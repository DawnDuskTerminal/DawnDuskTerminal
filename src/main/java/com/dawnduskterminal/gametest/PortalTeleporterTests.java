package com.dawnduskterminal.gametest;

import com.dawnduskterminal.portal.PortalState;
import com.dawnduskterminal.portal.PortalTeleporter;
import com.dawnduskterminal.registry.ModAttachments;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.world.ChronoLineState;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class PortalTeleporterTests {
    private static final BlockPos CURRENT = new BlockPos(8192, 64, 8192);
    private static final BlockPos RETURN = new BlockPos(-33, 64, -49);

    private PortalTeleporterTests() {}

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    public static void recordedReturnDoesNotLoadDiscardedRegion(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            TestPlayer player = returningPlayer(chrono);
            prepareLanding(overworld, RETURN);
            helper.assertTrue(
                    overworld.getChunkSource().getChunkNow(CURRENT.getX() >> 4, CURRENT.getZ() >> 4) == null,
                    "The discarded region must initially be unloaded");
            PortalTeleporter.teleportEntity(player);
            assertLanding(helper, player, overworld, RETURN);
            helper.assertTrue(
                    overworld.getChunkSource().getChunkNow(CURRENT.getX() >> 4, CURRENT.getZ() >> 4) == null,
                    "A recorded return must not load the discarded region");
        });
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    public static void recordedReturnDoesNotBuildDiscardedPlatform(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            TestPlayer player = returningPlayer(chrono);
            LevelChunk discarded = overworld.getChunk(CURRENT.getX() >> 4, CURRENT.getZ() >> 4);
            // 平坦测试世界的自然地表低于搜索下限，因此舍弃区域的搜索必然进入平台回退路径。
            helper.assertTrue(
                    overworld.getChunkSource().getGenerator() instanceof FlatLevelSource,
                    "The discarded region must use the flat test generator");
            helper.assertTrue(
                    overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CURRENT.getX(), CURRENT.getZ())
                            < PortalTeleporter.LANDING_MIN_Y,
                    "The discarded column must not provide a natural landing");
            BlockState before = discarded.getBlockState(CURRENT.below());
            prepareLanding(overworld, RETURN);
            PortalTeleporter.teleportEntity(player);
            helper.assertTrue(
                    discarded.getBlockState(CURRENT.below()).equals(before),
                    "The discarded region must not receive a fallback platform");
            assertLanding(helper, player, overworld, RETURN);
        });
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    public static void missingReturnUsesCurrentCoordinates(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            TestPlayer player = new TestPlayer(chrono);
            player.setPos(CURRENT.getX() + 0.25D, CURRENT.getY(), CURRENT.getZ() + 0.75D);
            prepareLanding(overworld, CURRENT);
            PortalTeleporter.teleportEntity(player);
            assertLanding(helper, player, overworld, CURRENT);
            helper.assertTrue(
                    player.getData(ModAttachments.PORTAL_STATE).equals(PortalState.empty()),
                    "Returning without a record must not create one");
        });
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    public static void mismatchedReturnDimensionUsesCurrentCoordinates(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            TestPlayer player = returningPlayer(chrono);
            PortalState state = new PortalState(Level.NETHER.location().toString(), -32.25D, 64.75D, -48.25D);
            player.setData(ModAttachments.PORTAL_STATE, state);
            prepareLanding(overworld, CURRENT);
            PortalTeleporter.teleportEntity(player);
            assertLanding(helper, player, overworld, CURRENT);
            helper.assertTrue(
                    player.getData(ModAttachments.PORTAL_STATE).equals(state),
                    "A dimension mismatch must preserve the existing return record");
        });
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    public static void enteringRemembersOriginAndBuildsArrivalPortal(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            TestPlayer player = new TestPlayer(overworld);
            Vec3 origin = new Vec3(CURRENT.getX() + 0.25D, CURRENT.getY() + 0.75D, CURRENT.getZ() + 0.75D);
            player.setPos(origin);
            player.setData(
                    ModAttachments.PORTAL_STATE,
                    new PortalState(
                            Level.OVERWORLD.location().toString(), RETURN.getX(), RETURN.getY(), RETURN.getZ()));
            prepareLanding(chrono, CURRENT);
            PortalTeleporter.teleportEntity(player);
            helper.assertTrue(
                    player.teleports == 1 && player.destination == chrono,
                    "Entering must teleport once to the chrono dimension");
            helper.assertTrue(
                    player.getData(ModAttachments.PORTAL_STATE)
                            .equals(new PortalState(
                                    Level.OVERWORLD.location().toString(), origin.x, origin.y, origin.z)),
                    "Entering must remember the original dimension and exact coordinates");
            helper.assertTrue(
                    chrono.queriedColumns.contains(ChunkPos.asLong(CURRENT.getX(), CURRENT.getZ()))
                            && !chrono.queriedColumns.contains(ChunkPos.asLong(RETURN.getX(), RETURN.getZ())),
                    "Entering must search at the current coordinates, not at an older return record");
            BlockPos pool = BlockPos.containing(player.landing).offset(2, -1, -1);
            helper.assertTrue(
                    chrono.getBlockState(pool).is(ModBlocks.PORTAL_FLUID.get()),
                    "Entering must build the arrival portal next to the landing");
        });
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    public static void nonPlayerReturnsAtCurrentCoordinates(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            ArmorStand entity = new ArmorStand(chrono, CURRENT.getX() + 0.25D, CURRENT.getY(), CURRENT.getZ() + 0.75D);
            prepareLanding(overworld, CURRENT);
            try {
                PortalTeleporter.teleportEntity(entity);
                var arrived = overworld.getEntity(entity.getUUID());
                helper.assertTrue(
                        arrived != null && arrived.position().equals(Vec3.atBottomCenterOf(CURRENT)),
                        "A non-player must return to the overworld at its current coordinates");
                helper.assertTrue(
                        overworld.heightQueries == 1 && overworld.blockWrites == 0,
                        "A non-player return must search once without building an arrival portal");
            } finally {
                var arrived = overworld.getEntity(entity.getUUID());
                if (arrived != null) {
                    arrived.discard();
                }
                entity.discard();
            }
        });
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    public static void managementCommandKeepsDirectLandingContract(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            TestPlayer player = returningPlayer(overworld);
            var line = ChronoLineState.serverLine();
            BlockPos landing = BlockPos.containing(line.originX(), 64, line.originZ());
            prepareLanding(chrono, landing);
            PortalState before = player.getData(ModAttachments.PORTAL_STATE);
            try {
                int result = overworld
                        .getServer()
                        .getCommands()
                        .getDispatcher()
                        .execute("ddt tp 0", player.createCommandSourceStack().withPermission(2));
                helper.assertTrue(result == 1, "The management command must succeed");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                throw new AssertionError("The management command must remain callable", e);
            }
            assertLanding(helper, player, chrono, landing);
            helper.assertTrue(
                    player.getData(ModAttachments.PORTAL_STATE).equals(before),
                    "The management command must not overwrite the portal return record");
        });
        helper.succeed();
    }

    private static TestPlayer returningPlayer(ServerLevel level) {
        TestPlayer player = new TestPlayer(level);
        player.setPos(CURRENT.getX() + 0.25D, CURRENT.getY(), CURRENT.getZ() + 0.75D);
        player.setData(
                ModAttachments.PORTAL_STATE,
                new PortalState(Level.OVERWORLD.location().toString(), -32.25D, 64.75D, -48.25D));
        return player;
    }

    private static void prepareLanding(TrackingLevel level, BlockPos feet) {
        level.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
        level.heightQueries = 0;
        level.blockWrites = 0;
        level.requestedChunks.clear();
        level.queriedColumns.clear();
    }

    private static void assertLanding(GameTestHelper helper, TestPlayer player, TrackingLevel level, BlockPos feet) {
        helper.assertTrue(
                player.teleports == 1 && player.destination == level,
                "The entity must teleport once to the final target dimension");
        helper.assertTrue(
                Vec3.atBottomCenterOf(feet).equals(player.landing),
                "The landing must use the selected origin, including negative-coordinate flooring");
        helper.assertTrue(level.heightQueries == 1, "A safe final column must be searched exactly once");
        helper.assertTrue(level.blockWrites == 0, "A safe return or management command must not modify terrain");
        helper.assertTrue(
                level.requestedChunks.equals(Set.of(new ChunkPos(feet).toLong())),
                "Only the final landing chunk may be accessed");
    }

    @SuppressWarnings("deprecation")
    private static void withWorlds(GameTestHelper helper, BiConsumer<TrackingLevel, TrackingLevel> test) {
        ServerLevel original = helper.getLevel();
        MinecraftServer server = original.getServer();
        // 独立目录保留供诊断使用；只临时替换服务器维度映射，不修改原世界或删除区块文件。
        try (var storage = LevelStorageSource.createDefault(Files.createTempDirectory("ddt-r01-worlds-"))
                        .createAccess("portal");
                var overworld = new TrackingLevel(original, storage, Level.OVERWORLD);
                var chrono = new TrackingLevel(original, storage, ModDimensions.CHRONO)) {
            var levels = server.forgeGetWorldMap();
            ServerLevel previousOverworld = levels.put(Level.OVERWORLD, overworld);
            ServerLevel previousChrono = levels.put(ModDimensions.CHRONO, chrono);
            server.markWorldsDirty();
            try {
                test.accept(overworld, chrono);
            } finally {
                levels.put(Level.OVERWORLD, previousOverworld);
                if (previousChrono == null) {
                    levels.remove(ModDimensions.CHRONO);
                } else {
                    levels.put(ModDimensions.CHRONO, previousChrono);
                }
                server.markWorldsDirty();
            }
        } catch (IOException e) {
            throw new AssertionError("Isolated portal test worlds must open and close successfully", e);
        }
    }

    private static final class TestPlayer extends FakePlayer {
        private ServerLevel destination;
        private Vec3 landing;
        private int teleports;

        private TestPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "portal-test"));
        }

        // 仅截获最终传送，不将模拟玩家加入服务器玩家列表；非玩家用例另行验证实际跨维度传送。
        @Override
        public boolean teleportTo(
                ServerLevel level,
                double x,
                double y,
                double z,
                Set<RelativeMovement> relative,
                float yaw,
                float pitch) {
            teleportTo(level, x, y, z, yaw, pitch);
            return true;
        }

        @Override
        public void teleportTo(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
            destination = level;
            landing = new Vec3(x, y, z);
            teleports++;
        }
    }

    private static final class TrackingLevel extends ServerLevel {
        private int heightQueries;
        private int blockWrites;
        private final Set<Long> requestedChunks = new HashSet<>();
        private final Set<Long> queriedColumns = new HashSet<>();

        private TrackingLevel(
                ServerLevel original,
                LevelStorageSource.LevelStorageAccess storage,
                net.minecraft.resources.ResourceKey<Level> dimension) {
            super(
                    original.getServer(),
                    Util.backgroundExecutor(),
                    storage,
                    new DerivedLevelData(
                            original.getServer().getWorldData(),
                            original.getServer().getWorldData().overworldData()),
                    dimension,
                    new LevelStem(
                            original.dimensionTypeRegistration(),
                            original.getChunkSource().getGenerator()),
                    LoggerChunkProgressListener.createCompleted(),
                    false,
                    0L,
                    List.of(),
                    false,
                    null);
        }

        @Override
        public LevelChunk getChunk(int x, int z) {
            if (requestedChunks != null) {
                requestedChunks.add(ChunkPos.asLong(x, z));
            }
            return super.getChunk(x, z);
        }

        @Override
        public int getHeight(Heightmap.Types type, int x, int z) {
            heightQueries++;
            if (queriedColumns != null) {
                queriedColumns.add(ChunkPos.asLong(x, z));
            }
            return super.getHeight(type, x, z);
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
            blockWrites++;
            return super.setBlock(pos, state, flags, recursionLeft);
        }
    }
}
