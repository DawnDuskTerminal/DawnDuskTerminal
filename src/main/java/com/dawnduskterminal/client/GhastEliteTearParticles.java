package com.dawnduskterminal.client;

import com.dawnduskterminal.entity.GhastEliteTear;
import com.dawnduskterminal.network.GhastEliteTearPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import twilightforest.client.particle.GhastTearParticle;

/** 近处与远处共用同一张 UUID 表，原暮色粒子仅提供渲染与受控触发的破碎效果。 */
public final class GhastEliteTearParticles {
    private static final Map<UUID, Tear> TEARS = new HashMap<>();
    private static final int MAX_VISUALS = 8192;
    private static ClientLevel world;

    private GhastEliteTearParticles() {}

    public static void accept(GhastEliteTearPayload payload) {
        ClientLevel level = currentLevel();
        if (level == null || !payload.valid() || !level.dimension().equals(payload.dimension())) {
            return;
        }
        long elapsed = level.getGameTime() - payload.gameTime();
        if (elapsed < -20 || elapsed > GhastEliteTear.MAX_LIFETIME + 20L) {
            return;
        }
        Tear tear = TEARS.get(payload.id());
        if (tear != null && payload.gameTime() < tear.stateTime) {
            return;
        }
        if (payload.kind() != GhastEliteTearPayload.STATE) {
            TEARS.remove(payload.id());
            if (payload.kind() == GhastEliteTearPayload.BREAK && elapsed <= 20) {
                if (tear == null) {
                    tear = new Tear(level, payload);
                }
                tear.shatter(payload.position());
            } else if (tear != null) {
                tear.remove();
            }
            return;
        }
        int delay = (int) Math.max(0L, elapsed);
        if (payload.age() + delay >= payload.lifetime()) {
            return;
        }
        if (tear == null || !tear.isAlive()) {
            if (TEARS.size() >= MAX_VISUALS) {
                return;
            }
            tear = new Tear(level, payload);
            TEARS.put(payload.id(), tear);
            Minecraft.getInstance().particleEngine.add(tear);
        }
        tear.update(payload, delay);
    }

    public static void onTick(ClientTickEvent.Post event) {
        ClientLevel level = currentLevel();
        if (level != null) {
            TEARS.values().removeIf(tear -> {
                if (!tear.isAlive() || level.getGameTime() > tear.expiresAt) {
                    tear.remove();
                    return true;
                }
                return false;
            });
        }
    }

    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() == world) {
            clear();
        }
    }

    public static void clear() {
        TEARS.values().forEach(Tear::remove);
        TEARS.clear();
        world = null;
    }

    private static ClientLevel currentLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (world != level) {
            clear();
            world = level;
        }
        return level;
    }

    private static final class Tear extends GhastTearParticle {
        private final UUID id;
        private final int entityId;
        private Vec3 velocity = Vec3.ZERO;
        private long stateTime;
        private long expiresAt;

        private Tear(ClientLevel level, GhastEliteTearPayload payload) {
            super(level, payload.position().x, payload.position().y, payload.position().z, Items.GHAST_TEAR);
            this.id = payload.id();
            this.entityId = payload.entityId();
            this.hasPhysics = false;
        }

        private void update(GhastEliteTearPayload payload, int delay) {
            this.stateTime = payload.gameTime();
            this.lifetime = payload.lifetime();
            this.age = payload.age();
            this.velocity = payload.velocity();
            this.setPos(payload.position().x, payload.position().y, payload.position().z);
            for (int tick = 0; tick < delay; tick++) {
                this.advance();
                ++this.age;
            }
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            this.expiresAt = this.level.getGameTime() + this.lifetime - this.age + 20L;
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            if (this.age++ >= this.lifetime) {
                this.remove();
                return;
            }
            Entity tracked = this.level.getEntity(this.entityId);
            if (tracked instanceof GhastEliteTear
                    && tracked.getUUID().equals(this.id)
                    && !tracked.isRemoved()
                    && tracked.position().distanceToSqr(new Vec3(this.x, this.y, this.z)) > 4.0D) {
                this.setPos(tracked.getX(), tracked.getY(), tracked.getZ());
            }
            this.advance();
        }

        private void advance() {
            Vec3 movement = GhastEliteTear.fallingVelocity(this.velocity);
            this.setPos(this.x + movement.x, this.y + movement.y, this.z + movement.z);
            this.velocity = GhastEliteTear.dampedVelocity(movement);
        }

        private void shatter(Vec3 position) {
            this.setPos(position.x, position.y, position.z);
            this.onGround = true;
            // 仅在服务端确认命中后调用父类，精确保留 50 颗碎片与随机破碎音效。
            super.tick();
            this.remove();
        }
    }
}
