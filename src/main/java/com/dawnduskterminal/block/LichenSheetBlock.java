package com.dawnduskterminal.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.GlowLichenBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class LichenSheetBlock extends GlowLichenBlock {
    public LichenSheetBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return !context.getItemInHand().is(asItem())
                || Direction.stream().anyMatch(direction -> !hasFace(state, direction));
    }
}
