package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestTimeoutException;
import net.minecraft.gametest.framework.RetryOptions;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** 验证测试模板的真实边界，以及区块等待超时不会执行测试或阻塞后续批次。 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class GameTestInfrastructureTests {
    @GameTest(template = "ddt_empty")
    public static void templatesDeclareRealBounds(GameTestHelper helper) throws IOException {
        List<ResourceLocation> ids = List.of(
                DawnDuskTerminal.id("ddt_empty"),
                DawnDuskTerminal.id("ddt_large"),
                ResourceLocation.fromNamespaceAndPath("dawnduskterminal057", "ddt_empty"),
                ResourceLocation.fromNamespaceAndPath("dawnduskterminalexpo", "ddt_empty"),
                ResourceLocation.fromNamespaceAndPath("dawnduskterminalnative", "ddt_empty"));
        for (ResourceLocation id : ids) {
            String resource = "/data/" + id.getNamespace() + "/structure/" + id.getPath() + ".nbt";
            try (InputStream input = GameTestInfrastructureTests.class.getResourceAsStream(resource)) {
                helper.assertTrue(input != null, "Missing test template " + id);
                CompoundTag tag = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
                helper.assertTrue(tag.contains("size", Tag.TAG_LIST), id + " size must be an integer list");
                helper.assertTrue(
                        tag.getList("size", Tag.TAG_INT).size() == 3, id + " must declare three integer dimensions");
            }
            int side = id.getPath().equals("ddt_large") ? 32 : 16;
            Vec3i expected = new Vec3i(side, side, side);
            Vec3i actual = helper.getLevel()
                    .getStructureManager()
                    .get(id)
                    .orElseThrow()
                    .getSize();
            helper.assertTrue(expected.equals(actual), id + " must load as " + expected + ", got " + actual);
        }
        var bounds = helper.getBounds();
        helper.assertTrue(
                bounds.getXsize() == 16 && bounds.getYsize() == 16 && bounds.getZsize() == 16,
                "The running default test must cover 16 blocks on every axis, got " + bounds);
        helper.assertTrue(
                BlockPos.betweenClosedStream(
                                BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                                BlockPos.containing(bounds.maxX - 1, bounds.maxY - 1, bounds.maxZ - 1))
                        .allMatch(helper.getLevel()::isPositionEntityTicking),
                "Every position in the test bounds must be entity-ticking before execution");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void chunkWaitFailsWithoutStartingOrRepeatingNotifications(GameTestHelper helper)
            throws ReflectiveOperationException {
        // 此结构仅存在于内存中，不加载远端区块，也不更改正在运行的测试场地。
        BlockPos unloaded = new BlockPos(29_000_000, 0, 29_000_000);
        helper.assertFalse(helper.getLevel().isPositionEntityTicking(unloaded), "The probe chunk must not tick");
        int[] notifications = new int[2];
        GameTestInfo probe = probe(helper, unloaded, notifications);
        probe.tick(null);
        helper.assertFalse(probe.isDone(), "A new chunk wait must retain its grace period");
        setField(probe, "ddt$firstTickMillis", Util.getMillis() - 5001L);
        probe.tick(null);
        helper.assertTrue(probe.hasFailed(), "An expired chunk wait must fail instead of running the test");
        helper.assertTrue(
                probe.getError() instanceof GameTestTimeoutException, "Chunk readiness must report a timeout");
        helper.assertFalse(probe.hasStarted(), "The test body must not execute without entity-ticking chunks");
        helper.assertTrue(
                notifications[0] == 0 && notifications[1] == 1,
                "The runner listener must receive exactly one failure and no success");
        probe.tick(null);
        helper.assertTrue(notifications[1] == 1, "A completed timeout must not notify the runner again");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void readyChunksWinOverAnExpiredWait(GameTestHelper helper) throws ReflectiveOperationException {
        int[] notifications = new int[2];
        GameTestInfo probe = probe(helper, helper.absolutePos(BlockPos.ZERO).below(), notifications);
        setField(probe, "ddt$firstTickMillis", Util.getMillis() - 5001L);
        probe.tick(null);
        helper.assertTrue(probe.hasSucceeded(), "Ready chunks must execute before checking the wait deadline");
        helper.assertTrue(
                notifications[0] == 1 && notifications[1] == 0,
                "The runner listener must receive one success and no timeout");
        helper.succeed();
    }

    private static GameTestInfo probe(GameTestHelper helper, BlockPos structurePos, int[] notifications)
            throws ReflectiveOperationException {
        TestFunction function = new TestFunction(
                "chunk_wait_probe",
                "chunk_wait_probe",
                DawnDuskTerminal.id("ddt_empty").toString(),
                100,
                0,
                true,
                GameTestHelper::succeed);
        GameTestInfo probe = new GameTestInfo(function, Rotation.NONE, helper.getLevel(), RetryOptions.noRetries());
        StructureBlockEntity structure =
                new StructureBlockEntity(structurePos, Blocks.STRUCTURE_BLOCK.defaultBlockState());
        structure.setStructureSize(new Vec3i(16, 16, 16));
        setField(probe, "structureBlockEntity", structure);
        setField(probe, "placedStructure", true);
        probe.startExecution(0);
        // 仅安装计数监听器；null runner 不会进入真实调度器，也不会替换当前测试运行器。
        probe.addListener(new GameTestListener() {
            @Override
            public void testStructureLoaded(GameTestInfo testInfo) {}

            @Override
            public void testPassed(GameTestInfo test, GameTestRunner runner) {
                notifications[0]++;
            }

            @Override
            public void testFailed(GameTestInfo test, GameTestRunner runner) {
                notifications[1]++;
            }

            @Override
            public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) {}
        });
        return probe;
    }

    private static void setField(GameTestInfo target, String name, Object value) throws ReflectiveOperationException {
        Field field = GameTestInfo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private GameTestInfrastructureTests() {}
}
