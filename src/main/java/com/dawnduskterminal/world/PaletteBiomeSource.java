package com.dawnduskterminal.world;

import com.dawnduskterminal.DawnDuskTerminal;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.levelgen.DensityFunction;

/** 以原版气候表为主，并在极温区按原版气候点配置模组群系。 */
public final class PaletteBiomeSource extends BiomeSource {
    private static final float FLOATREES_DEPTH = -1.6F;
    private static final long VANILLA_TEMPERATURE_EDGE = Climate.quantizeCoord(1.0F);
    private static final Climate.Parameter HOT = Climate.Parameter.span(1.0F, ChronoTemperature.CLIMATE_LIMIT);
    private static final Climate.Parameter COLD = Climate.Parameter.span(-ChronoTemperature.CLIMATE_LIMIT, -1.0F);

    public static final MapCodec<PaletteBiomeSource> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
                    MultiNoiseBiomeSourceParameterList.CODEC.fieldOf("preset").forGetter(PaletteBiomeSource::preset),
                    RegistryCodecs.homogeneousList(Registries.BIOME)
                            .fieldOf("palette")
                            .forGetter(PaletteBiomeSource::palette))
            .apply(inst, PaletteBiomeSource::new));

    private final Holder<MultiNoiseBiomeSourceParameterList> preset;
    private final HolderSet<Biome> palette;
    private volatile Map<ResourceLocation, Holder<Biome>> replacements;
    private volatile Map<Holder<Biome>, Holder<Biome>> holderReplacements;
    public static final ResourceKey<Biome> WILD_VOID =
            ResourceKey.create(Registries.BIOME, DawnDuskTerminal.id("wild_void"));
    private volatile WildVoidDensity voidShape;
    private Holder<Biome> wildVoid;

    public void bindWildVoid(ChronoTerrain terrain) {
        this.wildVoid = copyOf("wild_void");
        this.voidShape = terrain.wildVoidShape();
    }

    private volatile PolarTables polarTables;
    private volatile Holder<Biome> floatrees;

    private record PolarTables(Climate.ParameterList<Holder<Biome>> hot, Climate.ParameterList<Holder<Biome>> cold) {}

    /**
     * 每线程一份的晨昏线温度缓存
     *
     * <p>{@code LevelChunkSection#fillBiomesFromNoise} 的循环是 x 外层 y 中层 z 内层，同一个
     * {@code (x, z)} 在一节里被问四次、跨二十四节被问九十六次。晨昏线维度的温度只由 x/z 决定
     * （{@code noise_settings/chrono.json} 里 {@code y_scale} 与 {@code shift_y} 都是 0），所以直接缓存
     * 换好的温度，一个区块的十六个水平采样位置各占一个槽位。
     *
     * <p>缓存按采样器身份分代：换区块时采样器跟着换，整张表清空。这样既不会把上一个区块的值带过来，
     * 也不会长期持有 {@code NoiseChunk} 的函数图（采样器用弱引用比较）
     */
    private static final ThreadLocal<ShiftedTemperatureCache> SHIFTED_TEMPERATURES =
            ThreadLocal.withInitial(ShiftedTemperatureCache::new);

    public PaletteBiomeSource(Holder<MultiNoiseBiomeSourceParameterList> preset, HolderSet<Biome> palette) {
        this.preset = preset;
        this.palette = palette;
    }

    private Holder<Biome> floatrees() {
        Holder<Biome> holder = this.floatrees;
        if (holder == null) {
            this.floatrees = holder = copyOf("floatrees");
        }
        return holder;
    }

    /** 从调色板标签里按下标路径找同名副本，找不到返回 null */
    private Holder<Biome> copyOf(String path) {
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, DawnDuskTerminal.id(path));
        for (Holder<Biome> candidate : this.palette) {
            if (candidate.is(key)) {
                return candidate;
            }
        }
        return null;
    }

    private Map<ResourceLocation, Holder<Biome>> replacements() {
        Map<ResourceLocation, Holder<Biome>> map = this.replacements;
        if (map == null) {
            Map<ResourceLocation, Holder<Biome>> built = new HashMap<>();
            for (Holder<Biome> holder : this.palette) {
                holder.unwrapKey()
                        .ifPresent(key -> built.put(
                                ResourceLocation.withDefaultNamespace(
                                        key.location().getPath()),
                                holder));
            }
            if (built.isEmpty()) {
                DawnDuskTerminal.LOGGER.error(
                        "DawnDuskTerminal palette biome source resolved no biome copies ({} entries in the tag); "
                                + "the chrono dimension would generate with vanilla biomes",
                        this.palette.size());
            }
            this.replacements = map = Map.copyOf(built);
        }
        return map;
    }

    public Holder<MultiNoiseBiomeSourceParameterList> preset() {
        return this.preset;
    }

    /**
     * 原版群系持有者到调色板副本的直映射
     *
     * <p>{@link #getNoiseBiome} 每区块要查一千五百多次，原来每次都要 {@code unwrapKey()} 包一层
     * {@code Optional}、拼一个 {@code ResourceLocation} 再走哈希表。持有者在注册表里是稳定的，所以按
     * 身份建一张表，逐次查询只剩一次身份查找
     */
    private Map<Holder<Biome>, Holder<Biome>> holderReplacements() {
        Map<Holder<Biome>, Holder<Biome>> map = this.holderReplacements;
        if (map == null) {
            Map<ResourceLocation, Holder<Biome>> byPath = replacements();
            Map<Holder<Biome>, Holder<Biome>> built = new IdentityHashMap<>();
            for (Pair<Climate.ParameterPoint, Holder<Biome>> entry :
                    this.preset.value().parameters().values()) {
                Holder<Biome> base = entry.getSecond();
                ResourceKey<Biome> key = base.unwrapKey().orElse(null);
                if (key == null || !"minecraft".equals(key.location().getNamespace())) {
                    continue;
                }
                Holder<Biome> replacement = byPath.get(
                        ResourceLocation.withDefaultNamespace(key.location().getPath()));
                if (replacement != null) {
                    built.put(base, replacement);
                }
            }
            this.holderReplacements = map = Collections.unmodifiableMap(built);
        }
        return map;
    }

    private PolarTables polarTables() {
        PolarTables tables = this.polarTables;
        if (tables == null) {
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> vanilla =
                    this.preset.value().parameters().values();
            Map<Climate.ParameterPoint, Holder<Biome>> hot = new LinkedHashMap<>();
            Map<Climate.ParameterPoint, Holder<Biome>> cold = new LinkedHashMap<>();

            addTemplate(hot, vanilla, Biomes.BADLANDS, "polar_dawn", HOT);
            addTemplate(hot, vanilla, Biomes.PLAINS, "white_land", HOT);
            addTemplate(cold, vanilla, Biomes.BADLANDS, "polar_dusk", COLD);
            addTemplate(cold, vanilla, Biomes.DESERT, "blue_desert", COLD);

            // 延伸接触原版温度端点的气候点，使河海与极带陆地使用相同的温度区间。
            // 自定义模板优先占用完全相同的气候点。两个自定义模板重合时按上面的声明顺序处理。
            for (var entry : vanilla) {
                Climate.ParameterPoint point = entry.getFirst();
                Holder<Biome> biome = entry.getSecond();
                if (point.temperature().max() == VANILLA_TEMPERATURE_EDGE) {
                    hot.putIfAbsent(withTemperature(point, HOT), biome);
                }
                if (point.temperature().min() == -VANILLA_TEMPERATURE_EDGE) {
                    cold.putIfAbsent(withTemperature(point, COLD), biome);
                }
            }
            this.polarTables = tables = new PolarTables(parameterList(hot), parameterList(cold));
        }
        return tables;
    }

    private void addTemplate(
            Map<Climate.ParameterPoint, Holder<Biome>> destination,
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> vanilla,
            ResourceKey<Biome> template,
            String replacement,
            Climate.Parameter temperature) {
        Holder<Biome> biome = copyOf(replacement);
        if (biome != null) {
            for (var entry : vanilla) {
                if (entry.getSecond().is(template)) {
                    destination.putIfAbsent(withTemperature(entry.getFirst(), temperature), biome);
                }
            }
        }
    }

    private static Climate.ParameterPoint withTemperature(Climate.ParameterPoint point, Climate.Parameter temperature) {
        return new Climate.ParameterPoint(
                temperature,
                point.humidity(),
                point.continentalness(),
                point.erosion(),
                point.depth(),
                point.weirdness(),
                point.offset());
    }

    private static Climate.ParameterList<Holder<Biome>> parameterList(
            Map<Climate.ParameterPoint, Holder<Biome>> points) {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> values = new ArrayList<>(points.size());
        points.forEach((point, biome) -> values.add(Pair.of(point, biome)));
        return new Climate.ParameterList<>(values);
    }

    public HolderSet<Biome> palette() {
        return this.palette;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.concat(
                this.preset.value().parameters().values().stream().map(Pair::getSecond), this.palette.stream());
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        DensityFunction.SinglePointContext context = new DensityFunction.SinglePointContext(
                QuartPos.toBlock(quartX), QuartPos.toBlock(quartY), QuartPos.toBlock(quartZ));
        WildVoidDensity shape = this.voidShape;
        if (shape != null && this.wildVoid != null && shape.coversBiome(context)) {
            return this.wildVoid;
        }
        float depth = (float) sampler.depth().compute(context);
        Holder<Biome> floatreesHolder = floatrees();
        if (floatreesHolder != null && Climate.unquantizeCoord(Climate.quantizeCoord(depth)) < FLOATREES_DEPTH) {
            return floatreesHolder;
        }
        double temperature = SHIFTED_TEMPERATURES.get().shifted(sampler, quartX, quartZ, context);
        Climate.TargetPoint target = Climate.target(
                (float) temperature,
                (float) sampler.humidity().compute(context),
                (float) sampler.continentalness().compute(context),
                (float) sampler.erosion().compute(context),
                depth,
                (float) sampler.weirdness().compute(context));
        Climate.ParameterList<Holder<Biome>> parameters = this.preset.value().parameters();
        if (target.temperature() > VANILLA_TEMPERATURE_EDGE) {
            parameters = polarTables().hot();
        } else if (target.temperature() < -VANILLA_TEMPERATURE_EDGE) {
            parameters = polarTables().cold();
        }
        Holder<Biome> base = parameters.findValue(target);
        Holder<Biome> replacement = holderReplacements().get(base);
        return replacement != null ? replacement : base;
    }

    /**
     * {@code (x, z)} 到已偏移温度的直映射缓存，共十六个槽位。
     *
     * <p>水平坐标的低两位各索引一个维度。采样器或晨昏线变化时，整个缓存失效。
     */
    private static final class ShiftedTemperatureCache {
        private static final int SLOTS = 16;

        private final boolean[] valid = new boolean[SLOTS];
        private final int[] xs = new int[SLOTS];
        private final int[] zs = new int[SLOTS];
        private final double[] values = new double[SLOTS];
        private WeakReference<Climate.Sampler> sampler;
        private ChronoLine line;

        double shifted(Climate.Sampler current, int quartX, int quartZ, DensityFunction.SinglePointContext context) {
            ChronoLine currentLine = ChronoLineState.serverLine();
            if (this.sampler == null || this.sampler.get() != current || this.line != currentLine) {
                this.sampler = new WeakReference<>(current);
                this.line = currentLine;
                Arrays.fill(this.valid, false);
            }
            int slot = (quartX & 3) * 4 + (quartZ & 3);
            if (this.valid[slot] && this.xs[slot] == quartX && this.zs[slot] == quartZ) {
                return this.values[slot];
            }
            double vanilla = current.temperature().compute(context);
            double shifted = ChronoTemperature.shift(vanilla, context.blockX(), context.blockZ());
            this.valid[slot] = true;
            this.xs[slot] = quartX;
            this.zs[slot] = quartZ;
            this.values[slot] = shifted;
            return shifted;
        }
    }
}
