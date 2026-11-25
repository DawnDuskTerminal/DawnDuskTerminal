package com.dawnduskterminal.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.state.BlockState;

public class RoutePortalBlock extends HalfTransparentBlock {
    public RoutePortalBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        PortalBlock.touch(state, level, pos, entity);
    }
}
