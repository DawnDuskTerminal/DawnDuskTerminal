package com.dawnduskterminal.portal;

import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import twilightforest.init.TFBlocks;

public final class TwilightPoolPortalBlock extends RoutePortalBlock {
    public TwilightPoolPortalBlock(Properties properties) {
        super(properties);
    }

    static void animatePool(Level level, BlockPos pos, RandomSource random) {
        var reference = TFBlocks.TWILIGHT_PORTAL.get();
        reference.animateTick(reference.defaultBlockState(), level, pos, random);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        animatePool(level, pos, random);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction side,
            BlockState neighbor,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        if (side != Direction.UP) level.scheduleTick(pos, this, 1);
        return state;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!isFramed(level, pos)) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        tick(state, level, pos, random);
    }

    static boolean isFramed(BlockGetter level, BlockPos start) {
        return isFramed(level, start, PortalRoute.TWILIGHT.block());
    }

    static boolean isFramed(BlockGetter level, BlockPos start, Block portal) {
        var pending = new ArrayDeque<BlockPos>();
        var visited = new HashSet<BlockPos>();
        pending.add(start);
        visited.add(start);
        while (!pending.isEmpty()) {
            BlockPos pos = pending.remove();
            if (!PortalShape.hasPoolFloor(level, pos)) return false;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos side = pos.relative(direction);
                BlockState neighbor = level.getBlockState(side);
                if (neighbor.is(portal)) {
                    if (visited.add(side)) {
                        if (visited.size() > PortalShape.MAX_WATER) return false;
                        pending.add(side);
                    }
                } else if (!PortalShape.isPlantable(neighbor)) {
                    return false;
                }
            }
        }
        return visited.size() >= PortalShape.MIN_WATER;
    }
}
