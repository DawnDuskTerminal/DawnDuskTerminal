package com.dawnduskterminal.entity;

import com.dawnduskterminal.registry.ModEntities;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.jetbrains.annotations.Nullable;

public final class VirtualImageRecording {
    private static final Map<ServerPlayer, Session> ACTIVE = new HashMap<>();
    private static final Map<ServerPlayer, PendingDeath> DEATHS = new HashMap<>();

    private VirtualImageRecording() {}

    static void targetChanged(
            VirtualImageEntity image, @Nullable LivingEntity previous, @Nullable LivingEntity current) {
        if (previous == current || image.level().isClientSide) {
            return;
        }
        stopTracking(image, previous);
        if (current instanceof ServerPlayer player
                && player.isAlive()
                && player.level() == image.level()
                && image.isAlive()) {
            Session session = ACTIVE.computeIfAbsent(player, ignored -> new Session());
            if (session.hunters.isEmpty()) {
                session.sample(player);
            }
            session.hunters.add(image);
        }
    }

    static void stopTracking(VirtualImageEntity image, @Nullable LivingEntity target) {
        if (target instanceof ServerPlayer player) {
            Session session = ACTIVE.get(player);
            if (session != null && session.hunters.remove(image) && session.hunters.isEmpty()) {
                ACTIVE.remove(player);
            }
        }
    }

    public static void sample(ServerPlayer player) {
        Session session = ACTIVE.get(player);
        if (session != null && player.isAlive()) {
            session.sample(player);
        }
    }

    public static void recordSwing(ServerPlayer player, InteractionHand hand) {
        Session session = ACTIVE.get(player);
        if (session != null) {
            session.actions |= VirtualImageTrace.swing(hand);
        }
    }

    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Session session = ACTIVE.get(player);
        if (session == null
                || DEATHS.containsKey(player)
                || session.hunters.stream().noneMatch(image -> {
                    double range = image.getAttributeValue(Attributes.FOLLOW_RANGE);
                    return image.isAlive()
                            && image.getTarget() == player
                            && image.level() == player.level()
                            && image.distanceToSqr(player) <= range * range;
                })) {
            return;
        }
        session.sample(player);
        DEATHS.put(player, new PendingDeath(event, player.serverLevel(), session.trace.snapshot()));
    }

    public static void finishDeaths() {
        if (DEATHS.isEmpty()) {
            return;
        }
        for (Map.Entry<ServerPlayer, PendingDeath> entry : DEATHS.entrySet()) {
            PendingDeath death = entry.getValue();
            if (death.event.isCanceled()) {
                continue;
            }
            ACTIVE.remove(entry.getKey());
            VirtualImageEntity replay = ModEntities.VIRTUAL_IMAGE.get().create(death.level);
            if (replay != null) {
                replay.spawnReplay(death.level, death.trace);
            }
        }
        DEATHS.clear();
    }

    public static void leave(ServerPlayer player) {
        ACTIVE.remove(player);
    }

    public static void clear() {
        ACTIVE.clear();
        DEATHS.clear();
    }

    private record PendingDeath(LivingDeathEvent event, ServerLevel level, VirtualImageTrace trace) {}

    private static final class Session {
        private final Set<VirtualImageEntity> hunters = new HashSet<>();
        private final VirtualImageTrace.Buffer trace = new VirtualImageTrace.Buffer();
        private int lastTick = -1;
        private long actions;

        private void sample(ServerPlayer player) {
            int tick = player.serverLevel().getServer().getTickCount();
            long frameActions = this.actions;
            if (player.swinging && player.swingTime <= 0) {
                frameActions |= VirtualImageTrace.swing(player.swingingArm);
            }
            this.trace.sample(player, System.nanoTime(), tick == this.lastTick, frameActions);
            this.actions = 0L;
            this.lastTick = tick;
        }
    }
}
