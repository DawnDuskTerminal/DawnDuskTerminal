package com.dawnduskterminal.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 矮的那一种仙灵石竹：1~3 格高的一根柱子，每格都是这个方块
 *
 * <p>柱子长短完全由生成或玩家摆放决定（{@link com.dawnduskterminal.world.FairyDianthusFeature}
 * 放 1~3 格），方块本身不会长高。
 *
 * <p>{@link #OFFSET} 是「这根柱子偏哪一格」：四份模型各自把柱子挪 1 像素，值相同的一列才会连成一根直柱。
 * 整列共用一个值——往上接一格时从下面那格继承（{@link #getStateForPlacement}），生成器则一次性给整根柱子
 * 定一个值。取 0 时是原位，也是物品栏里用的那一份。
 *
 * <p>偏移是<b>坐标的纯函数</b>，不带随机源：客户端预测与服务端结算算出同一个值，所以放下之后不会先显示
 * 原位再跳一下；同一格永远同一个值，重进存档也一样。
 */
public class FairyDianthusSmallBlock extends AbstractFairyDianthusBlock {
    /** 偏移档位，对应 models/block/fairy_dianthus_small{,_1,_2,_3} */
    public static final IntegerProperty OFFSET = IntegerProperty.create("offset", 0, 3);

    /** 柱子的偏移档数 */
    public static final int OFFSETS = 4;

    public FairyDianthusSmallBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(OFFSET, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OFFSET);
    }

    /** 往上接的那一格沿用下面那一格的偏移；开新的一根时按坐标定一个 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        BlockState below = context.getLevel().getBlockState(pos.below());
        if (below.is(this)) {
            return this.defaultBlockState().setValue(OFFSET, below.getValue(OFFSET));
        }
        return this.defaultBlockState().setValue(OFFSET, offsetFor(pos));
    }

    /**
     * 偏移只取决于坐标
     *
     * <p>同一个坐标永远得到同一个值，客户端与服务端算出来一样；相邻的柱子因为坐标不同自然错开，
     * 不需要随机源
     */
    public static int offsetFor(BlockPos pos) {
        long hash = pos.getX() * 0x9E3779B97F4A7C15L ^ pos.getZ() * 0xC2B2AE3D27D4EB4FL;
        hash ^= hash >>> 33;
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= hash >>> 33;
        return (int) Math.floorMod(hash, OFFSETS);
    }

    /** 矮的那一种没有碰撞，可以走过去 */
    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
