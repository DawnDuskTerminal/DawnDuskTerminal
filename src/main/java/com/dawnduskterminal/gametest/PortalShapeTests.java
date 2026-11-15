package com.dawnduskterminal.gametest;

import com.dawnduskterminal.portal.PortalBlock;
import com.dawnduskterminal.portal.PortalBuilder;
import com.dawnduskterminal.portal.PortalShape;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class PortalShapeTests {
    private static final int POOL_Y = 1;
    private static final int POOL_MIN = 3;
    private static final int POOL_MAX = 5;

    /** 2×2 水池的四邻接池边，共 8 格；四个斜角外角不在其中 */
    private static final List<BlockPos> SMALL_RING = List.of(
            new BlockPos(2, POOL_Y, 3),
            new BlockPos(2, POOL_Y, 4),
            new BlockPos(3, POOL_Y, 2),
            new BlockPos(4, POOL_Y, 2),
            new BlockPos(5, POOL_Y, 3),
            new BlockPos(5, POOL_Y, 4),
            new BlockPos(3, POOL_Y, 5),
            new BlockPos(4, POOL_Y, 5));

    private static void buildFloor(GameTestHelper helper, net.minecraft.world.level.block.Block floor) {
        for (int x = POOL_MIN - 1; x <= POOL_MAX + 1; x++) {
            for (int z = POOL_MIN - 1; z <= POOL_MAX + 1; z++) {
                helper.setBlock(new BlockPos(x, POOL_Y - 1, z), floor);
            }
        }
    }

    private static void buildPool(GameTestHelper helper) {
        for (int x = POOL_MIN; x <= POOL_MAX; x++) {
            for (int z = POOL_MIN; z <= POOL_MAX; z++) {
                helper.setBlock(new BlockPos(x, POOL_Y, z), Blocks.WATER);
            }
        }
    }

    private static void buildRim(
            GameTestHelper helper,
            net.minecraft.world.level.block.Block rim,
            net.minecraft.world.level.block.Block plant) {
        for (int x = POOL_MIN - 1; x <= POOL_MAX + 1; x++) {
            for (int z = POOL_MIN - 1; z <= POOL_MAX + 1; z++) {
                boolean edge = x == POOL_MIN - 1 || x == POOL_MAX + 1 || z == POOL_MIN - 1 || z == POOL_MAX + 1;
                if (!edge) {
                    continue;
                }
                BlockPos pos = new BlockPos(x, POOL_Y, z);
                helper.setBlock(pos, rim);
                if (plant != null) {
                    helper.setBlock(pos.above(), plant);
                }
            }
        }
    }

    /**
     * 2×2 水池：铺地板、灌水、把四邻接池边设为草地，植物只放在 planted 列出的位置
     *
     * 四个斜角外角整格留空，用于锁定与暮色森林一致的四邻接边界
     */
    private static void buildSmallPool(GameTestHelper helper, List<BlockPos> planted) {
        for (int x = POOL_MIN - 1; x <= POOL_MIN + 2; x++) {
            for (int z = POOL_MIN - 1; z <= POOL_MIN + 2; z++) {
                helper.setBlock(new BlockPos(x, POOL_Y - 1, z), ModTerrainBlocks.CANOPY_STONE.get());
            }
        }
        for (int x = POOL_MIN; x <= POOL_MIN + 1; x++) {
            for (int z = POOL_MIN; z <= POOL_MIN + 1; z++) {
                helper.setBlock(new BlockPos(x, POOL_Y, z), Blocks.WATER);
            }
        }
        for (BlockPos side : SMALL_RING) {
            helper.setBlock(side, ModTerrainBlocks.ROSY_GRASS.get());
            if (planted.contains(side)) {
                helper.setBlock(side.above(), Blocks.SHORT_GRASS);
            }
        }
    }

    @GameTest(template = "ddt_empty")
    public static void everyRimCellMustBePlanted(GameTestHelper helper) {
        buildSmallPool(helper, SMALL_RING.subList(0, 2));
        ServerLevel level = helper.getLevel();
        BlockPos seed = helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN));
        helper.assertTrue(
                PortalShape.find(level, seed).isEmpty(), "two plants on an eight cell rim must not open a portal");
        for (BlockPos side : SMALL_RING) {
            helper.setBlock(side.above(), Blocks.SHORT_GRASS);
        }
        helper.assertTrue(PortalShape.find(level, seed).isPresent(), "every rim cell planted must open a portal");
        for (BlockPos corner : List.of(
                new BlockPos(POOL_MIN - 1, POOL_Y + 1, POOL_MIN - 1),
                new BlockPos(POOL_MIN + 2, POOL_Y + 1, POOL_MIN - 1),
                new BlockPos(POOL_MIN - 1, POOL_Y + 1, POOL_MIN + 2),
                new BlockPos(POOL_MIN + 2, POOL_Y + 1, POOL_MIN + 2))) {
            helper.assertBlockPresent(Blocks.AIR, corner);
        }
        helper.assertTrue(
                PortalShape.find(level, seed).isPresent(),
                "bare diagonal corners must not be required, they are not four adjacent to the pool");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void removingAnySinglePlantFails(GameTestHelper helper) {
        buildSmallPool(helper, SMALL_RING);
        ServerLevel level = helper.getLevel();
        BlockPos seed = helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN));
        helper.assertTrue(PortalShape.find(level, seed).isPresent(), "the fully planted pool must be a portal");
        for (BlockPos side : SMALL_RING) {
            helper.setBlock(side.above(), Blocks.AIR);
            helper.assertTrue(
                    PortalShape.find(level, seed).isEmpty(),
                    "removing the plant above " + side + " must invalidate the shape");
            helper.setBlock(side.above(), Blocks.SHORT_GRASS);
        }
        helper.assertTrue(PortalShape.find(level, seed).isPresent(), "restored plants must restore the shape");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void onlyDecorationCountsAsPlant(GameTestHelper helper) {
        buildSmallPool(helper, SMALL_RING);
        ServerLevel level = helper.getLevel();
        BlockPos seed = helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN));
        BlockPos probe = SMALL_RING.get(0).above();
        for (BlockState impostor : List.of(
                Blocks.STONE.defaultBlockState(),
                ModTerrainBlocks.ROSY_GRASS.get().defaultBlockState())) {
            helper.setBlock(probe, impostor);
            helper.assertTrue(
                    PortalShape.find(level, seed).isEmpty(), impostor + " above the rim must not pass as a plant");
        }
        for (BlockState plant : List.of(
                Blocks.POPPY.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState(),
                Blocks.OAK_SAPLING.defaultBlockState())) {
            helper.setBlock(probe, plant);
            helper.assertTrue(PortalShape.find(level, seed).isPresent(), plant + " must pass as a plant");
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void ignitionWithoutPlantsKeepsItemAndPool(GameTestHelper helper) {
        buildSmallPool(helper, List.of());
        ServerLevel level = helper.getLevel();
        BlockPos seed = helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN));
        ItemEntity item = new ItemEntity(
                level,
                (double) seed.getX() + 0.5D,
                (double) seed.getY() + 0.5D,
                (double) seed.getZ() + 0.5D,
                new ItemStack(ModItems.ENCASED_DUST.get(), 2));
        PortalBlock.tryIgnite(level, seed, item);
        helper.assertTrue(item.getItem().getCount() == 2, "a rejected ignition must not consume the catalyst");
        helper.assertBlockPresent(Blocks.WATER, new BlockPos(POOL_MIN, POOL_Y, POOL_MIN));
        helper.assertBlockPresent(Blocks.WATER, new BlockPos(POOL_MIN + 1, POOL_Y, POOL_MIN + 1));
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void ignitionWithEveryPlantConsumesOneItem(GameTestHelper helper) {
        buildSmallPool(helper, SMALL_RING);
        ServerLevel level = helper.getLevel();
        BlockPos seed = helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN));
        ItemEntity item = new ItemEntity(
                level,
                (double) seed.getX() + 0.5D,
                (double) seed.getY() + 0.5D,
                (double) seed.getZ() + 0.5D,
                new ItemStack(ModItems.ENCASED_DUST.get(), 2));
        PortalBlock.tryIgnite(level, seed, item);
        helper.assertTrue(item.getItem().getCount() == 1, "a successful ignition consumes exactly one catalyst");
        for (int x = POOL_MIN; x <= POOL_MIN + 1; x++) {
            for (int z = POOL_MIN; z <= POOL_MIN + 1; z++) {
                helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), new BlockPos(x, POOL_Y, z));
            }
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void plantedPoolIsPortal(GameTestHelper helper) {
        buildFloor(helper, ModTerrainBlocks.CANOPY_STONE.get());
        buildPool(helper);
        buildRim(helper, ModTerrainBlocks.ROSY_GRASS.get(), Blocks.SHORT_GRASS);
        ServerLevel level = helper.getLevel();
        helper.assertTrue(
                PortalShape.find(level, helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN)))
                        .isPresent(),
                "planted pool should be recognised as a portal shape");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void barrenPoolIsNotPortal(GameTestHelper helper) {
        buildFloor(helper, ModTerrainBlocks.CANOPY_STONE.get());
        buildPool(helper);
        buildRim(helper, Blocks.GLASS, null);
        ServerLevel level = helper.getLevel();
        helper.assertTrue(
                PortalShape.find(level, helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN)))
                        .isEmpty(),
                "a glass rim must not count as a portal edge");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void poolWithoutPlantsIsNotPortal(GameTestHelper helper) {
        buildFloor(helper, ModTerrainBlocks.CANOPY_STONE.get());
        buildPool(helper);
        buildRim(helper, ModTerrainBlocks.ROSY_GRASS.get(), null);
        ServerLevel level = helper.getLevel();
        helper.assertTrue(
                PortalShape.find(level, helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN)))
                        .isEmpty(),
                "open air above the rim must not count as plants");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void loneWaterBlockIsNotPortal(GameTestHelper helper) {
        buildFloor(helper, ModTerrainBlocks.CANOPY_STONE.get());
        helper.setBlock(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN), Blocks.WATER);
        ServerLevel level = helper.getLevel();
        helper.assertTrue(
                PortalShape.find(level, helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN)))
                        .isEmpty(),
                "four water blocks are the minimum");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void buildFillsPoolWithPortalFluid(GameTestHelper helper) {
        buildFloor(helper, ModTerrainBlocks.CANOPY_STONE.get());
        buildPool(helper);
        buildRim(helper, ModTerrainBlocks.ROSY_GRASS.get(), Blocks.SHORT_GRASS);
        ServerLevel level = helper.getLevel();
        BlockPos seed = helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN));
        helper.assertTrue(PortalBuilder.build(level, seed), "build should succeed on a valid pool");
        for (int x = POOL_MIN; x <= POOL_MAX; x++) {
            for (int z = POOL_MIN; z <= POOL_MAX; z++) {
                helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), new BlockPos(x, POOL_Y, z));
            }
        }
        helper.succeed();
    }

    /**
     * 两层深的水池：判定取水体最上面那层
     *
     * 只用种子所在那层的话，填好的传送门流体会被正上方那层水反应成 fragile_canopy
     */
    @GameTest(template = "ddt_empty")
    public static void deepPoolIsBuiltOnTheSurfaceLayer(GameTestHelper helper) {
        for (int x = POOL_MIN - 1; x <= POOL_MAX + 1; x++) {
            for (int z = POOL_MIN - 1; z <= POOL_MAX + 1; z++) {
                helper.setBlock(new BlockPos(x, POOL_Y - 1, z), ModTerrainBlocks.CANOPY_STONE.get());
                boolean edge = x == POOL_MIN - 1 || x == POOL_MAX + 1 || z == POOL_MIN - 1 || z == POOL_MAX + 1;
                for (int y = POOL_Y; y <= POOL_Y + 1; y++) {
                    helper.setBlock(new BlockPos(x, y, z), edge ? ModTerrainBlocks.ROSY_GRASS.get() : Blocks.WATER);
                }
                if (edge) {
                    helper.setBlock(new BlockPos(x, POOL_Y + 2, z), Blocks.SHORT_GRASS);
                }
            }
        }
        ServerLevel level = helper.getLevel();
        BlockPos seed = helper.absolutePos(new BlockPos(POOL_MIN, POOL_Y, POOL_MIN));
        helper.assertTrue(PortalBuilder.build(level, seed), "a two deep pool should build a portal");
        for (int x = POOL_MIN; x <= POOL_MAX; x++) {
            for (int z = POOL_MIN; z <= POOL_MAX; z++) {
                helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), new BlockPos(x, POOL_Y + 1, z));
                helper.assertBlockPresent(Blocks.WATER, new BlockPos(x, POOL_Y, z));
            }
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void portalBlockSurvivesWithNeighbour(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), ModBlocks.PORTAL_FLUID.get());
        helper.setBlock(new BlockPos(3, 1, 2), ModBlocks.PORTAL_FLUID.get());
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(new BlockPos(2, 1, 2));
        level.getBlockState(abs).randomTick(level, abs, level.random);
        helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), new BlockPos(2, 1, 2));
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void isolatedPortalBlockSelfHeals(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), ModBlocks.PORTAL_FLUID.get());
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(new BlockPos(2, 1, 2));
        level.getBlockState(abs).randomTick(level, abs, level.random);
        helper.assertBlockNotPresent(ModBlocks.PORTAL_FLUID.get(), new BlockPos(2, 1, 2));
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void portalFluidIsCompleteWithTwoNeighbours(GameTestHelper helper) {
        helper.setBlock(new BlockPos(2, 1, 2), ModBlocks.PORTAL_FLUID.get());
        helper.setBlock(new BlockPos(3, 1, 2), ModBlocks.PORTAL_FLUID.get());
        helper.setBlock(new BlockPos(2, 1, 3), ModBlocks.PORTAL_FLUID.get());
        ServerLevel level = helper.getLevel();
        helper.assertTrue(
                PortalShape.isCompletePortalPart(level, helper.absolutePos(new BlockPos(2, 1, 2))),
                "the corner of an L has two neighbours so it counts as a complete part");
        helper.assertTrue(
                !PortalShape.isCompletePortalPart(level, helper.absolutePos(new BlockPos(3, 1, 2))),
                "a block with a single neighbour is not complete");
        helper.succeed();
    }
}
