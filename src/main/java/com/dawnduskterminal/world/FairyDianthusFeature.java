package com.dawnduskterminal.world;

import com.dawnduskterminal.block.FairyDianthusBlock;
import com.dawnduskterminal.block.FairyDianthusSmallBlock;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * 仙灵石竹的一簇：先撒一片矮的，再在<b>同一片里</b>长出高的
 *
 * <p>两种植物「分布在一起」是靠几何保证的：矮的那片与高的那几根都从同一个圆心、同一个半径里取坐标，
 * 所以高仙灵石竹的每一段分布都落在那片矮的之内，反过来不成立。两个数量分开配，谁多谁少只影响疏密。
 *
 * <p>几何（与 {@code Downloads/example.json} 的示例一致）：
 *
 * <ul>
 *   <li>矮的：1~3 格，一根柱子</li>
 *   <li>高的：主柱 4~12 格；中下部伸出 0~2 根分支柱，分支柱只与主柱相邻（水平距离正好 1 格），
 *       顶端低于主柱顶端</li>
 *   <li>分支柱的底格是「转向件」：朝回主柱的那一面带连接；主柱对应那格也带一个朝外的连接，
 *       两段连接柱在格子边界上对齐，于是分支从主柱「拐」上来</li>
 * </ul>
 *
 * <p>一簇里的每根柱子要挑各列自己的地表高度：维度的地表高低不平，用 {@code WORLD_SURFACE_WG}
 * 逐列取比拿 {@code context.origin()} 那一格更准。
 */
public class FairyDianthusFeature extends Feature<FairyDianthusFeature.Configuration> {
    public FairyDianthusFeature() {
        super(Configuration.CODEC);
    }

    /** 一簇的参数：半径（格）与两种植物的数量 */
    public record Configuration(int radius, int smallCount, int largeCount) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.intRange(1, 16).fieldOf("radius").forGetter(Configuration::radius),
                        Codec.intRange(0, 64).fieldOf("small_count").forGetter(Configuration::smallCount),
                        Codec.intRange(0, 16).fieldOf("large_count").forGetter(Configuration::largeCount))
                .apply(instance, Configuration::new));
    }

    /** 主柱高度的下限与上限 */
    private static final int MAIN_MIN_HEIGHT = 4;

    private static final int MAIN_MAX_HEIGHT = 12;

    /** 只抽到一根分支时，在那个分支点的对向再挂一根向下的分支的概率 */
    public static final float DOWNWARD_CHANCE = 0.05F;

    /** 向下分支的长度：拐角下面 1~3 格下垂柱 */
    private static final int DOWN_MIN_HEIGHT = 1;

    private static final int DOWN_MAX_HEIGHT = 3;

    @Override
    public boolean place(FeaturePlaceContext<Configuration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        RandomSource random = context.random();
        Configuration config = context.config();
        int radius = config.radius();
        boolean placed = false;

        for (int i = 0; i < config.smallCount(); i++) {
            placed |= placeSmall(level, random, patchColumn(origin, random, radius));
        }
        // 高的这根与矮的那片共用同一个圆心与半径，所以它的分布必然落在矮的那片之内
        for (int i = 0; i < config.largeCount(); i++) {
            placed |= placeLarge(level, random, patchColumn(origin, random, radius));
        }
        return placed;
    }

    private static BlockPos patchColumn(BlockPos origin, RandomSource random, int radius) {
        int x = origin.getX() + random.nextInt(radius * 2 + 1) - radius;
        int z = origin.getZ() + random.nextInt(radius * 2 + 1) - radius;
        return new BlockPos(x, 0, z);
    }

    /** 一根 1~3 格的矮仙灵石竹；脚下不是沙土或空间不够就放弃 */
    private boolean placeSmall(WorldGenLevel level, RandomSource random, BlockPos column) {
        BlockPos base = onSurface(level, column);
        if (base == null) {
            return false;
        }
        int height = 1 + random.nextInt(3);
        if (!hasRoom(level, base, height)) {
            return false;
        }
        // 整根柱子共用一个偏移，观感上才是一根直柱
        int offset = ModTerrainBlocks.FAIRY_DIANTHUS_SMALL.get().offsetFor(base);
        BlockState state = ModTerrainBlocks.FAIRY_DIANTHUS_SMALL
                .get()
                .defaultBlockState()
                .setValue(FairyDianthusSmallBlock.OFFSET, offset);
        for (int i = 0; i < height; i++) {
            level.setBlock(base.above(i), state, Block.UPDATE_CLIENTS);
        }
        return true;
    }

    /** 一根高的仙灵石竹：主柱 + 0~2 根分支柱 */
    private boolean placeLarge(WorldGenLevel level, RandomSource random, BlockPos column) {
        BlockPos base = onSurface(level, column);
        if (base == null) {
            return false;
        }
        int mainHeight = MAIN_MIN_HEIGHT + random.nextInt(MAIN_MAX_HEIGHT - MAIN_MIN_HEIGHT + 1);
        if (!hasRoom(level, base, mainHeight)) {
            return false;
        }

        BlockState plain = ModTerrainBlocks.FAIRY_DIANTHUS.get().defaultBlockState();
        for (int i = 0; i < mainHeight; i++) {
            level.setBlock(base.above(i), plain, Block.UPDATE_CLIENTS);
        }

        int branches = random.nextInt(3);
        int placedBranches = 0;
        int lastY = -1;
        Direction lastDirection = null;
        for (int branch = 0; branch < branches; branch++) {
            // 中下部：y 只取主柱下半段（1 ~ mainHeight/2），分支柱从下半截伸出去
            int highest = Math.max(1, mainHeight / 2);
            int y = 1 + random.nextInt(highest);
            Direction direction = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            BlockPos branchBase = base.above(y).relative(direction);
            // 分支柱可以高 1 ~ (mainHeight-1-y) 格：1 格也算一根，顶端始终低于主柱顶端
            int tallest = mainHeight - 1 - y;
            if (tallest < 1) {
                continue;
            }
            int branchHeight = 1 + random.nextInt(tallest);
            if (!hasRoom(level, branchBase, branchHeight)) {
                continue;
            }

            // 同一格允许分出多根分支：在**当前状态**上再加一个连接，角色同时换成 Part.BRANCH
            level.setBlock(
                    base.above(y),
                    FairyDianthusBlock.withStub(level.getBlockState(base.above(y)), direction),
                    Block.UPDATE_CLIENTS);
            level.setBlock(
                    branchBase,
                    FairyDianthusBlock.turnBase(ModTerrainBlocks.FAIRY_DIANTHUS.get(), direction.getOpposite()),
                    Block.UPDATE_CLIENTS);
            for (int i = 1; i < branchHeight; i++) {
                level.setBlock(branchBase.above(i), plain, Block.UPDATE_CLIENTS);
            }
            placedBranches++;
            lastY = y;
            lastDirection = direction;
        }
        // 只抽到一根分支：有 5% 的概率在同一个分支点的对向再挂一根向下的
        if (placedBranches == 1 && random.nextFloat() < DOWNWARD_CHANCE) {
            placeDownward(level, random, base, lastY, lastDirection);
        }
        return true;
    }

    /**
     * 在分支点的对向挂一根向下的分支
     *
     * <p>几何：主柱那一格补一个朝外的连接，相邻那格摆一个朝回主柱的<b>向下拐角</b>
     * （柱子只占下半截），再从拐角往下接 1~3 格下垂柱。往下不是空气的地方挂不起来，
     * 这时连拐角也不摆——免得留下孤零零一个拐角
     *
     * @param base 主柱的底格
     * @param y 分支点相对底格的高度
     * @param branchDirection 已有那根向上分支的方向；向下的这根挂在它的对向
     * @return 有没有摆成
     */
    public static boolean placeDownward(
            WorldGenLevel level, RandomSource random, BlockPos base, int y, Direction branchDirection) {
        BlockPos joint = base.above(y);
        Direction direction = branchDirection.getOpposite();
        BlockPos dropPos = joint.relative(direction);
        if (!level.getBlockState(dropPos).canBeReplaced()) {
            return false;
        }
        int wanted = DOWN_MIN_HEIGHT + random.nextInt(DOWN_MAX_HEIGHT - DOWN_MIN_HEIGHT + 1);
        int fitted = 0;
        while (fitted < wanted && level.getBlockState(dropPos.below(fitted + 1)).canBeReplaced()) {
            fitted++;
        }
        if (fitted < DOWN_MIN_HEIGHT) {
            return false;
        }
        level.setBlock(joint, FairyDianthusBlock.withStub(level.getBlockState(joint), direction), Block.UPDATE_CLIENTS);
        level.setBlock(
                dropPos,
                FairyDianthusBlock.dropBase(ModTerrainBlocks.FAIRY_DIANTHUS.get(), direction.getOpposite()),
                Block.UPDATE_CLIENTS);
        for (int i = 1; i <= fitted; i++) {
            level.setBlock(
                    dropPos.below(i),
                    FairyDianthusBlock.hang(ModTerrainBlocks.FAIRY_DIANTHUS.get()),
                    Block.UPDATE_CLIENTS);
        }
        return true;
    }

    /** 这一列的地表格（站立的那一格），脚下是沙类或土类才返回非 null */
    private static BlockPos onSurface(WorldGenLevel level, BlockPos column) {
        int x = column.getX();
        int z = column.getZ();
        // 用 MOTION_BLOCKING 而不是 WORLD_SURFACE_WG：两者在世界生成时都准，但只有前者会随方块更新，
        // 所以同一份代码在游戏测试里对着刚铺好的沙台也能取到正确高度
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos base = new BlockPos(x, y, z);
        BlockState soil = level.getBlockState(base.below());
        if (!soil.is(BlockTags.SAND) && !soil.is(BlockTags.DIRT)) {
            return null;
        }
        return base;
    }

    /** 从 base 往上 height 格都要是空气或可替换（不碰水、不碰别的方块） */
    private static boolean hasRoom(WorldGenLevel level, BlockPos base, int height) {
        for (int i = 0; i < height; i++) {
            BlockState state = level.getBlockState(base.above(i));
            if (!state.canBeReplaced()) {
                return false;
            }
        }
        return true;
    }
}
