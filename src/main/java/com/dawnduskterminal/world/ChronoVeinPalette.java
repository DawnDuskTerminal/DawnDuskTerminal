package com.dawnduskterminal.world;

import com.dawnduskterminal.registry.ModChronoOres;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * {@code OreVeinifier} 写死的矿脉填充物，重映射到晨昏线调色板
 *
 * <p>原版大矿脉不是特征，是代码生成的：{@code OreVeinifier.VeinType} 把方块状态烤进两个枚举常量——
 *
 * <pre>
 * COPPER(Blocks.COPPER_ORE, Blocks.RAW_COPPER_BLOCK, Blocks.GRANITE,  0,  50)
 * IRON  (Blocks.DEEPSLATE_IRON_ORE, Blocks.RAW_IRON_BLOCK, Blocks.TUFF, -60, -8)
 * </pre>
 *
 * <p>所以 {@code ore_veins_enabled: true} 时，数据包说什么都没用，维度照样长原版花岗岩、原版凝灰岩
 * 与原版矿石方块。这里只换方块，不动矿脉自己的生成概率与高度：花岗岩换成赭石，凝灰岩换成暮光石，
 * 矿石换成 {@link ModChronoOres} 的两档副本——原版石头一档的矿进苍穹石，深板岩一档的矿进紫苏岩。
 * 粗矿块（{@code RAW_COPPER_BLOCK} 与 {@code RAW_IRON_BLOCK}）原样保留
 *
 * <p>矿脉不是这六种矿的唯一来源：六种矿各自还有一条特征
 * （见 {@code data/dawnduskterminal/worldgen/placed_feature/ore_*.json}）。矿脉只是额外那一份，
 * 因此它的频率与高度带保持原版，不跟着特征表那套去改
 */
public final class ChronoVeinPalette {
    private ChronoVeinPalette() {}

    private static final class Holder {
        static final BlockState OCHRE = ModTerrainBlocks.OCHRE.get().defaultBlockState();
        static final BlockState TWILIGHT_STONE =
                ModTerrainBlocks.TWILIGHT_STONE.get().defaultBlockState();
        static final Map<Block, BlockState> ORES = orePalette();

        /**
         * 原版矿石方块到这里的两档副本
         *
         * <p>键按方块身份比：原版那一份枚举常量就是这二十四个引用，不会有同名的另一份
         */
        private static Map<Block, BlockState> orePalette() {
            Map<Block, BlockState> map = new IdentityHashMap<>();
            ore(map, Blocks.COPPER_ORE, ModChronoOres.CANOPY_STONE_COPPER_ORE.get());
            ore(map, Blocks.DEEPSLATE_COPPER_ORE, ModChronoOres.CHARNOCKITE_COPPER_ORE.get());
            ore(map, Blocks.IRON_ORE, ModChronoOres.CANOPY_STONE_IRON_ORE.get());
            ore(map, Blocks.DEEPSLATE_IRON_ORE, ModChronoOres.CHARNOCKITE_IRON_ORE.get());
            ore(map, Blocks.GOLD_ORE, ModChronoOres.CANOPY_STONE_GOLD_ORE.get());
            ore(map, Blocks.DEEPSLATE_GOLD_ORE, ModChronoOres.CHARNOCKITE_GOLD_ORE.get());
            ore(map, Blocks.REDSTONE_ORE, ModChronoOres.CANOPY_STONE_REDSTONE_ORE.get());
            ore(map, Blocks.DEEPSLATE_REDSTONE_ORE, ModChronoOres.CHARNOCKITE_REDSTONE_ORE.get());
            ore(map, Blocks.LAPIS_ORE, ModChronoOres.CANOPY_STONE_LAPIS_ORE.get());
            ore(map, Blocks.DEEPSLATE_LAPIS_ORE, ModChronoOres.CHARNOCKITE_LAPIS_ORE.get());
            ore(map, Blocks.DIAMOND_ORE, ModChronoOres.CANOPY_STONE_DIAMOND_ORE.get());
            ore(map, Blocks.DEEPSLATE_DIAMOND_ORE, ModChronoOres.CHARNOCKITE_DIAMOND_ORE.get());
            return map;
        }

        private static void ore(Map<Block, BlockState> map, Block vanilla, Block palette) {
            map.put(vanilla, palette.defaultBlockState());
        }
    }

    /**
     * @return 替换后的状态；本方块不是写死的矿脉填充物时原样返回。{@code null} 直接透传：区块方块规则
     *     用 null 表示「保留默认方块」，而这是最常见的情况
     */
    public static BlockState remap(BlockState state) {
        if (state == null) {
            return null;
        }
        // 只有十来种写死的填充物，直接按方块身份查表比每方块建一个包装便宜
        Block block = state.getBlock();
        if (block == Blocks.GRANITE) {
            return Holder.OCHRE;
        }
        if (block == Blocks.TUFF) {
            return Holder.TWILIGHT_STONE;
        }
        BlockState ore = Holder.ORES.get(block);
        return ore != null ? ore : state;
    }

    /**
     * 包住区块方块规则，让写死的矿脉填充物变成调色板方块
     *
     * <p>{@code BlockStateFiller#calculate} 是 {@code @Nullable}，用 {@code null} 表示「保留默认方
     * 块」，而这是绝大多数情况，null 必须直接透传。弄错的话几乎每个方块都会 NPE，区块永远生成不完，所以
     * 这段放在这个可测的方法里，而不是内联进 mixin
     */
    public static NoiseChunk.BlockStateFiller wrap(NoiseChunk.BlockStateFiller original) {
        return context -> {
            BlockState state = original.calculate(context);
            return state == null ? null : remap(state);
        };
    }
}
