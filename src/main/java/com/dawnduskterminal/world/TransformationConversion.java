package com.dawnduskterminal.world;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModChronoOres;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import twilightforest.init.TFDataMaps;
import twilightforest.util.datamaps.EntityTransformation;

/**
 * 两瓶转换粉的对照表：方块、群系与实体，各自有一个正向（→ 主世界）与一个反向（→ 晨昏线）
 *
 * <p>这张表的内容全部来自项目里已有的记载，不是新定的：
 *
 * <ul>
 *   <li>方块照 {@code docs/biomes.md} 附录那张「原版方块到模组方块」表，再加上
 *       {@link ModChronoOres#overworldOres()} 那十二块两档矿石；
 *       岩浆（热情的暮光）不参与转换；模组独有的矿（赤钼、狄拉克、carid、易矿）与混乱石、
 *       炽热的方块没有对应物，保持原样
 *   <li>群系照「占位不改注册名」那条约定：副本的 ID 等于它顶替的原版群系，所以同一 ID 换命名空间即可；
 *       自建的几个里只有蓝沙有对应（{@code desert} 的同名副本被改成了 {@code blue_desert}），
 *       白地、极昼、极夜不参与。暮色森林那一侧照 {@code docs/biomes.md} 里
 *       「装了暮色森林内容的副本」那张表，反向查
 *   <li>实体照暮色森林自己的数据图 {@code twilightforest:transformation_powder}（{@link TFDataMaps#TRANSFORMATION_POWDER}）：
 *       那里面每一对都是双向的，本类只按「结果落在哪个命名空间」筛方向——
 *       正向要 {@code minecraft:}，反向要 {@code twilightforest:}。表外的实体（含玩家）没有对应物，
 *       自然不受影响
 * </ul>
 *
 * <p>查询全部返回 {@code null} 表示「这一格不在表里」，调用方据此跳过，不做任何兜底猜测。
 */
public final class TransformationConversion {
    /** 两瓶转换粉的作用半径：落点起算的球，单位格 */
    public static final double RADIUS = 5.0D;

    public static final String OVERWORLD_NAMESPACE = "minecraft";

    public static final String TWILIGHT_NAMESPACE = "twilightforest";

    private TransformationConversion() {}

    /**
     * 方块表：两张 {@link IdentityHashMap}，键按方块身份比
     *
     * <p>放在内部类里懒初始化：{@link ModTerrainBlocks} 那几个 {@code DeferredBlock} 要等注册完才有值，
     * 类加载早于注册就拿到 {@code null}
     */
    private static final class Tables {
        /** 晨昏线方块 → 主世界方块 */
        static final Map<Block, BlockState> TO_OVERWORLD = new IdentityHashMap<>();

        /** 主世界方块 → 晨昏线方块 */
        static final Map<Block, BlockState> TO_CHRONO = new IdentityHashMap<>();

        /**
         * 自建群系与它对应的原版格子；其余副本同名同 ID，不需要表
         *
         * <p>只有蓝沙这一格有对应：{@code desert} 的同名副本被改成了自建 ID {@code blue_desert}
         * （见 {@code docs/biomes.md}），所以沙漠与蓝沙互相换。白地、极昼、极夜不管
         */
        static final Map<ResourceLocation, ResourceLocation> CHRONO_BIOMES =
                Map.of(DawnDuskTerminal.id("blue_desert"), vanilla("desert"));

        /** 暮色森林群系 → 它被装进的那一格的原始群系，见 {@code docs/biomes.md} */
        static final Map<ResourceLocation, ResourceLocation> TWILIGHT_BIOMES = Map.of(
                twilight("dark_forest_center"), vanilla("dark_forest"),
                twilight("firefly_forest"), vanilla("birch_forest"),
                twilight("dense_forest"), vanilla("flower_forest"),
                twilight("enchanted_forest"), vanilla("jungle"),
                twilight("fire_swamp"), vanilla("swamp"),
                twilight("clearing"), vanilla("plains"),
                twilight("oak_savannah"), vanilla("savanna"),
                twilight("glacier"), vanilla("snowy_plains"),
                twilight("highlands"), vanilla("windswept_hills"),
                twilight("forest"), vanilla("forest"));

        static {
            pair(Blocks.GRASS_BLOCK, ModTerrainBlocks.ROSY_GRASS.get());
            pair(Blocks.DIRT, ModTerrainBlocks.ROSY_DIRT.get());
            pair(Blocks.STONE, ModTerrainBlocks.CANOPY_STONE.get());
            pair(Blocks.GRANITE, ModTerrainBlocks.OCHRE.get());
            pair(Blocks.DIORITE, ModTerrainBlocks.GREENSCHIST.get());
            pair(Blocks.ANDESITE, ModTerrainBlocks.ECLOGITE.get());
            pair(Blocks.DEEPSLATE, ModTerrainBlocks.CHARNOCKITE.get());
            pair(Blocks.TUFF, ModTerrainBlocks.TWILIGHT_STONE.get());
            pair(Blocks.SAND, ModTerrainBlocks.ROAD_SAND.get());
            pair(Blocks.RED_SAND, ModTerrainBlocks.UNCONSCIOUS_SAND.get());
            pair(Blocks.SANDSTONE, ModTerrainBlocks.SILTSTONE.get());
            pair(Blocks.ICE, ModTerrainBlocks.FROZEN_CANOPY.get());
            pair(Blocks.SNOW, ModTerrainBlocks.CANOPY_FROST.get());
            pair(Blocks.SNOW_BLOCK, ModTerrainBlocks.CANOPY_FROST_BLOCK.get());
            pair(Blocks.WATER, ModBlocks.PORTAL_FLUID.get());
            // 岩浆与热情的暮光不做对应：这两样不在转换的范围内
            pair(Blocks.COPPER_ORE, ModChronoOres.CANOPY_STONE_COPPER_ORE.get());
            pair(Blocks.IRON_ORE, ModChronoOres.CANOPY_STONE_IRON_ORE.get());
            pair(Blocks.GOLD_ORE, ModChronoOres.CANOPY_STONE_GOLD_ORE.get());
            pair(Blocks.REDSTONE_ORE, ModChronoOres.CANOPY_STONE_REDSTONE_ORE.get());
            pair(Blocks.LAPIS_ORE, ModChronoOres.CANOPY_STONE_LAPIS_ORE.get());
            pair(Blocks.DIAMOND_ORE, ModChronoOres.CANOPY_STONE_DIAMOND_ORE.get());
            pair(Blocks.DEEPSLATE_COPPER_ORE, ModChronoOres.CHARNOCKITE_COPPER_ORE.get());
            pair(Blocks.DEEPSLATE_IRON_ORE, ModChronoOres.CHARNOCKITE_IRON_ORE.get());
            pair(Blocks.DEEPSLATE_GOLD_ORE, ModChronoOres.CHARNOCKITE_GOLD_ORE.get());
            pair(Blocks.DEEPSLATE_REDSTONE_ORE, ModChronoOres.CHARNOCKITE_REDSTONE_ORE.get());
            pair(Blocks.DEEPSLATE_LAPIS_ORE, ModChronoOres.CHARNOCKITE_LAPIS_ORE.get());
            pair(Blocks.DEEPSLATE_DIAMOND_ORE, ModChronoOres.CHARNOCKITE_DIAMOND_ORE.get());
        }

        private static void pair(Block vanilla, Block chrono) {
            TO_OVERWORLD.put(chrono, vanilla.defaultBlockState());
            TO_CHRONO.put(vanilla, chrono.defaultBlockState());
        }

        private static ResourceLocation vanilla(String path) {
            return ResourceLocation.withDefaultNamespace(path);
        }

        private static ResourceLocation twilight(String path) {
            return ResourceLocation.fromNamespaceAndPath(TWILIGHT_NAMESPACE, path);
        }
    }

    /**
     * 一格方块换成主世界的对应物
     *
     * <p>返回的状态会把两边同名的属性照抄一遍：雪层的层数、原木的 {@code axis}、草方块的 {@code snowy}
     * 都因此保住，不会把八层霜层压成一层
     *
     * @return 接不上的方块返 {@code null}
     */
    @Nullable
    public static BlockState toOverworldBlock(BlockState state) {
        BlockState mapped = Tables.TO_OVERWORLD.get(state.getBlock());
        return mapped == null ? null : copySharedProperties(state, mapped);
    }

    /** 一格方块换成晨昏线的对应物，同 {@link #toOverworldBlock} */
    @Nullable
    public static BlockState toChronoBlock(BlockState state) {
        BlockState mapped = Tables.TO_CHRONO.get(state.getBlock());
        return mapped == null ? null : copySharedProperties(state, mapped);
    }

    /**
     * 这次转换之后，易碎的苍穹要变回什么
     *
     * <p>它不是调色板里的一格，而是<b>流体反应的产物</b>：不稳定液体碰上水就变成它
     * （见 {@code world/FluidReactions}）。所以它没有「另一侧的对应物」，只能拆回两种原料之一——
     * 强效那瓶拆成水（主世界那一侧），反转那瓶拆成不稳定液体
     *
     * @return 不管哪一瓶，易碎的苍穹都会被换掉，所以这里不会返 {@code null}
     */
    public static BlockState fragileCanopyMelt(boolean reversed) {
        return reversed ? ModBlocks.PORTAL_FLUID.get().defaultBlockState() : Blocks.WATER.defaultBlockState();
    }

    /** 这一格是不是易碎的苍穹：{@link #fragileCanopyMelt} 只对它生效 */
    public static boolean isFragileCanopy(BlockState state) {
        return state.is(ModTerrainBlocks.FRAGILE_CANOPY.get());
    }

    /**
     * 群系换成主世界的对应物：晨昏线副本按同名换命名空间，蓝沙查小表，暮色森林的群系查移植表
     *
     * @return 表外或者这个存档里没有那个群系时返 {@code null}
     */
    @Nullable
    public static ResourceKey<Biome> toOverworldBiome(ResourceKey<Biome> key, Registry<Biome> registry) {
        ResourceLocation id = key.location();
        ResourceLocation mapped;
        if (id.getNamespace().equals(DawnDuskTerminal.MOD_ID)) {
            mapped = Tables.CHRONO_BIOMES.get(id);
            if (mapped == null) {
                mapped = ResourceLocation.withDefaultNamespace(id.getPath());
            }
        } else if (id.getNamespace().equals(TWILIGHT_NAMESPACE)) {
            mapped = Tables.TWILIGHT_BIOMES.get(id);
        } else {
            return null;
        }
        return resolve(mapped, registry);
    }

    /**
     * 群系换成晨昏线的对应物：主世界群系按同名换命名空间，沙漠换成蓝沙
     *
     * <p>沙漠对应蓝沙而不是别的：{@code docs/biomes.md} 写着 {@code desert} 的同名副本被改成了自建 ID
     * {@code blue_desert}。白地、极昼、极夜没有对应的原版格子，因此不在这一档里。
     * 暮色森林群系也不在（它们不是主世界的格子）
     */
    @Nullable
    public static ResourceKey<Biome> toChronoBiome(ResourceKey<Biome> key, Registry<Biome> registry) {
        ResourceLocation id = key.location();
        if (!id.getNamespace().equals(OVERWORLD_NAMESPACE)) {
            return null;
        }
        ResourceLocation mapped =
                id.getPath().equals("desert") ? DawnDuskTerminal.id("blue_desert") : DawnDuskTerminal.id(id.getPath());
        return resolve(mapped, registry);
    }

    /**
     * 活物换成主世界那一侧的对应物
     *
     * <p>暮色森林的数据图是双向的，这里只认「结果落在 {@code minecraft:}」的那些，于是方向被筛成
     * 「暮色森林的怪 → 原版的怪」；原版的怪查出来是暮色那一侧的结果，被同一道筛子挡掉
     */
    @Nullable
    public static EntityType<?> toOverworldEntity(EntityType<?> type) {
        return transform(type, OVERWORLD_NAMESPACE);
    }

    /** 活物换成暮色森林那一侧的对应物，筛子反过来装 */
    @Nullable
    public static EntityType<?> toTwilightEntity(EntityType<?> type) {
        return transform(type, TWILIGHT_NAMESPACE);
    }

    @Nullable
    private static EntityType<?> transform(EntityType<?> type, String namespace) {
        EntityTransformation transformation =
                BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type).getData(TFDataMaps.TRANSFORMATION_POWDER);
        if (transformation == null) {
            return null;
        }
        EntityType<?> result = transformation.result();
        ResourceLocation resultId = BuiltInRegistries.ENTITY_TYPE.getKey(result);
        if (result == type || !resultId.getNamespace().equals(namespace)) {
            return null;
        }
        return result;
    }

    @Nullable
    private static ResourceKey<Biome> resolve(@Nullable ResourceLocation mapped, Registry<Biome> registry) {
        if (mapped == null || !registry.containsKey(mapped)) {
            return null;
        }
        return ResourceKey.create(Registries.BIOME, mapped);
    }

    /**
     * 把 {@code source} 上那些与 {@code target} 同名的属性照抄过去
     *
     * <p>{@code Property} 不重写 equals，所以「同名但类型不同」的会查不到，正好跳过
     */
    private static BlockState copySharedProperties(BlockState source, BlockState target) {
        BlockState result = target;
        for (Property<?> property : target.getProperties()) {
            result = copyProperty(source, result, property);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(
            BlockState source, BlockState target, Property<T> property) {
        if (!source.hasProperty(property)) {
            return target;
        }
        return target.setValue(property, source.getValue(property));
    }

    /** 粒子与转换都把落点当成球心，这里给测试一个「这个位置在球内吗」的口径 */
    public static boolean insideBlast(BlockPos centre, BlockPos candidate) {
        return centre.distSqr(candidate) <= RADIUS * RADIUS;
    }
}
