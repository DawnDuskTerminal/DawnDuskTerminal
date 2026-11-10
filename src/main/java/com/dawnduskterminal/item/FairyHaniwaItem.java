package com.dawnduskterminal.item;

import com.dawnduskterminal.block.FairyDianthusBlock;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 仙灵埴轮：创造模式下对着大仙灵石竹的侧面右键，在那一侧挂一根向下的分支
 *
 * <p>这一件物品本身没有别的用途，右键只是给建造用的：分支的几何（拐角 + 下垂柱）与生成器
 * {[@code FairyDianthusFeature#placeDownward} 一致，所以手动挂出来的与自然长出来的长得一样。
 *
 * <p><b>只在创造模式生效</b>：生存模式右键一律 {@link InteractionResult#PASS}，既不消耗也不放。
 * 点的是主柱那一格（{@code part=stem} 或 {@code part=branch}），侧面朝外；这一侧已经有连接、
 * 或外面没地方挂，就当没点。
 *
 * <p>下垂柱的长度不是固定值：与生成器一样掷 1~3 格，下面放不下就按实际能放下的格数挂。
 */
public class FairyHaniwaItem extends Item {
    /** 下垂柱最多几格：与生成器的 1~3 同一个上限 */
    private static final int MAX_HANG = 3;

    public FairyHaniwaItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isCreative()) {
            return InteractionResult.PASS;
        }
        Direction face = context.getClickedFace();
        if (!face.getAxis().isHorizontal()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        BlockState state = level.getBlockState(clicked);
        Block plant = ModTerrainBlocks.FAIRY_DIANTHUS.get();
        if (!state.is(plant)) {
            return InteractionResult.PASS;
        }
        FairyDianthusBlock.Part part = state.getValue(FairyDianthusBlock.PART);
        if (part != FairyDianthusBlock.Part.STEM && part != FairyDianthusBlock.Part.BRANCH) {
            return InteractionResult.PASS;
        }
        if (state.getValue(FairyDianthusBlock.stub(face))) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // 与生成器同一个掷法：1~3 格，不是每次都给满
        int wanted = 1 + level.getRandom().nextInt(MAX_HANG);
        return FairyDianthusBlock.placeDownwardBranch(plant, level, clicked, face, wanted)
                ? InteractionResult.SUCCESS
                : InteractionResult.PASS;
    }
}
