package com.dawnduskterminal.fluid;

import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/** 沿可供水的方向回溯至真实源头，横向只允许流量递增，向上每步升高一格。 */
public final class PortalWaterfallSupply {
    private PortalWaterfallSupply() {}

    public static boolean isSupplied(ServerLevel level, BlockPos start, FlowingFluid portal) {
        BlockPos.MutableBlockPos top = start.mutable();
        while (!level.isOutsideBuildHeight(top)) {
            FluidState fluid = level.getFluidState(top);
            if (!portal.isSame(fluid.getType())) {
                return false;
            }
            if (fluid.isSource()) {
                return true;
            }
            if (!fluid.getValue(FlowingFluid.FALLING)) {
                return searchFromInlet(level, top.immutable(), portal);
            }
            top.move(Direction.UP);
        }
        return false;
    }

    private static boolean searchFromInlet(ServerLevel level, BlockPos start, FlowingFluid portal) {
        var pending = new ArrayDeque<BlockPos>();
        var visited = new HashSet<BlockPos>();
        pending.add(start);
        while (!pending.isEmpty()) {
            BlockPos pos = pending.removeLast();
            while (!level.isOutsideBuildHeight(pos) && visited.add(pos)) {
                FluidState fluid = level.getFluidState(pos);
                if (!portal.isSame(fluid.getType())) {
                    break;
                }
                if (fluid.isSource()) {
                    return true;
                }
                if (!fluid.getValue(FlowingFluid.FALLING)) {
                    for (Direction direction : Direction.Plane.HORIZONTAL) {
                        BlockPos side = pos.relative(direction);
                        int chunkX = SectionPos.blockToSectionCoord(side.getX());
                        int chunkZ = SectionPos.blockToSectionCoord(side.getZ());
                        var chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                        if (chunk != null) {
                            FluidState neighbor = chunk.getFluidState(side);
                            if (portal.isSame(neighbor.getType()) && neighbor.getAmount() > fluid.getAmount()) {
                                pending.add(side);
                            }
                        }
                    }
                }
                pos = pos.above();
            }
        }
        return false;
    }
}
