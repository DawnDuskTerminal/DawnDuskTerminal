package com.dawnduskterminal.world;

import com.dawnduskterminal.block.AbstractSunflowerBlock;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * 向日葵的一簇：在半径 7 的方形范围内撒 40 次，每次尝试一株 1~3 格的花
 *
 * <p>原版 {@code patch_sunflower} 为 {@code random_patch} 的 {@code tries 96 / xz_spread 7}。
 *
 * <p>高度图取 {@code MOTION_BLOCKING}。
 *
 * <p>每一段用一次 {@code setBlock} 写入完整状态（段号与是否顶段一次给全），
 * 以免中途出现段号或顶段标志错误的中间状态。
 */
public class SunflowerFeature extends Feature<SunflowerFeature.Configuration> {
    /** 顶段长成鬼花而不是向日葵的概率 */
    public static final float GHOFLOWER_CHANCE = 0.03F;

    /** 一簇的参数：半径（格）与株数 */
    public record Configuration(int radius, int count) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.intRange(1, 16).fieldOf("radius").forGetter(Configuration::radius),
                        Codec.intRange(0, 256).fieldOf("count").forGetter(Configuration::count))
                .apply(instance, Configuration::new));
    }

    public SunflowerFeature() {
        super(Configuration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<Configuration> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        BlockPos origin = context.origin();
        Configuration config = context.config();
        int diameter = config.radius() * 2 + 1;
        boolean placed = false;
        for (int index = 0; index < config.count(); index++) {
            int x = origin.getX() + random.nextInt(diameter) - config.radius();
            int z = origin.getZ() + random.nextInt(diameter) - config.radius();
            placed |= placeOne(level, random, x, z);
        }
        return placed;
    }

    /** 一株：脚下是土类，上面 1~3 格都能替换 */
    private static boolean placeOne(WorldGenLevel level, RandomSource random, int x, int z) {
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos base = new BlockPos(x, surface, z);
        BlockState soil = level.getBlockState(base.below());
        if (!soil.is(BlockTags.DIRT)) {
            return false;
        }
        int height = 1 + random.nextInt(AbstractSunflowerBlock.MAX_HEIGHT);
        // 晨昏线的哪一侧决定这一株是向日葵还是月葵；鬼花只可能出现在顶段
        boolean night = ChronoSun.isNight(ChronoLineState.serverLine(), base.getX() + 0.5D, base.getZ() + 0.5D);
        Block stemBlock = night ? ModTerrainBlocks.MOONFLOWER_STEM.get() : ModTerrainBlocks.SUNFLOWER_STEM.get();
        Block flowerBlock = night ? ModTerrainBlocks.MOONFLOWER.get() : ModTerrainBlocks.SUNFLOWER.get();
        Block ghostBlock = night ? ModTerrainBlocks.LUNAR_GHOFLOWER.get() : ModTerrainBlocks.GHOFLOWER.get();
        Block topBlock = random.nextFloat() < GHOFLOWER_CHANCE ? ghostBlock : flowerBlock;
        for (int index = 0; index < height; index++) {
            if (!level.getBlockState(base.above(index)).canBeReplaced()) {
                return false;
            }
        }
        for (int index = 0; index < height; index++) {
            Block block = index == height - 1 ? topBlock : stemBlock;
            level.setBlock(
                    base.above(index),
                    block.defaultBlockState()
                            .setValue(AbstractSunflowerBlock.SEGMENT, index)
                            .setValue(AbstractSunflowerBlock.HEAD, index == height - 1),
                    Block.UPDATE_CLIENTS);
        }
        return true;
    }
}
