package com.dawnduskterminal.block;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 仙灵石竹两种方块的共用底座：只负责「立得住」与「脚下没了就断」。
 *
 * <p>两种方块都不是作物：没有 {@code randomTicks()}，也不实现 {@code BonemealableBlock}，
 * 所以它们不会生长、骨粉也没有用。
 *
 * <p><b>两种植物互不相认</b>：凑成柱形只有「脚下是同类」这一条，矮的不能立在高的上面，反过来也不行；
 * 脚下那格消失时整根随之断开（{@link #updateShape}）。
 *
 * <p>形状与碰撞箱都取「柱子 + 连接柱」的并集，因此长着分支的那一格瞄准框与碰撞箱都会跟着变宽。
 */
public abstract class AbstractFairyDianthusBlock extends Block {
    /** 分支连接的四个水平朝向；只做显示与放置判定，不会自动连邻居 */
    public static final Map<Direction, BooleanProperty> STUBS = Map.of(
            Direction.NORTH, BlockStateProperties.NORTH,
            Direction.EAST, BlockStateProperties.EAST,
            Direction.SOUTH, BlockStateProperties.SOUTH,
            Direction.WEST, BlockStateProperties.WEST);

    /** 模型里那根柱子：x/z 4~12、整格高 */
    protected static final VoxelShape STEM = Block.box(4.0D, 0.0D, 4.0D, 12.0D, 16.0D, 12.0D);

    /** 转向件的柱子：从格底抬 4 像素，底下一截是空的（与模型一致） */
    protected static final VoxelShape TURN_STEM = Block.box(4.0D, 4.0D, 4.0D, 12.0D, 16.0D, 12.0D);

    /** 向下拐角的柱子：转向件在竖直方向上的镜像，格底到连接柱顶端（12 像素），底下那根柱子接着往下挂 */
    protected static final VoxelShape DROP_STEM = Block.box(4.0D, 0.0D, 4.0D, 12.0D, 12.0D, 12.0D);

    /** 连接柱：8×8×4，纵向 4~12，贴着方块边界 */
    protected static final Map<Direction, VoxelShape> CONNECTORS = Map.of(
            Direction.NORTH, Block.box(4.0D, 4.0D, 0.0D, 12.0D, 12.0D, 4.0D),
            Direction.EAST, Block.box(12.0D, 4.0D, 4.0D, 16.0D, 12.0D, 12.0D),
            Direction.SOUTH, Block.box(4.0D, 4.0D, 12.0D, 12.0D, 12.0D, 16.0D),
            Direction.WEST, Block.box(0.0D, 4.0D, 4.0D, 4.0D, 12.0D, 12.0D));

    protected AbstractFairyDianthusBlock(Properties properties) {
        super(properties);
    }

    /** 这一格从哪些侧面接出了分支（矮的那一种恒为空集） */
    protected boolean isConnected(BlockState state, Direction direction) {
        return false;
    }

    /** 这一格自己的柱子形状；转向件是抬起来的那一根 */
    protected VoxelShape stemShape(BlockState state) {
        return STEM;
    }

    /** 柱子 + 已连接的连接柱 */
    protected VoxelShape shapeOf(BlockState state) {
        VoxelShape shape = this.stemShape(state);
        for (Direction direction : STUBS.keySet()) {
            if (this.isConnected(state, direction)) {
                shape = Shapes.or(shape, CONNECTORS.get(direction));
            }
        }
        return shape;
    }

    /** 沙类、土类，或者<b>同一种</b>仙灵石竹的下一格才能托住 */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(BlockTags.SAND) || below.is(BlockTags.DIRT) || below.getBlock() == this;
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbour,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighbourPos) {
        // 脚下没了，或者自己靠着的侧面没了
        if (direction == Direction.DOWN && !this.canSurvive(state, level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (this.isConnected(state, direction) && !this.isSupport(state, direction, neighbour)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    /** 连接柱那一侧的方块是不是还撑着自己；矮的那一种没有连接，恒为真 */
    protected boolean isSupport(BlockState state, Direction direction, BlockState neighbour) {
        return true;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.shapeOf(state);
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.shapeOf(state);
    }
}
