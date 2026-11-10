package com.dawnduskterminal.item;

import com.dawnduskterminal.block.FairyDianthusBlock;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 仙灵石竹的物品：普通放置不做任何连接，只有点在某一格的侧面时才接出一根分支
 *
 * <p>原版放置走 {@code BlockItem#useOn}，把新方块放在点击面外那一格。这里拦下「点在高仙灵石竹的侧面」
 * 这一种，改成两件事：
 *
 * <ol>
 *   <li>被点的那一格打上朝该侧面的连接（主柱上多一个连接柱）</li>
 *   <li>相邻那一格摆一个朝回主柱的转向件，也就是分支柱的底</li>
 * </ol>
 *
 * 分支柱因此天然与主柱相邻，不额外水平延伸；其余情形原样交给 {@code super}，所以往顶面放、往地上放
 * 都不会连出分支（{@link FairyDianthusBlock} 没有自动连接逻辑）。
 */
public class FairyDianthusItem extends BlockItem {
    public FairyDianthusItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        Direction face = context.getClickedFace();
        BlockState clickedState = level.getBlockState(clicked);
        if (!clickedState.is(this.getBlock()) || !face.getAxis().isHorizontal()) {
            return super.useOn(context);
        }
        // 转向件只有一个朝向：再点它一次就按普通放置走，否则 blockstate 没有对应的组合
        if (FairyDianthusBlock.isTurn(clickedState)) {
            return super.useOn(context);
        }

        BlockPos branchPos = clicked.relative(face);
        BlockState branchState = level.getBlockState(branchPos);
        BlockPlaceContext placeContext = new BlockPlaceContext(context);
        if (!branchState.canBeReplaced(placeContext)) {
            return InteractionResult.FAIL;
        }

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        level.setBlock(clicked, FairyDianthusBlock.withStub(clickedState, face), Block.UPDATE_ALL);
        level.setBlock(branchPos, FairyDianthusBlock.turnBase(this.getBlock(), face.getOpposite()), Block.UPDATE_ALL);

        if (context.getPlayer() != null) {
            ItemStack stack = context.getItemInHand();
            if (!context.getPlayer().getAbilities().instabuild) {
                stack.shrink(1);
            }
            context.getPlayer().swing(context.getHand());
        }
        return InteractionResult.SUCCESS;
    }

    /** 备用查询：这个物品对应的方块是 {@code fairy_dianthus} */
    public Block plantsBlock() {
        return ModTerrainBlocks.FAIRY_DIANTHUS.get();
    }
}
