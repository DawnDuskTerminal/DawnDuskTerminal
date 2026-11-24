package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.ChronoLine;
import com.dawnduskterminal.world.ChronoLineState;
import com.dawnduskterminal.world.ChronoTemperature;
import com.dawnduskterminal.world.ChronoVeinPalette;
import com.dawnduskterminal.world.PaletteBiomeSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.material.MaterialRuleList;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFBlocks;

/**
 * 晨昏线维度自己拥有地形与群系：噪声设置直接指向模组方块，群系是忠实副本且特征表也铺同样的方块。
 * 两者合起来让维度在生成时就铺好自己的调色板，不需要对成品区块再做一遍处理
 *
 * <p>注意：{@code GameTestServer} 的世界由 {@code WorldPresets.FLAT} 构建，只带原版维度，所以任何需要
 * 晨昏线 {@code ServerLevel} 的地方都得处理它在这里不存在的情况
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class WorldgenTests {
    private static final String MOD_ID = "dawnduskterminal";
    private static final ResourceLocation CHRONO = ResourceLocation.fromNamespaceAndPath(MOD_ID, "chrono");
    private static final TagKey<Biome> HOLLOW_TREE_BIOMES =
            TagKey.create(Registries.BIOME, ResourceLocation.parse("twilightforest:valid_hollow_tree_biomes"));

    /** 移植后的主世界噪声路由需要的所有密度函数 */
    private static final String[] PORTED_DENSITY_FUNCTIONS = {
        "shift_x",
        "shift_z",
        "y",
        "overworld/base_3d_noise",
        "overworld/continents",
        "overworld/erosion",
        "overworld/depth",
        "overworld/factor",
        "overworld/jaggedness",
        "overworld/offset",
        "overworld/ridges",
        "overworld/ridges_folded",
        "overworld/sloped_cheese",
        "overworld/caves/entrances",
        "overworld/caves/noodle",
        "overworld/caves/pillars",
        "overworld/caves/spaghetti_2d",
        "overworld/caves/spaghetti_2d_thickness_modulator",
        "overworld/caves/spaghetti_roughness_function",
    };

    /** 那些密度函数读取的、与种子相关的噪声参数 */
    private static final String[] PORTED_NOISE_PARAMETERS = {
        "continentalness",
        "erosion",
        "ridge",
        "temperature",
        "vegetation",
        "jagged",
        "offset",
        "surface",
        "surface_swamp",
        "calcite",
        "gravel",
        "powder_snow",
        "ice",
        "packed_ice",
        "cave_cheese",
        "cave_layer",
        "cave_entrance",
        "spaghetti_2d",
        "spaghetti_roughness",
        "noodle",
        "pillar",
        "ore_vein_a",
        "ore_vein_b",
        "ore_veininess",
        "ore_gap",
        "aquifer_barrier",
        "aquifer_fluid_level_floodedness",
        "aquifer_fluid_level_spread",
        "aquifer_lava",
    };

    /** 维度必须有副本的主世界群系抽样 */
    /** 自建群系，没有同名原版副本，只查模组侧注册 */
    private static final String[] OWN_BIOMES = {"floatrees", "white_land", "blue_desert", "polar_dawn", "polar_dusk"};

    private static final String[] COPIED_BIOMES = {
        "plains",
        "desert",
        "sunflower_plains",
        "ocean",
        "deep_ocean",
        "frozen_ocean",
        "forest",
        "taiga",
        "jungle",
        "swamp",
        "mushroom_fields",
        "stony_peaks",
        "deep_dark",
        "lush_caves",
        "dripstone_caves",
    };

    private static NoiseGeneratorSettings chronoSettings(GameTestHelper helper) {
        Registry<NoiseGeneratorSettings> registry =
                helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        NoiseGeneratorSettings settings = registry.get(CHRONO);
        helper.assertTrue(settings != null, "noise settings " + CHRONO + " missing");
        return settings;
    }

    /** 收集一棵密度函数树读取到的噪声键 */
    private static Set<ResourceLocation> noiseKeys(DensityFunction function) {
        Set<ResourceLocation> keys = new LinkedHashSet<>();
        function.mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction inner) {
                return inner;
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
                holder.noiseData().unwrapKey().ifPresent(key -> keys.add(key.location()));
                return holder;
            }
        });
        return keys;
    }

    @GameTest(template = "ddt_empty")
    public static void chronoDimensionTypeIsRegistered(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        Registry<DimensionType> types = access.registryOrThrow(Registries.DIMENSION_TYPE);
        helper.assertTrue(types.containsKey(CHRONO), "chrono dimension type must be registered");
        helper.assertTrue(
                access.registryOrThrow(Registries.NOISE_SETTINGS).containsKey(CHRONO),
                "chrono noise settings must be registered");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void portedDensityFunctionsAreRegistered(GameTestHelper helper) {
        Registry<DensityFunction> registry =
                helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.DENSITY_FUNCTION);
        for (String id : PORTED_DENSITY_FUNCTIONS) {
            helper.assertTrue(
                    registry.containsKey(ResourceLocation.fromNamespaceAndPath(MOD_ID, id)),
                    "ported density function missing: " + id);
        }
        // 移植是复制而不是替换：原版原件必须仍然完好
        helper.assertTrue(
                registry.containsKey(ResourceLocation.withDefaultNamespace("overworld/sloped_cheese")),
                "vanilla overworld/sloped_cheese must stay registered");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void portedNoiseParametersAreRegistered(GameTestHelper helper) {
        Registry<NormalNoise.NoiseParameters> registry =
                helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.NOISE);
        for (String id : PORTED_NOISE_PARAMETERS) {
            helper.assertTrue(
                    registry.containsKey(ResourceLocation.fromNamespaceAndPath(MOD_ID, id)),
                    "ported noise parameter missing: " + id);
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void temperatureUsesVanillaNoiseAndLinearOffset(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        var noises = access.lookupOrThrow(Registries.NOISE);
        var overworld = access.registryOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var vanilla = RandomState.create(overworld, noises, 42L).sampler();
        var chrono = RandomState.create(chronoSettings(helper), noises, 42L).sampler();
        ChronoLine previous = ChronoLineState.hasServerLine() ? ChronoLineState.serverLine() : null;
        ChronoLineState.setServerLine(new ChronoLine(0, 100, 0, 1));
        try {
            int[] distances = {-20000, -8000, -5000, 0, 5000, 8000, 20000};
            double[] offsets = {-4.0D, -1.6D, -1.0D, 0.0D, 1.0D, 1.6D, 4.0D};
            for (int i = 0; i < distances.length; i++) {
                int z = 100 + distances[i];
                var context = new DensityFunction.SinglePointContext(300, 64, z);
                double raw = chrono.temperature().compute(context);
                helper.assertTrue(
                        raw == vanilla.temperature().compute(context),
                        "chrono must use the unmodified vanilla temperature field at z=" + z);
                helper.assertTrue(
                        ChronoTemperature.shift(raw, 300, z) == Math.clamp(raw + offsets[i], -2.0D, 2.0D),
                        "the signed distance must add the expected offset at z=" + z);
            }
        } finally {
            if (previous != null) {
                ChronoLineState.setServerLine(previous);
            } else {
                ChronoLineState.resetServerLine();
            }
        }
        helper.succeed();
    }

    /**
     * 把噪声参数搬进自己命名空间的全部意义。引擎把每个噪声场都建成
     * {@code NormalNoise.create(randomFactory.fromHashOf(key.location()), params)}，由世界种子决定，所以
     * {@code dawnduskterminal:*} 的键会得到一个与 {@code minecraft:*} 不同、且由种子派生的场，而原版原件
     * 保留自己的场
     */
    @GameTest(template = "ddt_empty")
    public static void terrainNoiseIsSeedDerived(GameTestHelper helper) {
        NoiseRouter router = chronoSettings(helper).noiseRouter();
        Set<ResourceLocation> keys = new LinkedHashSet<>();
        for (DensityFunction function : List.of(
                router.barrierNoise(),
                router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(),
                router.lavaNoise(),
                router.vegetation(),
                router.continents(),
                router.erosion(),
                router.depth(),
                router.ridges(),
                router.initialDensityWithoutJaggedness(),
                router.finalDensity(),
                router.veinToggle(),
                router.veinRidged(),
                router.veinGap())) {
            keys.addAll(noiseKeys(function));
        }
        helper.assertTrue(!keys.isEmpty(), "the chrono router must read at least one noise parameter");
        for (ResourceLocation key : keys) {
            helper.assertTrue(
                    MOD_ID.equals(key.getNamespace()),
                    "router noise " + key + " is still in the vanilla namespace, so its field would be "
                            + "identical to the Overworld's instead of seed-derived");
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void paletteBiomeCopiesAreRegistered(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.BIOME);
        for (String name : COPIED_BIOMES) {
            helper.assertTrue(
                    biomes.containsKey(ResourceLocation.fromNamespaceAndPath(MOD_ID, name)),
                    "chrono biome copy missing: " + name);
            helper.assertTrue(
                    biomes.containsKey(ResourceLocation.withDefaultNamespace(name)),
                    "the vanilla biome must stay untouched next to its copy: " + name);
        }
        for (String name : OWN_BIOMES) {
            helper.assertTrue(
                    biomes.containsKey(ResourceLocation.fromNamespaceAndPath(MOD_ID, name)),
                    "self-made chrono biome missing: " + name);
        }
        helper.succeed();
    }

    /**
     * 复制群系的意义：副本必须铺*我们的*特征，这样维度生成时的花岗岩 闪长岩 安山岩 凝灰岩 岩浆 雪
     * 才会以模组方块出现
     */
    @GameTest(template = "ddt_empty")
    public static void paletteBiomeCopiesCarryOurFeatures(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.BIOME);
        Biome plains = biomes.get(ResourceLocation.fromNamespaceAndPath(MOD_ID, "plains"));
        helper.assertTrue(plains != null, MOD_ID + ":plains must be registered");
        Set<ResourceLocation> featureIds = new LinkedHashSet<>();
        for (HolderSet<PlacedFeature> step : plains.getGenerationSettings().features()) {
            for (Holder<PlacedFeature> holder : step) {
                holder.unwrapKey().ifPresent(key -> featureIds.add(key.location()));
            }
        }
        helper.assertTrue(!featureIds.isEmpty(), "the biome copy must keep its feature list");
        helper.assertTrue(
                featureIds.stream().anyMatch(id -> MOD_ID.equals(id.getNamespace())),
                "the biome copy must place our own features, found " + featureIds);
        helper.succeed();
    }

    /**
     * 高大暮色橡树（Robust Twilight Oak）是暮色森林的结构 {@code twilightforest:hollow_tree}，不是 placed_feature，
     * 它的群系选择器是 {@code #twilightforest:valid_hollow_tree_biomes}。那份标签原本只列暮色森林自己的群系，
     * 本模组的数据包把副本追加进去。标签一旦丢失，巨树只是不再出现，不会有任何报错
     */
    @GameTest(template = "ddt_empty")
    public static void hollowTreesAcceptTheChronoBiomes(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.BIOME);
        HolderSet.Named<Biome> valid = biomes.getTag(HOLLOW_TREE_BIOMES).orElse(null);
        helper.assertTrue(valid != null, HOLLOW_TREE_BIOMES.location() + " must resolve");
        for (String name : new String[] {
            "forest",
            "birch_forest",
            "old_growth_birch_forest",
            "flower_forest",
            "dark_forest",
            "mushroom_fields",
            "savanna"
        }) {
            ResourceKey<Biome> key =
                    ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MOD_ID, name));
            Holder<Biome> biome = biomes.getHolder(key).orElse(null);
            helper.assertTrue(biome != null, "chrono biome copy missing: " + name);
            helper.assertTrue(
                    valid.contains(biome), MOD_ID + ":" + name + " must be in " + HOLLOW_TREE_BIOMES.location());
        }
        helper.succeed();
    }

    /**
     * 大型暮色橡树的地面门槛是 placed_feature 里的 {@code would_survive}，判定的是暮色橡树树苗；
     * 那个树苗注册成原版 {@code SaplingBlock}，{@code canSurvive} 只认 {@code #minecraft:dirt}。霞土霞草
     * 一旦掉出这个标签，树会静默消失，所以这里直接问方块本身
     */
    @GameTest(template = "ddt_empty")
    public static void twilightOakSaplingSurvivesOnRosyGround(GameTestHelper helper) {
        for (Block ground : new Block[] {ModTerrainBlocks.ROSY_GRASS.get(), ModTerrainBlocks.ROSY_DIRT.get()}) {
            helper.assertTrue(
                    ground.defaultBlockState().is(BlockTags.DIRT),
                    ground.getName().getString() + " must stay in the #minecraft:dirt tag the sapling checks");
        }
        Block sapling = TFBlocks.TWILIGHT_OAK_SAPLING.get();
        helper.assertTrue(
                sapling != Blocks.AIR,
                "twilightforest:twilight_oak_sapling must be registered, Twilight Forest is a required dependency");
        BlockPos groundPos = new BlockPos(1, 1, 1);
        helper.setBlock(groundPos, ModTerrainBlocks.ROSY_GRASS.get());
        helper.assertTrue(
                sapling.defaultBlockState()
                        .canSurvive(
                                helper.getLevel(), helper.absolutePos(groundPos).above()),
                "the sapling must accept rosy grass as ground, otherwise no twilight oak can be placed on the chrono surface");
        helper.succeed();
    }

    /** 群系副本是照原版手抄的特征表，抄漏一条不会有任何报错，只是那种树不再出现 */
    @GameTest(template = "ddt_empty")
    public static void paletteBiomeCopiesRequestTwilightOaks(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.BIOME);
        ResourceLocation largeOak = ResourceLocation.parse("twilightforest:tree/large_twilight_oak_tree");
        for (String name : new String[] {"forest", "birch_forest", "old_growth_birch_forest", "savanna"}) {
            Biome biome = biomes.get(ResourceLocation.fromNamespaceAndPath(MOD_ID, name));
            helper.assertTrue(biome != null, "chrono biome copy missing: " + name);
            Set<ResourceLocation> featureIds = new LinkedHashSet<>();
            for (HolderSet<PlacedFeature> step : biome.getGenerationSettings().features()) {
                for (Holder<PlacedFeature> holder : step) {
                    holder.unwrapKey().ifPresent(key -> featureIds.add(key.location()));
                }
            }
            helper.assertTrue(featureIds.contains(largeOak), MOD_ID + ":" + name + " must keep requesting " + largeOak);
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void paletteBiomeSourceIsRegistered(GameTestHelper helper) {
        Registry<MapCodec<? extends BiomeSource>> registry =
                helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.BIOME_SOURCE);
        helper.assertTrue(
                registry.containsKey(ResourceLocation.fromNamespaceAndPath(MOD_ID, "palette")),
                "the chrono biome source type must be registered");
        helper.succeed();
    }

    /** 游戏测试服使用平坦预设，因此从资源管理器解码晨昏线维度的实际群系源。 */
    private static PaletteBiomeSource readChronoBiomeSource(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, "dimension/chrono.json");
        JsonObject dimension;
        try (BufferedReader reader = helper.getLevel()
                .getServer()
                .getResourceManager()
                .getResource(id)
                .orElseThrow()
                .openAsReader()) {
            dimension = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError("cannot read " + id, e);
        }
        BiomeSource source = BiomeSource.CODEC
                .parse(
                        RegistryOps.create(JsonOps.INSTANCE, access),
                        dimension.getAsJsonObject("generator").getAsJsonObject("biome_source"))
                .getOrThrow();
        helper.assertTrue(
                source instanceof PaletteBiomeSource, "chrono must use the palette biome source, got " + source);
        return (PaletteBiomeSource) source;
    }

    @GameTest(template = "ddt_empty")
    public static void chronoDimensionDeclaresThePaletteSource(GameTestHelper helper) {
        PaletteBiomeSource chrono = readChronoBiomeSource(helper);
        helper.assertTrue(
                chrono.preset()
                        .is(ResourceKey.create(
                                Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                                ResourceLocation.withDefaultNamespace("overworld"))),
                "the palette source must read the vanilla overworld parameter list");
        List<ResourceKey<Biome>> possible = chrono.possibleBiomes().stream()
                .map(holder -> holder.unwrapKey().orElseThrow())
                .toList();
        for (String path : new String[] {"polar_dawn", "polar_dusk", "blue_desert", "white_land", "desert"}) {
            ResourceKey<Biome> key =
                    ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MOD_ID, path));
            helper.assertTrue(
                    possible.contains(key), "the palette biome must be reported as possible, missing " + path);
        }
        var settings = helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        new NoiseBasedChunkGenerator(
                        chrono, settings.getHolderOrThrow(ResourceKey.create(Registries.NOISE_SETTINGS, CHRONO)))
                .validate();
        helper.succeed();
    }

    /** 保留下来，让移植后的特征排序仍然被跑到，尽管别处都不需要它 */
    @GameTest(template = "ddt_empty")
    public static void configuredFeaturesAreRegistered(GameTestHelper helper) {
        Registry<ConfiguredFeature<?, ?>> registry =
                helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        helper.assertTrue(
                registry.containsKey(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ore_granite")),
                "the palette copy of the granite ore feature must be registered");
        helper.assertTrue(
                registry.containsKey(ResourceLocation.withDefaultNamespace("ore_granite")),
                "the vanilla granite ore feature must stay registered for the overworld");
        helper.succeed();
    }

    /** 原版大矿脉在 {@code OreVeinifier} 里写死填充物；这里验的是替换结果 */
    @GameTest(template = "ddt_empty")
    public static void veinFillersUseThePalette(GameTestHelper helper) {
        helper.assertTrue(
                ChronoVeinPalette.remap(Blocks.GRANITE.defaultBlockState()).is(ModTerrainBlocks.OCHRE.get()),
                "the copper vein filler (granite) must become ochre");
        helper.assertTrue(
                ChronoVeinPalette.remap(Blocks.TUFF.defaultBlockState()).is(ModTerrainBlocks.TWILIGHT_STONE.get()),
                "the iron vein filler (tuff) must become twilight_stone");
        helper.assertTrue(
                ChronoVeinPalette.remap(Blocks.STONE.defaultBlockState()).is(Blocks.STONE),
                "unrelated blocks must pass through untouched");
        // 区块方块规则用 null 表示「保留默认方块」，这是最常见的情况
        helper.assertTrue(
                ChronoVeinPalette.remap(null) == null,
                "a null block state must pass through: it means 'leave the default block here'");
        // 包装器必须把这个 null 透传，而不是对它 NPE，那正是当初让区块生成卡死的 bug
        helper.assertTrue(
                ChronoVeinPalette.wrap(context -> null).calculate(null) == null,
                "the wrapped rule must forward a null result untouched");
        helper.assertTrue(
                ChronoVeinPalette.wrap(context -> Blocks.GRANITE.defaultBlockState())
                        .calculate(null)
                        .is(ModTerrainBlocks.OCHRE.get()),
                "the wrapped rule must still remap a real vein filler");
        var ordered = new MaterialRuleList(
                List.of(context -> null, context -> Blocks.GRANITE.defaultBlockState(), context -> {
                    throw new AssertionError("the first non-null material must stop rule evaluation");
                }));
        helper.assertTrue(
                ChronoVeinPalette.wrap(ordered).calculate(null).is(ModTerrainBlocks.OCHRE.get()),
                "the rule list must preserve order and remap the first non-null result");
        helper.succeed();
    }

    /**
     * 证明群系替换真的发生了：构造维度声明的那同一个源，检查原版主世界预设能报出的每个群系是否都变成
     * 了我们的副本。这条失败就说明维度在用原版群系生成，因而也在用原版特征
     */
    @GameTest(template = "ddt_empty")
    public static void paletteBiomeSourceSubstitutesThePreset(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
        Registry<MultiNoiseBiomeSourceParameterList> presets =
                access.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        Holder<MultiNoiseBiomeSourceParameterList> overworld = presets.getHolderOrThrow(ResourceKey.create(
                Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                ResourceLocation.withDefaultNamespace("overworld")));
        HolderSet<Biome> palette = biomes.getTag(TagKey.create(
                        Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MOD_ID, "palette_biomes")))
                .orElseThrow();
        helper.assertTrue(
                palette.size() > 40, "the palette tag must resolve to the biome copies, got " + palette.size());

        BiomeSource delegate = MultiNoiseBiomeSource.createFromPreset(overworld);
        PaletteBiomeSource source = new PaletteBiomeSource(overworld, palette);
        ChronoLine previous = ChronoLineState.hasServerLine() ? ChronoLineState.serverLine() : null;
        ChronoLineState.setServerLine(ChronoLine.fallback());
        try {
            Set<ResourceLocation> leaked = new LinkedHashSet<>();
            int sampled = 0;
            // 沿晨昏线法向选取多个位置，覆盖不同的气候温度。
            for (double param : new double[] {-0.7D, -0.5D, -0.2D, 0.0D, 0.45D, 0.7D}) {
                int quartX = QuartPos.fromBlock((int) Math.round(param * 5000.0D));
                for (double humidity : new double[] {-1.0D, 0.0D, 1.0D}) {
                    for (double continentalness : new double[] {-1.0D, -0.5D, 0.0D, 0.5D, 1.0D}) {
                        for (double erosion : new double[] {-1.0D, 0.0D, 1.0D}) {
                            for (double weirdness : new double[] {-1.0D, -0.5D, 0.0D, 0.5D, 1.0D}) {
                                Climate.Sampler sampler =
                                        constantSampler(0.0D, humidity, continentalness, erosion, weirdness);
                                Holder<Biome> got = source.getNoiseBiome(quartX, 16, 0, sampler);
                                sampled++;
                                ResourceLocation key = got.unwrapKey()
                                        .map(ResourceKey::location)
                                        .orElse(null);
                                if (key == null || !MOD_ID.equals(key.getNamespace())) {
                                    leaked.add(key);
                                }
                            }
                        }
                    }
                }
            }
            helper.assertTrue(
                    leaked.isEmpty(), "these biomes were not substituted out of " + sampled + " samples: " + leaked);

            // 核对原版沙漠温区的选格仍对应同名副本。
            ResourceKey<Biome> vanillaDesert =
                    ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("desert"));
            ResourceKey<Biome> desert =
                    ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MOD_ID, "desert"));
            int hotQuartX = QuartPos.fromBlock(3500);
            int desertSamples = 0;
            for (double humidity : new double[] {-1.0D, -0.5D}) {
                for (double continentalness : new double[] {0.3D, 1.0D}) {
                    for (double erosion : new double[] {-1.0D, 0.0D, 1.0D}) {
                        for (double weirdness : new double[] {-1.0D, -0.5D, 0.5D, 1.0D}) {
                            Climate.Sampler sampler =
                                    constantSampler(0.875D, humidity, continentalness, erosion, weirdness);
                            if (!delegate.getNoiseBiome(0, 16, 0, sampler).is(vanillaDesert)) {
                                continue;
                            }
                            desertSamples++;
                            Holder<Biome> got = source.getNoiseBiome(
                                    hotQuartX,
                                    16,
                                    0,
                                    constantSampler(0.0D, humidity, continentalness, erosion, weirdness));
                            helper.assertTrue(
                                    got.is(desert),
                                    "vanilla desert must become the desert copy, got "
                                            + got.unwrapKey()
                                                    .map(ResourceKey::location)
                                                    .orElse(null));
                        }
                    }
                }
            }
            helper.assertTrue(desertSamples > 0, "no sampler produced a vanilla desert to swap");
        } finally {
            if (previous != null) {
                ChronoLineState.setServerLine(previous);
            } else {
                ChronoLineState.resetServerLine();
            }
        }

        helper.succeed();
    }

    /**
     * 结构判定读 {@code RandomState.sampler()}，区块落盘读 {@code NoiseChunk.cachedClimateSampler}。
     * 两者的温度是两套不同的函数对象，但同一个坐标上的数值相同。群系结果只能由数值决定，不能被函数身份
     * 或缓存分代带偏，否则两个采样器又会判出不同群系
     */
    @GameTest(template = "ddt_empty")
    public static void biomeSelectionIgnoresTheSamplerIdentity(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
        Registry<MultiNoiseBiomeSourceParameterList> presets =
                access.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        Holder<MultiNoiseBiomeSourceParameterList> overworld = presets.getHolderOrThrow(ResourceKey.create(
                Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                ResourceLocation.withDefaultNamespace("overworld")));
        HolderSet<Biome> palette = biomes.getTag(TagKey.create(
                        Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MOD_ID, "palette_biomes")))
                .orElseThrow();
        PaletteBiomeSource source = new PaletteBiomeSource(overworld, palette);

        ChronoLine previous = ChronoLineState.hasServerLine() ? ChronoLineState.serverLine() : null;
        ChronoLineState.setServerLine(ChronoLine.fallback());
        try {
            int compared = 0;
            for (int quartX : new int[] {-875, -500, -200, 0, 200, 500, 875}) {
                for (int quartZ : new int[] {-200, 0, 200}) {
                    for (double humidity : new double[] {-1.0D, 0.0D, 1.0D}) {
                        for (double weirdness : new double[] {-1.0D, 0.0D, 1.0D}) {
                            // 两次调用各建一个 constant 函数对象，数值一样；缓存必须按数值重置而不是串值
                            ResourceKey<Biome> first = source.getNoiseBiome(
                                            quartX, 16, quartZ, constantSampler(0.3D, humidity, 0.0D, 0.0D, weirdness))
                                    .unwrapKey()
                                    .orElse(null);
                            ResourceKey<Biome> second = source.getNoiseBiome(
                                            quartX, 16, quartZ, constantSampler(0.3D, humidity, 0.0D, 0.0D, weirdness))
                                    .unwrapKey()
                                    .orElse(null);
                            helper.assertTrue(
                                    first != null && first.equals(second),
                                    "sampler identity leaked into the biome choice at quart (" + quartX + ", " + quartZ
                                            + "): " + first + " vs " + second);
                            compared++;
                        }
                    }
                }
            }
            helper.assertTrue(compared > 0, "the sampler-independence grid must not be empty");
        } finally {
            if (previous != null) {
                ChronoLineState.setServerLine(previous);
            } else {
                ChronoLineState.resetServerLine();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void polarClimatePreservesRiversOceansAndLand(GameTestHelper helper) {
        PaletteBiomeSource source = readChronoBiomeSource(helper);
        ChronoLine previous = ChronoLineState.hasServerLine() ? ChronoLineState.serverLine() : null;
        ChronoLineState.setServerLine(ChronoLine.fallback());
        try {
            for (double temperature : new double[] {1.2D, 2.0D, 3.0D}) {
                assertBiome(helper, source, temperature, 0.0D, -0.35D, 0.0D, 0.0D, "warm_ocean");
                assertBiome(helper, source, temperature, 0.0D, -0.65D, 0.0D, 0.0D, "warm_ocean");
                assertBiome(helper, source, temperature, 0.0D, -0.15D, -0.6D, 0.0D, "river");
                assertBiome(helper, source, -temperature, 0.0D, -0.35D, 0.0D, 0.0D, "frozen_ocean");
                assertBiome(helper, source, -temperature, 0.0D, -0.65D, 0.0D, 0.0D, "deep_frozen_ocean");
                assertBiome(helper, source, -temperature, 0.0D, -0.15D, -0.6D, 0.0D, "frozen_river");
            }
            Set<String> hotLand = samplePolarLand(source, 1.5D);
            Set<String> coldLand = samplePolarLand(source, -1.5D);
            helper.assertTrue(
                    hotLand.containsAll(Set.of("white_land", "polar_dawn")), "missing hot land biomes: " + hotLand);
            helper.assertTrue(
                    coldLand.containsAll(Set.of("polar_dusk", "blue_desert")), "missing cold land biomes: " + coldLand);
        } finally {
            if (previous != null) {
                ChronoLineState.setServerLine(previous);
            } else {
                ChronoLineState.resetServerLine();
            }
        }
        helper.succeed();
    }

    private static void assertBiome(
            GameTestHelper helper,
            PaletteBiomeSource source,
            double temperature,
            double humidity,
            double continentalness,
            double erosion,
            double weirdness,
            String expected) {
        var sampler = constantSampler(temperature, humidity, continentalness, erosion, weirdness);
        var biome = source.getNoiseBiome(0, 16, 0, sampler);
        helper.assertTrue(
                biome.is(ResourceLocation.fromNamespaceAndPath(MOD_ID, expected)),
                "expected " + expected + " at temperature " + temperature + ", got " + biome.unwrapKey());
    }

    private static Set<String> samplePolarLand(PaletteBiomeSource source, double temperature) {
        Set<String> paths = new LinkedHashSet<>();
        for (double humidity : new double[] {-0.7D, -0.2D, 0.0D, 0.2D, 0.6D}) {
            for (double continentalness : new double[] {-0.05D, 0.12D, 0.5D}) {
                for (double erosion : new double[] {-0.9D, -0.6D, -0.3D, 0.0D, 0.3D, 0.5D, 0.7D}) {
                    for (double weirdness : new double[] {-0.97D, -0.7D, -0.4D, 0.4D, 0.7D, 0.97D}) {
                        var sampler = constantSampler(temperature, humidity, continentalness, erosion, weirdness);
                        source.getNoiseBiome(0, 16, 0, sampler)
                                .unwrapKey()
                                .ifPresent(key -> paths.add(key.location().getPath()));
                    }
                }
            }
        }
        return paths;
    }

    private static Climate.Sampler constantSampler(
            double temperature, double humidity, double continentalness, double erosion, double weirdness) {
        return new Climate.Sampler(
                DensityFunctions.constant(temperature),
                DensityFunctions.constant(humidity),
                DensityFunctions.constant(continentalness),
                DensityFunctions.constant(erosion),
                DensityFunctions.constant(0.0D),
                DensityFunctions.constant(weirdness),
                List.of());
    }
}
