package com.dawnduskterminal.gametest;

import com.dawnduskterminal.block.PortalWaterfallBlock;
import com.dawnduskterminal.fluid.PortalWaterfallSupply;
import com.dawnduskterminal.fluid.WaterfallFluid;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModFluids;
import com.dawnduskterminal.registry.ModScorchingTwilight;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class PortalWaterfallTests {
    @GameTest(template = "ddt_empty")
    public static void waterfallTrimsThenResumesRealFlowWhenObstructed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 90, 8));
        prepareColumn(level, anchor);
        helper.assertTrue(!placeAnchor(level, anchor), "a waterfall needs four falling blocks");

        BlockPos obstruction = anchor.below(PortalWaterfallBlock.MIN_LENGTH - 1);
        level.setBlock(obstruction, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        fillUpstream(level, anchor);
        helper.assertTrue(!placeAnchor(level, anchor), "a short air column must keep the real fluid");
        level.removeBlock(obstruction, false);

        helper.assertTrue(placeAnchor(level, anchor), "a long falling column must create an anchor");
        int length = level.getBlockState(anchor).getValue(PortalWaterfallBlock.LENGTH);
        helper.assertTrue(
                length >= PortalWaterfallBlock.MIN_LENGTH && length <= PortalWaterfallBlock.MAX_LENGTH,
                "anchor length must remain in its coordinate-dependent range");
        helper.assertTrue(level.getFluidState(anchor).isEmpty(), "the anchor itself must have no fluid state");
        level.removeBlock(anchor, false);
        helper.assertTrue(placeAnchor(level, anchor), "the same position must accept a replacement anchor");
        helper.assertTrue(
                level.getBlockState(anchor).getValue(PortalWaterfallBlock.LENGTH) == length,
                "the same coordinate must retain its length");

        BlockPos edge = anchor.below(PortalWaterfallBlock.MIN_LENGTH);
        level.setBlock(edge, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(
                    level.getBlockState(anchor).getValue(PortalWaterfallBlock.LENGTH)
                            == PortalWaterfallBlock.MIN_LENGTH,
                    "the obstructed segment must stop above the block");
            level.setBlock(obstruction, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        });
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(level.getBlockState(anchor).isAir(), "the too-short anchor must be removed");
            helper.assertTrue(level.getBlockState(obstruction).is(Blocks.STONE), "the waterfall must preserve terrain");
        });
        helper.succeedWhen(() -> helper.assertTrue(
                level.getFluidState(anchor).is(ModFluids.PORTAL_FLOWING.get()),
                "real flow must resume below the obstruction threshold"));
    }

    @GameTest(template = "ddt_empty")
    public static void waterfallDisappearsAfterUpstreamIsRemoved(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(7, 90, 7));
        prepareColumn(level, anchor);
        fillUpstream(level, anchor);
        helper.assertTrue(placeAnchor(level, anchor), "the waterfall must start");
        BlockPos source = anchor.above(PortalWaterfallBlock.FALLING_RUN + 1);
        level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(
                !PortalWaterfallSupply.isSupplied(level, anchor.above(), ModFluids.PORTAL_FLOWING.get()),
                "the four residual falling blocks must not count as supply");
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getBlockState(anchor).isAir(), "removing the source must remove the old anchor");
            helper.assertTrue(!placeAnchor(level, anchor), "the residual flow must not recreate the anchor");
        });
        helper.runAfterDelay(30, () -> {
            for (int dy = 0; dy <= PortalWaterfallBlock.FALLING_RUN; dy++) {
                helper.assertTrue(
                        level.getFluidState(anchor.above(dy)).isEmpty(), "the disconnected column must drain");
            }
            helper.succeed();
        });
    }

    @GameTest(template = "ddt_empty")
    public static void waterfallAcceptsASideInletAndHasNoOutline(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(6, 90, 6));
        prepareColumn(level, anchor);
        fillUpstream(level, anchor);
        BlockPos inlet = anchor.above(PortalWaterfallBlock.FALLING_RUN + 1);
        level.setBlock(
                inlet, ModFluids.PORTAL_FLOWING.get().getFlowing(7, false).createLegacyBlock(), Block.UPDATE_ALL);
        level.setBlock(inlet.east(), ModBlocks.PORTAL_FLUID.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(placeAnchor(level, anchor), "a stronger horizontal inlet must support the falling column");
        var state = level.getBlockState(anchor);
        helper.assertTrue(state.getShape(level, anchor).isEmpty(), "the waterfall must not intercept selection");
        helper.assertTrue(state.getCollisionShape(level, anchor).isEmpty(), "the waterfall must remain passable");
        level.setBlock(inlet.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(
                !PortalWaterfallSupply.isSupplied(level, anchor.above(), ModFluids.PORTAL_FLOWING.get()),
                "a residual horizontal inlet must not count as a source");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void scorchingWaterfallRequiresItsOwnSupply(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(6, 90, 6));
        prepareColumn(level, anchor);
        BlockPos source = anchor.above(PortalWaterfallBlock.FALLING_RUN + 1);
        var falling = ModScorchingTwilight.FLOWING.get().getFlowing(8, true).createLegacyBlock();
        for (int dy = 1; dy <= PortalWaterfallBlock.FALLING_RUN; dy++) {
            level.setBlock(anchor.above(dy), falling, Block.UPDATE_ALL);
        }
        level.setBlock(source, ModBlocks.PORTAL_FLUID.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(
                !PortalWaterfallBlock.tryPlace(level, anchor, level.getBlockState(anchor), WaterfallFluid.SCORCHING),
                "a portal source must not supply a scorching waterfall");

        level.setBlock(source, ModScorchingTwilight.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(
                PortalWaterfallBlock.tryPlace(level, anchor, level.getBlockState(anchor), WaterfallFluid.SCORCHING),
                "a scorching source must support the falling column");
        BlockState state = level.getBlockState(anchor);
        helper.assertTrue(
                state.getValue(PortalWaterfallBlock.FLUID) == WaterfallFluid.SCORCHING,
                "the anchor must retain the fluid variant");
        helper.assertTrue(
                !PortalWaterfallBlock.tryPlace(level, anchor, state, WaterfallFluid.PORTAL),
                "the portal variant must not reuse a scorching anchor");
        helper.assertTrue(state.getFluidState().isEmpty(), "the decorative segment must not add ticking fluid");
        helper.assertTrue(
                ModScorchingTwilight.FLOWING.get().getTickDelay(level)
                        > ModFluids.PORTAL_FLOWING.get().getTickDelay(level),
                "scorching twilight must retain its longer flow delay");

        level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.succeedWhen(() -> helper.assertTrue(
                level.getBlockState(anchor).isAir(), "the scorching anchor must be removed when its source is cut"));
    }

    private static boolean placeAnchor(ServerLevel level, BlockPos anchor) {
        return PortalWaterfallBlock.tryPlace(level, anchor, level.getBlockState(anchor), WaterfallFluid.PORTAL);
    }

    @GameTest(template = "ddt_empty")
    public static void shortWaterfallsReachTheBuildFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos portal = helper.absolutePos(new BlockPos(4, 0, 4)).atY(level.getMinBuildHeight());
        BlockPos scorching = portal.east(4).above(14);
        level.removeBlock(portal, false);
        fillUpstream(level, portal);
        helper.assertTrue(placeAnchor(level, portal), "the lowest cell must accept a one-block waterfall");
        for (int i = 0; i < 15; i++) {
            level.removeBlock(scorching.below(i), false);
        }
        var falling = ModScorchingTwilight.FLOWING.get().getFlowing(8, true).createLegacyBlock();
        for (int i = 1; i <= PortalWaterfallBlock.FALLING_RUN; i++) {
            level.setBlock(scorching.above(i), falling, Block.UPDATE_ALL);
        }
        level.setBlock(
                scorching.above(PortalWaterfallBlock.FALLING_RUN + 1),
                ModScorchingTwilight.BLOCK.get().defaultBlockState(),
                Block.UPDATE_ALL);
        helper.assertTrue(
                PortalWaterfallBlock.tryPlace(
                        level, scorching, level.getBlockState(scorching), WaterfallFluid.SCORCHING),
                "a scorching waterfall must use the remaining fifteen cells");
        helper.assertTrue(level.getBlockState(scorching).getValue(PortalWaterfallBlock.LENGTH) == 15, "wrong length");
        level.scheduleTick(portal, ModBlocks.PORTAL_WATERFALL.get(), 1);
        level.setBlock(scorching.below(14), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(
                    level.getBlockState(portal).getValue(PortalWaterfallBlock.LENGTH) == 1, "lost floor anchor");
            helper.assertTrue(
                    !level.getBlockState(scorching).is(ModBlocks.PORTAL_WATERFALL.get()),
                    "an obstruction at the floor must invalidate a short waterfall");
            helper.succeed();
        });
    }

    private static void prepareColumn(ServerLevel level, BlockPos anchor) {
        for (int dy = 0; dy < PortalWaterfallBlock.MAX_LENGTH; dy++) {
            level.setBlock(anchor.below(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static void fillUpstream(ServerLevel level, BlockPos anchor) {
        var falling = ModFluids.PORTAL_FLOWING.get().getFlowing(8, true).createLegacyBlock();
        level.setBlock(
                anchor.above(PortalWaterfallBlock.FALLING_RUN + 1),
                ModBlocks.PORTAL_FLUID.get().defaultBlockState(),
                Block.UPDATE_ALL);
        for (int dy = PortalWaterfallBlock.FALLING_RUN; dy > 0; dy--) {
            level.setBlock(anchor.above(dy), falling, Block.UPDATE_ALL);
        }
    }
}
