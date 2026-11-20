package com.dawnduskterminal.world;

import com.dawnduskterminal.block.PortalWaterfallBlock;
import com.dawnduskterminal.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/** 服务端按列登记已加载的锚点，供方块变化时合并调度。 */
public final class PortalWaterfallColumns {
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<NavigableSet<Integer>>> ANCHORS = new WeakHashMap<>();

    private PortalWaterfallColumns() {}

    public static void track(ServerLevel level, BlockPos anchor) {
        ANCHORS.computeIfAbsent(level, ignored -> new Long2ObjectOpenHashMap<>())
                .computeIfAbsent(column(anchor), ignored -> new TreeSet<>())
                .add(anchor.getY());
    }

    public static void untrack(ServerLevel level, BlockPos anchor) {
        var columns = ANCHORS.get(level);
        if (columns == null) {
            return;
        }
        long column = column(anchor);
        var heights = columns.get(column);
        if (heights != null && heights.remove(anchor.getY()) && heights.isEmpty()) {
            columns.remove(column);
        }
        if (columns.isEmpty()) {
            ANCHORS.remove(level);
        }
    }

    public static void blockChanged(ServerLevel level, BlockPos changed, BlockState oldState, BlockState newState) {
        var columns = ANCHORS.get(level);
        if (columns == null) {
            return;
        }
        var heights = columns.get(column(changed));
        if (heights == null) {
            return;
        }

        boolean airChanged = oldState.isAir() != newState.isAir();
        boolean upstreamChanged =
                ChronoDimensionProbe.isChronoFluid(oldState) || ChronoDimensionProbe.isChronoFluid(newState);
        int y = changed.getY();
        if (upstreamChanged) {
            schedule(level, changed, heights.headSet(y, false));
        }
        if (airChanged) {
            schedule(level, changed, heights.subSet(y, false, y + PortalWaterfallBlock.MAX_LENGTH, false));
        }
    }

    private static void schedule(ServerLevel level, BlockPos changed, Iterable<Integer> heights) {
        for (int y : heights) {
            level.scheduleTick(changed.atY(y), ModBlocks.PORTAL_WATERFALL.get(), 1);
        }
    }

    private static long column(BlockPos pos) {
        return ChunkPos.asLong(pos.getX(), pos.getZ());
    }
}
