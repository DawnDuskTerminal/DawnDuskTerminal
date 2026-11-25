package com.dawnduskterminal.gametest;

import com.dawnduskterminal.portal.NetherGateBlock;
import com.dawnduskterminal.portal.PortalBlock;
import com.dawnduskterminal.portal.PortalState;
import com.dawnduskterminal.portal.PortalTeleporter;
import com.dawnduskterminal.registry.ModAttachments;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.ChronoLineState;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LoggerChunkProgressListener;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFBlocks;
import twilightforest.init.TFDimension;

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
            // 平坦测试世界的自然地表低于搜索下限，舍弃区域一旦被搜索就只会落进回退分支
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
    public static void leavingDimensionUsesRecordedOriginWithoutBuildingPlatform(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            TestPlayer player = returningPlayer(chrono);
            Vec3 origin = new Vec3(CURRENT.getX() + 0.25D, CURRENT.getY(), CURRENT.getZ() + 0.75D);
            player.setData(
                    ModAttachments.PORTAL_STATE,
                    new PortalState(Level.OVERWORLD.location().toString(), origin.x, origin.y, origin.z));
            // 平坦测试世界处处低于落地下限，回程搜索必然失败并落进不铺平台的分支
            helper.assertTrue(
                    overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CURRENT.getX(), CURRENT.getZ())
                            < PortalTeleporter.LANDING_MIN_Y,
                    "The return column must not provide a natural landing");
            PortalTeleporter.teleportEntity(player);
            helper.assertTrue(
                    player.landing != null && player.landing.distanceToSqr(origin) < 1.0E-9D,
                    "A failed return search must land on the recorded origin");
            helper.assertTrue(
                    overworld.blockWrites == 0, "Leaving the chrono dimension must not modify the target terrain");
            helper.assertTrue(
                    player.teleports == 1 && player.destination == overworld,
                    "Leaving must teleport once to the overworld");
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

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    public static void specialPortalsSelectAndReuseTheirOwnExits(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            var tfKey = TFDimension.DIMENSION_KEY;
            var server = chrono.getServer();
            var levels = server.forgeGetWorldMap();
            try (var storage = LevelStorageSource.createDefault(Files.createTempDirectory("ddt-route-test"))
                            .createAccess("routes");
                    var twilight = new TrackingLevel(overworld, storage, tfKey);
                    var nether = new TrackingLevel(overworld, storage, Level.NETHER)) {
                var oldTf = levels.put(tfKey, twilight);
                var oldNether = levels.put(Level.NETHER, nether);
                server.markWorldsDirty();
                try {
                    var pool = new BlockPos(-64, 64, -64);
                    for (var side : List.of(twilight, chrono)) {
                        var catalyst = side == twilight ? ModItems.ENCASED_DUST.get() : Items.DIAMOND;
                        for (int x = -1; x <= 2; x++) {
                            for (int z = -1; z <= 2; z++) {
                                var p = pool.offset(x, 0, z);
                                side.setBlockAndUpdate(p.below(), Blocks.STONE.defaultBlockState());
                                if (x >= 0 && x < 2 && z >= 0 && z < 2)
                                    side.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
                                else {
                                    side.setBlockAndUpdate(p, Blocks.GRASS_BLOCK.defaultBlockState());
                                    side.setBlockAndUpdate(p.above(), Blocks.POPPY.defaultBlockState());
                                }
                            }
                        }
                        var item = new ItemEntity(side, pool.getX(), pool.getY(), pool.getZ(), new ItemStack(catalyst));
                        if (side == chrono) {
                            helper.assertFalse(
                                    TFBlocks.TWILIGHT_PORTAL
                                            .get()
                                            .tryToCreatePortal(side, pool, item, new TestPlayer(side)),
                                    "TF must leave the DDT diamond for the DDT gate");
                        }
                        PortalBlock.tryIgnite(side, pool, item);
                        helper.assertTrue(
                                item.isRemoved() && side.getBlockState(pool).is(ModBlocks.TWILIGHT_PORTAL.get()),
                                "The local catalyst must open the twilight pool");
                    }
                    var traveler = touchGate(helper, twilight, pool, chrono);
                    helper.assertTrue(
                            traveler.landing.distanceToSqr(Vec3.atCenterOf(pool)) < 16,
                            "The existing DDT pool must be reused");
                    touchGate(helper, chrono, pool, twilight);
                    var natural = pool.offset(8, 0, 0);
                    for (int x = 0; x < 2; x++)
                        for (int z = 0; z < 2; z++)
                            chrono.setBlockAndUpdate(
                                    natural.offset(x, 0, z),
                                    ModBlocks.PORTAL_FLUID.get().defaultBlockState());
                    touchGate(helper, chrono, natural, overworld);

                    var remotePool = pool.offset(64, 100, 0);
                    for (int x = -6; x <= 6; x++) {
                        for (int z = -6; z <= 6; z++) {
                            var p = remotePool.offset(x, 0, z);
                            twilight.setBlockAndUpdate(p.below(97), Blocks.DIRT.defaultBlockState());
                            twilight.setBlockAndUpdate(p.below(96), Blocks.GRASS_BLOCK.defaultBlockState());
                            twilight.setBlockAndUpdate(p.below(95), Blocks.SNOW.defaultBlockState());
                        }
                    }
                    for (int x = -1; x <= 2; x++) {
                        for (int z = -1; z <= 2; z++) {
                            var p = remotePool.offset(x, 0, z);
                            chrono.setBlockAndUpdate(p.below(), Blocks.STONE.defaultBlockState());
                            chrono.setBlockAndUpdate(
                                    p,
                                    (x >= 0 && x < 2 && z >= 0 && z < 2
                                                    ? ModBlocks.TWILIGHT_PORTAL.get()
                                                    : Blocks.GRASS_BLOCK)
                                            .defaultBlockState());
                            boolean interior = x >= 0 && x < 2 && z >= 0 && z < 2;
                            BlockPos buried = p.below(101);
                            twilight.setBlockAndUpdate(buried.below(), Blocks.DIRT.defaultBlockState());
                            twilight.setBlockAndUpdate(
                                    buried,
                                    (interior ? ModBlocks.TWILIGHT_PORTAL.get() : Blocks.GRASS_BLOCK)
                                            .defaultBlockState());
                            if (interior)
                                twilight.setBlockAndUpdate(
                                        p, ModBlocks.TWILIGHT_PORTAL.get().defaultBlockState());
                        }
                    }
                    var grounded = touchGate(helper, chrono, remotePool, twilight);
                    helper.assertTrue(
                            grounded.landing.y == 69,
                            "A high DDT gate must land at the Twilight surface, got " + grounded.landing);
                    BlockPos feet = BlockPos.containing(grounded.landing);
                    BlockPos exitPool = feet.north().below();
                    BlockPos rim = feet.below();
                    helper.assertTrue(
                            twilight.getBlockState(exitPool).is(ModBlocks.TWILIGHT_PORTAL.get())
                                    && twilight.getBlockState(rim).is(Blocks.GRASS_BLOCK)
                                    && twilight.getBlockState(exitPool.below()).is(Blocks.DIRT),
                            "A generated portal must have a bank and solid floor");
                    helper.assertTrue(
                            twilight.getBlockState(feet).is(Blocks.SNOW)
                                    && twilight.getBlockState(rim)
                                            .getValue(net.minecraft.world.level.block.SnowyDirtBlock.SNOWY),
                            "Snow and snowy grass must be preserved beside the generated pool");
                    twilight.setBlockAndUpdate(rim, Blocks.AIR.defaultBlockState());
                    var blocked = new TestPlayer(twilight);
                    blocked.setPos(Vec3.atCenterOf(exitPool));
                    twilight.getBlockState(exitPool).entityInside(twilight, exitPool, blocked);
                    helper.assertTrue(blocked.destination == null, "A broken bank must immediately disable travel");
                    twilight.getBlockState(exitPool).tick(twilight, exitPool, twilight.getRandom());
                    helper.assertTrue(
                            twilight.getBlockState(exitPool).isAir(), "The invalid pool must disappear on update");

                    var frame = new BlockPos(80, 64, 80);
                    helper.assertTrue(
                            igniteFrame(chrono, frame, Blocks.OBSIDIAN, ModTerrainBlocks.CHAOS_STONE.get()),
                            "Flint and steel must ignite the frame in DDT");
                    var vanilla = new BlockPos(10, 64, 10);
                    helper.assertFalse(
                            igniteFrame(nether, vanilla, Blocks.OBSIDIAN, Blocks.OBSIDIAN),
                            "Nether ignition must remain available to vanilla");
                    for (int x = 0; x < 2; x++)
                        for (int y = 0; y < 3; y++)
                            nether.setBlock(vanilla.offset(x, y, 0), Blocks.NETHER_PORTAL.defaultBlockState(), 2);
                    var first = touchGate(helper, chrono, frame, nether);
                    var exit = BlockPos.containing(first.landing).north();
                    helper.assertTrue(
                            nether.getBlockState(exit).is(ModBlocks.NETHER_PORTAL.get()),
                            "The generated portal must have the DDT route");
                    helper.assertTrue(
                            Math.abs(exit.getX() - 10) <= 14 && Math.abs(exit.getZ() - 10) <= 14 && exit.getY() < 128,
                            "The exit must use Nether scale below the roof");
                    helper.assertTrue(
                            nether.getBlockState(vanilla).is(Blocks.NETHER_PORTAL), "The vanilla portal must survive");
                    var back = touchGate(helper, nether, exit, chrono);
                    helper.assertTrue(
                            back.landing.distanceToSqr(Vec3.atCenterOf(frame)) < 20,
                            "The original DDT frame must be reused");
                    var bottom = frame.below();
                    chrono.setBlockAndUpdate(bottom, Blocks.OBSIDIAN.defaultBlockState());
                    chrono.setBlockAndUpdate(
                            bottom, ModTerrainBlocks.CHAOS_STONE.get().defaultBlockState());
                    helper.assertTrue(
                            chrono.getBlockState(frame).is(ModBlocks.NETHER_PORTAL.get()),
                            "A chaos stone frame block must keep the portal active after a neighbor update");
                    chrono.setBlockAndUpdate(bottom, Blocks.AIR.defaultBlockState());
                    helper.assertTrue(
                            chrono.getBlockState(frame).isAir(), "Breaking the frame must extinguish the portal");
                } finally {
                    if (oldTf == null) levels.remove(tfKey);
                    else levels.put(tfKey, oldTf);
                    if (oldNether == null) levels.remove(Level.NETHER);
                    else levels.put(Level.NETHER, oldNether);
                    server.markWorldsDirty();
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    public static void chaosStoneFrameWorksInChrono(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            BlockPos origin = new BlockPos(48, 64, 48);
            Block chaosStone = ModTerrainBlocks.CHAOS_STONE.get();
            helper.assertTrue(
                    igniteFrame(chrono, origin, chaosStone, chaosStone),
                    "A frame made entirely of chaos stone must ignite");
            helper.assertTrue(
                    chrono.getBlockState(origin).is(ModBlocks.NETHER_PORTAL.get()),
                    "Ignition must create the DDT nether gate");
            igniteFrame(overworld, origin, chaosStone, chaosStone);
            helper.assertTrue(
                    net.minecraft.world.level.portal.PortalShape.findEmptyPortalShape(
                                    overworld, origin, Direction.Axis.X)
                            .isEmpty(),
                    "Vanilla portal validation outside DDT must reject chaos stone");
        });
        helper.succeed();
    }

    private static boolean igniteFrame(ServerLevel level, BlockPos origin, Block primary, Block alternate) {
        for (int x = -1; x <= 2; x++)
            for (int y = -1; y <= 3; y++) {
                if (x == -1 || x == 2 || y == -1 || y == 3)
                    level.setBlockAndUpdate(
                            origin.offset(x, y, 0), ((x + y) % 2 == 0 ? primary : alternate).defaultBlockState());
            }
        level.setBlockAndUpdate(origin.south().below(), Blocks.STONE.defaultBlockState());
        var player = new TestPlayer(level);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
        var hit = new BlockHitResult(Vec3.atCenterOf(origin.below()), Direction.UP, origin.below(), false);
        var event = new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, origin.below(), hit);
        NetherGateBlock.ignite(event);
        return event.isCanceled();
    }

    private static TestPlayer touchGate(
            GameTestHelper helper, ServerLevel source, BlockPos gate, ServerLevel expected) {
        var player = new TestPlayer(source);
        player.setPos(Vec3.atCenterOf(gate));
        source.getBlockState(gate).entityInside(source, gate, player);
        helper.assertTrue(
                player.destination == expected,
                "Gate at " + gate + " must lead to " + expected.dimension() + ": " + player.message);
        return player;
    }

    @GameTest(template = "ddt_empty", batch = "portal_teleporter")
    @SuppressWarnings("deprecation")
    public static void buildsExitInGeneratedTwilightTerrain(GameTestHelper helper) {
        withWorlds(helper, (overworld, chrono) -> {
            var server = chrono.getServer();
            var levels = server.forgeGetWorldMap();
            try (var input = TFDimension.class.getResourceAsStream(
                            "/data/twilightforest/dimension/twilight_forest.json");
                    var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                    var storage = LevelStorageSource.createDefault(Files.createTempDirectory("ddt-generated-twilight"))
                            .createAccess("routes")) {
                var stem = LevelStem.CODEC
                        .parse(
                                RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()),
                                JsonParser.parseReader(reader))
                        .getOrThrow();
                try (var twilight = new TrackingLevel(overworld, storage, TFDimension.DIMENSION_KEY, stem)) {
                    var previous = levels.put(TFDimension.DIMENSION_KEY, twilight);
                    server.markWorldsDirty();
                    try {
                        BlockPos pool = new BlockPos(16, 200, 16);
                        BlockState portal = ModBlocks.TWILIGHT_PORTAL.get().defaultBlockState();
                        helper.assertTrue(
                                portal.skipRendering(portal, Direction.EAST), "Adjacent pool faces must be culled");
                        for (int x = -1; x <= 2; x++) {
                            for (int z = -1; z <= 2; z++) {
                                BlockPos pos = pool.offset(x, 0, z);
                                chrono.setBlockAndUpdate(pos.below(), Blocks.DIRT.defaultBlockState());
                                chrono.setBlockAndUpdate(
                                        pos,
                                        x >= 0 && x < 2 && z >= 0 && z < 2
                                                ? portal
                                                : Blocks.GRASS_BLOCK.defaultBlockState());
                            }
                        }
                        var traveler = touchGate(helper, chrono, pool, twilight);
                        BlockPos feet = BlockPos.containing(traveler.landing);
                        helper.assertTrue(
                                feet.getY() < pool.getY()
                                        && twilight.getBlockState(feet.north().below())
                                                .is(portal.getBlock()),
                                "The generated forest must have a ground-level DDT return pool");
                        touchGate(helper, twilight, feet.north().below(), chrono);
                    } finally {
                        if (previous == null) levels.remove(TFDimension.DIMENSION_KEY);
                        else levels.put(TFDimension.DIMENSION_KEY, previous);
                        server.markWorldsDirty();
                    }
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
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
        private String message;
        private Vec3 landing;
        private int teleports;

        private TestPlayer(ServerLevel level) {
            super(level, new GameProfile(UUID.randomUUID(), "portal-test"));
        }

        @Override
        public void displayClientMessage(net.minecraft.network.chat.Component component, boolean overlay) {
            message = component.getString();
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
            this(
                    original,
                    storage,
                    dimension,
                    new LevelStem(
                            original.dimensionTypeRegistration(),
                            original.getChunkSource().getGenerator()));
        }

        private TrackingLevel(
                ServerLevel original,
                LevelStorageSource.LevelStorageAccess storage,
                net.minecraft.resources.ResourceKey<Level> dimension,
                LevelStem stem) {
            super(
                    original.getServer(),
                    Util.backgroundExecutor(),
                    storage,
                    new DerivedLevelData(
                            original.getServer().getWorldData(),
                            original.getServer().getWorldData().overworldData()),
                    dimension,
                    stem,
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
