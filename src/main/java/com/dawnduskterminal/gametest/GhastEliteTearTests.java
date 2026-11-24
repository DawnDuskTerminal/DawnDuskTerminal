package com.dawnduskterminal.gametest;

import com.dawnduskterminal.entity.GhastEliteEntity;
import com.dawnduskterminal.entity.GhastEliteTear;
import com.dawnduskterminal.network.GhastEliteTearPayload;
import com.dawnduskterminal.registry.ModEntities;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFDamageTypes;
import twilightforest.init.TFEntities;

/** 泪滴的服务端行为与视觉协议；实际客户端画面对照另行验收。 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class GhastEliteTearTests {
    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void sweptPlayerHitHasOwnerAndOnlyHurtsOnce(GameTestHelper helper) {
        Player player = player(helper, GameType.SURVIVAL, 8.5D, 2.0D, 8.5D);
        GhastEliteEntity owner = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
        GhastEliteTear tear = tear(helper, new Vec3(8.5D, 9.0D, 8.5D), new Vec3(0.0D, -10.0D, 0.0D));
        tear.setOwner(owner);
        tear.tick();
        helper.assertTrue(tear.isRemoved(), "a swept player hit must consume the tear");
        helper.assertTrue(player.getHealth() == 17.0F, "an indoor player hit must take exactly three damage");
        DamageSource source = player.getLastDamageSource();
        helper.assertTrue(source != null && source.is(TFDamageTypes.GHAST_TEAR), "the damage type must be GHAST_TEAR");
        helper.assertTrue(
                source.getDirectEntity() == tear && source.getEntity() == owner,
                "both damage entities must be retained");
        tear.tick();
        helper.assertTrue(player.getHealth() == 17.0F, "a consumed tear must not hit twice");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void loweredHitboxHitsPlayerBelowVisualOrigin(GameTestHelper helper) {
        Player player = player(helper, GameType.SURVIVAL, 8.5D, 2.0D, 8.5D);
        GhastEliteTear above = tear(helper, new Vec3(6.5D, 4.7D, 8.5D), new Vec3(4.0D, 0.0D, 0.0D));
        above.tick();
        helper.assertFalse(above.isRemoved(), "a player below the hitbox must not be hit");
        helper.assertTrue(player.getHealth() == 20.0F, "a missed tear must not damage the player");
        above.discard();
        GhastEliteTear overlap = tear(helper, new Vec3(6.5D, 4.3D, 8.5D), new Vec3(4.0D, 0.0D, 0.0D));
        overlap.tick();
        helper.assertTrue(overlap.isRemoved(), "a player overlapping the lowered hitbox must be hit");
        helper.assertTrue(player.getHealth() == 17.0F, "the lowered hitbox must deal normal tear damage");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void sidewaysMarginWidensPlayerHitVolume(GameTestHelper helper) {
        helper.assertTrue(
                GhastEliteTear.ENTITY_HIT_MARGIN == 0.6D, "the sideways player margin must stay at 0.6 blocks");
        Player beside = player(helper, GameType.SURVIVAL, 8.5D, 2.0D, 8.5D);
        GhastEliteTear wide = tear(helper, new Vec3(9.7D, 5.0D, 8.5D), new Vec3(0.0D, -0.5D, 0.0D));
        wide.tick();
        helper.assertTrue(wide.isRemoved(), "a tear 0.9 blocks beside the player must hit under the widened margin");
        helper.assertTrue(beside.getHealth() == 17.0F, "the widened margin must deal normal tear damage");
        beside.discard();
        Player clear = player(helper, GameType.SURVIVAL, 8.5D, 2.0D, 8.5D);
        GhastEliteTear outside = tear(helper, new Vec3(9.85D, 5.0D, 8.5D), new Vec3(0.0D, -0.5D, 0.0D));
        outside.tick();
        helper.assertFalse(outside.isRemoved(), "a tear beyond the widened margin must still miss the player");
        helper.assertTrue(clear.getHealth() == 20.0F, "a miss outside the widened margin must not damage the player");
        outside.discard();
        clear.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void roofBlocksDamageButNearbyRainDoesNot(GameTestHelper helper) {
        Player player = player(helper, GameType.SURVIVAL, 8.5D, 2.0D, 8.5D);
        helper.setBlock(new BlockPos(8, 5, 8), Blocks.STONE);
        GhastEliteTear blocked = tear(helper, new Vec3(8.5D, 9.0D, 8.5D), new Vec3(0.0D, -10.0D, 0.0D));
        blocked.tick();
        helper.assertTrue(blocked.isRemoved(), "the roof must consume the tear before it reaches the player");
        helper.assertTrue(player.getHealth() == 20.0F, "a roof must prevent damage");
        GhastEliteTear miss = tear(helper, new Vec3(10.5D, 9.0D, 8.5D), new Vec3(0.0D, -10.0D, 0.0D));
        miss.tick();
        helper.assertTrue(player.getHealth() == 20.0F, "nearby tears must not cause area damage");
        miss.discard();
        player.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void thinBlocksAndPhysicalEdgesStopFastTears(GameTestHelper helper) {
        helper.setBlock(new BlockPos(4, 3, 4), Blocks.STONE_SLAB);
        GhastEliteTear slab = tear(helper, new Vec3(4.5D, 9.0D, 4.5D), new Vec3(0.0D, -8.0D, 0.0D));
        slab.tick();
        helper.assertTrue(slab.isRemoved(), "a high-speed tear must not tunnel through a slab");
        helper.assertTrue(
                Math.abs(slab.getY() - helper.absolutePos(new BlockPos(4, 3, 4)).getY() - 1.3D) < 1.0E-6D,
                "the lowered hitbox must stop at the slab surface while retaining the visual origin");
        GhastEliteTear touching = tear(helper, new Vec3(4.5D, 4.3D, 4.5D), new Vec3(0.0D, -8.0D, 0.0D));
        Vec3 contact = touching.position();
        touching.tick();
        helper.assertTrue(
                touching.isRemoved() && touching.position().distanceToSqr(contact) < 1.0E-20D,
                "a tear starting on a surface must not tunnel through it at t=0");
        helper.setBlock(new BlockPos(8, 3, 8), Blocks.STONE);
        GhastEliteTear edge = tear(helper, new Vec3(7.65D, 9.0D, 8.5D), new Vec3(0.0D, -8.0D, 0.0D));
        helper.assertTrue(
                edge.getBbWidth() == 0.8F && edge.getBbHeight() == 0.8F, "the tear hitbox must be 0.8 by 0.8 blocks");
        helper.assertTrue(
                Math.abs(edge.getBoundingBox().minY - (edge.getY() - 0.8D)) < 1.0E-6D
                        && Math.abs(edge.getBoundingBox().maxY - edge.getY()) < 1.0E-6D,
                "the hitbox must extend 0.8 blocks below the visual origin");
        edge.tick();
        helper.assertTrue(edge.isRemoved(), "the 0.8 block volume must hit beyond the old 0.2 block boundary");
        GhastEliteTear outside = tear(helper, new Vec3(7.55D, 9.0D, 8.5D), new Vec3(0.0D, -4.0D, 0.0D));
        outside.tick();
        helper.assertFalse(outside.isRemoved(), "a tear outside the 0.8 block boundary must miss the block");
        outside.discard();
        helper.setBlock(new BlockPos(11, 3, 11), Blocks.OAK_FENCE);
        GhastEliteTear fence = tear(helper, new Vec3(11.5D, 5.05D, 9.5D), new Vec3(0.0D, 0.0D, 3.0D));
        fence.tick();
        helper.assertTrue(fence.isRemoved(), "a collision shape extending above its block must be included");
        helper.assertTrue(
                helper.getBlockState(new BlockPos(8, 3, 8)).is(Blocks.STONE), "tears must not destroy blocks");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void creativeSpectatorsDoNotIntercept(GameTestHelper helper) {
        for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
            Player player = player(helper, mode, 8.5D, 2.0D, 8.5D);
            GhastEliteTear tear = tear(helper, new Vec3(8.5D, 8.0D, 8.5D), new Vec3(0.0D, -5.0D, 0.0D));
            tear.tick();
            helper.assertFalse(tear.isRemoved(), "creative and spectator players must not intercept a tear");
            helper.assertTrue(player.getHealth() == 20.0F, "protected players must not be hurt");
            tear.discard();
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void otherMobsTakeTearDamage(GameTestHelper helper) {
        var owner = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
        var pig = helper.spawnWithNoFreeWill(EntityType.PIG, 8, 3, 8);
        GhastEliteTear tear = tear(helper, new Vec3(8.5D, 8.0D, 8.5D), new Vec3(0.0D, -4.0D, 0.0D));
        tear.setOwner(owner);
        tear.tick();
        helper.assertTrue(tear.isRemoved(), "a mob other than the elite's own minions must consume the tear");
        helper.assertTrue(pig.getHealth() == pig.getMaxHealth() - 3.0F, "a mob hit must take exactly three damage");
        DamageSource source = pig.getLastDamageSource();
        helper.assertTrue(
                source != null && source.is(TFDamageTypes.GHAST_TEAR), "mob damage must use the GHAST_TEAR type");
        helper.assertTrue(
                source.getDirectEntity() == tear && source.getEntity() == owner,
                "mob damage must retain both damage entities");
        helper.assertFalse(
                tear.getType().is(EntityTypeTags.REDIRECTABLE_PROJECTILE), "tears must not be redirectable fireballs");
        tear.discard();
        pig.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void minionsAndOwnerAreImmuneToTears(GameTestHelper helper) {
        var owner = helper.spawnWithNoFreeWill(ModEntities.GHAST_ELITE.get(), 8, 3, 8);
        owner.setHealth(100.0F);
        var minion = helper.spawnWithNoFreeWill(TFEntities.CARMINITE_GHASTLING.get(), 8, 3, 8);
        var marked = helper.spawnWithNoFreeWill(EntityType.PIG, 8, 3, 8);
        marked.getPersistentData().putBoolean(GhastEliteEntity.MINION_TAG, true);
        GhastEliteTear tear = tear(helper, new Vec3(8.5D, 8.0D, 8.5D), new Vec3(0.0D, -4.0D, 0.0D));
        tear.setOwner(owner);
        tear.tick();
        helper.assertFalse(tear.isRemoved(), "the owner and its minions must neither intercept nor consume a tear");
        helper.assertTrue(
                minion.getHealth() == minion.getMaxHealth() && marked.getHealth() == marked.getMaxHealth(),
                "ghastlings and minion-tagged entities must not be hurt");
        helper.assertTrue(owner.getHealth() == 100.0F, "tears must neither hurt nor heal the owner");
        tear.discard();
        minion.discard();
        marked.discard();
        owner.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void ordinaryInvulnerabilityFramesAreRespected(GameTestHelper helper) {
        Player player = player(helper, GameType.SURVIVAL, 8.5D, 2.0D, 8.5D);
        for (int count = 0; count < 2; count++) {
            GhastEliteTear tear = tear(helper, new Vec3(8.5D, 7.0D, 8.5D), new Vec3(0.0D, -5.0D, 0.0D));
            tear.tick();
            helper.assertTrue(tear.isRemoved(), "each actual hit must consume its tear even during invulnerability");
        }
        helper.assertTrue(
                player.getHealth() == 17.0F, "a second same-tick tear must respect normal invulnerability frames");
        player.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void cancelledImpactsDoNotDamageOrConsume(GameTestHelper helper) {
        Player player = player(helper, GameType.SURVIVAL, 8.5D, 2.0D, 8.5D);
        GhastEliteTear tear = tear(helper, new Vec3(8.5D, 7.0D, 8.5D), new Vec3(0.0D, -5.0D, 0.0D));
        Consumer<ProjectileImpactEvent> listener = event -> {
            if (event.getProjectile() == tear) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(ProjectileImpactEvent.class, listener);
        try {
            tear.tick();
            helper.assertFalse(tear.isRemoved(), "a cancelled impact must not consume the projectile");
            helper.assertTrue(player.getHealth() == 20.0F, "a cancelled impact must not hurt the player");
        } finally {
            NeoForge.EVENT_BUS.unregister(listener);
            tear.discard();
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void motionMatchesOriginalParticleArithmetic(GameTestHelper helper) {
        GhastEliteTear tear = tear(helper, new Vec3(8.0D, 12.0D, 8.0D), new Vec3(0.08D, 0.1D, -0.05D));
        Vec3 position = tear.position();
        Vec3 velocity = tear.getDeltaMovement();
        for (int tick = 0; tick < 8; tick++) {
            velocity = velocity.add(0.0D, -0.04D * (double) 0.6F, 0.0D);
            position = position.add(velocity);
            velocity = velocity.scale((double) 0.98F);
            tear.tick();
            helper.assertTrue(tear.position().distanceToSqr(position) < 1.0E-20D, "gravity must precede movement");
            helper.assertTrue(tear.getDeltaMovement().distanceToSqr(velocity) < 1.0E-20D, "drag must follow movement");
        }
        tear.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void reloadPreservesOwnerMotionAndRemainingLifetime(GameTestHelper helper) {
        var owner = helper.spawnWithNoFreeWill(ModEntities.GHAST_ELITE.get(), 8, 5, 8);
        GhastEliteTear original = tear(helper, new Vec3(2.0D, 10.0D, 2.0D), new Vec3(0.03D, -0.04D, 0.01D));
        original.setOwner(owner);
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        saved.putInt("Age", 59);
        saved.putInt("Lifetime", 60);
        original.discard();
        GhastEliteTear restored = new GhastEliteTear(ModEntities.GHAST_ELITE_TEAR.get(), helper.getLevel());
        restored.load(saved);
        helper.assertTrue(restored.getOwner() == owner, "the owner UUID must resolve after reloading");
        helper.assertTrue(
                restored.position().equals(original.position())
                        && restored.getDeltaMovement().equals(original.getDeltaMovement()),
                "position and velocity must survive reloading");
        restored.tick();
        helper.assertFalse(restored.isRemoved(), "one movement tick must remain");
        restored.tick();
        helper.assertTrue(restored.isRemoved(), "reloading must not reset the consumed lifetime");
        owner.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void voidAndInvalidMotionAreCleanedUp(GameTestHelper helper) {
        GhastEliteTear falling = tear(helper, new Vec3(8.0D, 8.0D, 8.0D), Vec3.ZERO);
        falling.setPos(falling.getX(), helper.getLevel().getMinBuildHeight() - 65.0D, falling.getZ());
        falling.tick();
        helper.assertTrue(falling.isRemoved(), "a tear entering the void must be removed");
        GhastEliteTear invalid = tear(helper, new Vec3(8.0D, 8.0D, 8.0D), new Vec3(Double.NaN, 0.0D, 0.0D));
        invalid.tick();
        helper.assertTrue(invalid.isRemoved(), "non-finite motion must not reach collision queries");
        helper.succeed();
    }

    @GameTest(template = "ddt_large", batch = "ghast_tears", timeoutTicks = 180)
    public static void normalWorldTicksKeepRainPopulationBounded(GameTestHelper helper) {
        GhastEliteEntity owner =
                (GhastEliteEntity) helper.spawnWithNoFreeWill(ModEntities.GHAST_ELITE.get(), 16, 20, 16);
        owner.setNoGravity(true);
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(owner.tickCount >= 100, "this check requires normal entity ticks");
            List<GhastEliteTear> tears = helper.getLevel()
                    .getEntitiesOfClass(
                            GhastEliteTear.class,
                            owner.getBoundingBox().inflate(128.0D),
                            tear -> tear.getOwner() == owner);
            helper.assertTrue(
                    !tears.isEmpty() && tears.size() <= 100,
                    "one tear per tick must be bounded by its maximum lifetime");
            for (GhastEliteTear tear : tears) {
                CompoundTag data = tear.saveWithoutId(new CompoundTag());
                helper.assertTrue(
                        data.getInt("Lifetime") >= 60 && data.getInt("Lifetime") <= 99,
                        "spawned lifetimes must stay within the original distribution");
            }
            owner.discard();
            tears.forEach(GhastEliteTear::discard);
            helper.succeed();
        });
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void bossTickDoesNotRetainPeriodicAreaDamage(GameTestHelper helper) {
        GhastEliteEntity owner = (GhastEliteEntity) helper.spawnWithNoFreeWill(ModEntities.GHAST_ELITE.get(), 8, 7, 8);
        owner.setNoGravity(true);
        Player player = player(helper, GameType.SURVIVAL, 8.0D, 2.0D, 8.0D);
        for (int tick = 0; tick < 20; tick++) {
            owner.tick();
        }
        helper.assertTrue(player.getHealth() == 20.0F, "boss ticks alone must not apply old periodic area damage");
        List<GhastEliteTear> tears = helper.getLevel()
                .getEntitiesOfClass(
                        GhastEliteTear.class, owner.getBoundingBox().inflate(4.0D), tear -> tear.getOwner() == owner);
        helper.assertTrue(tears.size() == 20, "exactly one server projectile must be spawned per boss tick");
        tears.forEach(GhastEliteTear::discard);
        owner.discard();
        player.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void visualProtocolRoundTripsAndRejectsInvalidStates(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        RegistryFriendlyByteBuf buffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            for (int kind :
                    List.of(GhastEliteTearPayload.STATE, GhastEliteTearPayload.BREAK, GhastEliteTearPayload.END)) {
                GhastEliteTearPayload payload = new GhastEliteTearPayload(
                        Level.OVERWORLD,
                        id,
                        42,
                        kind,
                        new Vec3(1.0D, 2.0D, 3.0D),
                        new Vec3(0.1D, -0.5D, 0.0D),
                        10,
                        80,
                        123L);
                GhastEliteTearPayload.STREAM_CODEC.encode(buffer, payload);
                GhastEliteTearPayload decoded = GhastEliteTearPayload.STREAM_CODEC.decode(buffer);
                helper.assertTrue(
                        payload.equals(decoded) && decoded.valid(), "all three visual states must round-trip");
                buffer.clear();
            }
        } finally {
            buffer.release();
        }
        for (GhastEliteTearPayload invalid : List.of(
                new GhastEliteTearPayload(Level.OVERWORLD, id, 42, 3, Vec3.ZERO, Vec3.ZERO, 0, 80, 0L),
                new GhastEliteTearPayload(
                        Level.OVERWORLD, id, 42, 0, new Vec3(Double.NaN, 0.0D, 0.0D), Vec3.ZERO, 0, 80, 0L),
                new GhastEliteTearPayload(
                        Level.OVERWORLD,
                        id,
                        42,
                        0,
                        Vec3.ZERO,
                        new Vec3(0.0D, Double.POSITIVE_INFINITY, 0.0D),
                        0,
                        80,
                        0L),
                new GhastEliteTearPayload(Level.OVERWORLD, id, 42, 0, Vec3.ZERO, Vec3.ZERO, 0, 100, 0L),
                new GhastEliteTearPayload(Level.OVERWORLD, id, 42, 0, Vec3.ZERO, Vec3.ZERO, 81, 80, 0L),
                new GhastEliteTearPayload(Level.OVERWORLD, id, 42, 0, Vec3.ZERO, Vec3.ZERO, 0, 80, -1L))) {
            helper.assertFalse(invalid.valid(), "malformed visual parameters must be rejected");
        }
        helper.assertTrue(
                GhastEliteTear.VISUAL_RANGE == 512.0D, "visual delivery must retain the original 512 block radius");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void visualDeliveryDoesNotRequireEntityTracking(GameTestHelper helper) {
        Vec3 origin = helper.absoluteVec(new Vec3(8.0D, 8.0D, 8.0D));
        for (int distance : List.of(0, 64, 160, 511, 513)) {
            List<GhastEliteTearPayload> received = new ArrayList<>();
            GameProfile profile = new GameProfile(UUID.randomUUID(), "tear-visual-test");
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            ServerPlayer player = new ServerPlayer(
                    helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation());
            new ServerGamePacketListenerImpl(
                    helper.getLevel().getServer(), new Connection(PacketFlow.SERVERBOUND), player, cookie) {
                @Override
                public void send(Packet<?> packet) {
                    if (packet instanceof ClientboundCustomPayloadPacket custom
                            && custom.payload() instanceof GhastEliteTearPayload payload) {
                        received.add(payload);
                    }
                }
            };
            player.setPos(origin.add(distance, 0.0D, 0.0D));
            helper.getLevel().addNewPlayer(player);
            GhastEliteEntity owner = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
            try {
                GhastEliteTear.spawn(helper.getLevel(), owner, origin);
                helper.assertTrue(
                        received.size() == (distance <= 512 ? 1 : 0),
                        "state delivery must obey 512 blocks without a tracking prerequisite: " + distance);
                List<GhastEliteTear> tears = helper.getLevel()
                        .getEntitiesOfClass(
                                GhastEliteTear.class,
                                new net.minecraft.world.phys.AABB(origin, origin).inflate(1.0D),
                                tear -> tear.getOwner() == owner);
                helper.assertTrue(tears.size() == 1, "the spawn must create one server entity");
                GhastEliteTear tear = tears.getFirst();
                tear.discard();
                helper.assertTrue(
                        received.size() == (distance <= 512 ? 2 : 0),
                        "normal termination must use the same delivery radius");
                if (distance <= 512) {
                    helper.assertTrue(
                            received.getLast().kind() == GhastEliteTearPayload.END
                                    && received.getLast()
                                            .id()
                                            .equals(received.getFirst().id()),
                            "state and termination must share one UUID");
                }
            } finally {
                helper.getLevel()
                        .removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghast_tears")
    public static void flowingWaterDoesNotAlterParticleMotion(GameTestHelper helper) {
        for (BlockPos pos : BlockPos.betweenClosed(6, 8, 6, 10, 13, 10)) {
            helper.setBlock(pos, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8));
        }
        GhastEliteTear tear = tear(helper, new Vec3(8.5D, 11.0D, 8.5D), new Vec3(0.05D, 0.0D, 0.0D));
        Vec3 expected = tear.position();
        Vec3 velocity = tear.getDeltaMovement();
        for (int tick = 0; tick < 8; tick++) {
            velocity = velocity.add(0.0D, -0.04D * (double) 0.6F, 0.0D);
            expected = expected.add(velocity);
            velocity = velocity.scale((double) 0.98F);
            tear.tick();
            helper.assertTrue(
                    tear.position().distanceToSqr(expected) < 1.0E-20D,
                    "water must not add fluid pushing or throwable-projectile drag");
        }
        tear.discard();
        helper.succeed();
    }

    private static Player player(GameTestHelper helper, GameType mode, double x, double y, double z) {
        Player player = helper.makeMockPlayer(mode);
        player.setPos(helper.absoluteVec(new Vec3(x, y, z)));
        helper.getLevel().addFreshEntity(player);
        return player;
    }

    private static GhastEliteTear tear(GameTestHelper helper, Vec3 relativePosition, Vec3 velocity) {
        GhastEliteTear tear = new GhastEliteTear(ModEntities.GHAST_ELITE_TEAR.get(), helper.getLevel());
        tear.setPos(helper.absoluteVec(relativePosition));
        tear.setDeltaMovement(velocity);
        helper.getLevel().addFreshEntity(tear);
        return tear;
    }
}
