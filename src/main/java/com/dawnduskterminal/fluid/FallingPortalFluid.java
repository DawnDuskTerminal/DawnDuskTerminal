package com.dawnduskterminal.fluid;

import com.dawnduskterminal.block.PortalWaterfallBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/** 在晨昏线维度将符合条件的下落流体交给装饰瀑布。 */
public final class FallingPortalFluid extends BaseFlowingFluid.Flowing {
    public FallingPortalFluid(Properties properties) {
        super(properties);
    }

    @Override
    protected void spread(Level level, BlockPos pos, FluidState state) {
        if (WaterfallFluid.PORTAL.stopsDetachedFlow(level, pos, state)) {
            return;
        }
        super.spread(level, pos, state);
    }

    @Override
    protected void spreadTo(
            LevelAccessor level, BlockPos pos, BlockState replaced, Direction direction, FluidState fluid) {
        if (PortalWaterfallBlock.trySpread(level, pos, replaced, direction, WaterfallFluid.PORTAL)) {
            return;
        }
        super.spreadTo(level, pos, replaced, direction, fluid);
    }
}
