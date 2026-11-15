package com.dawnduskterminal.entity;

import com.dawnduskterminal.network.GhastEliteTearPayload;
import com.dawnduskterminal.registry.ModEntities;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.network.PacketDistributor;
import twilightforest.init.TFDamageTypes;
import twilightforest.init.TFEntities;

/** 大恶魂的服务端泪滴；客户端粒子只显示状态，不参与伤害或碰撞判定。 */
public final class GhastEliteTear extends Projectile {
    public static final int MIN_LIFETIME = 60;
    public static final int MAX_LIFETIME = 99;
    public static final float DAMAGE = 3.0F;
    public static final double VISUAL_RANGE = 512.0D;
    private static final double HITBOX_DROP = 0.8D;
    private int age;
    private int lifetime;
    private boolean ended;

    public GhastEliteTear(EntityType<? extends GhastEliteTear> type, Level level) {
        super(type, level);
        this.lifetime = MIN_LIFETIME + this.random.nextInt(40);
    }

    public static void spawn(ServerLevel level, GhastEliteEntity owner, Vec3 position) {
        GhastEliteTear tear = new GhastEliteTear(ModEntities.GHAST_ELITE_TEAR.get(), level);
        tear.setOwner(owner);
        tear.setPos(position);
        tear.setDeltaMovement(randomVelocity(level.random));
        if (level.addFreshEntity(tear)) {
            tear.sendVisual(GhastEliteTearPayload.STATE);
        }
    }

    /** 与原版 Particle 的随机初速度分布一致，不叠加召唤者的运动。 */
    private static Vec3 randomVelocity(RandomSource random) {
        Vec3 direction = new Vec3(
                (random.nextDouble() * 2.0D - 1.0D) * 0.4F,
                (random.nextDouble() * 2.0D - 1.0D) * 0.4F,
                (random.nextDouble() * 2.0D - 1.0D) * 0.4F);
        double speed = (random.nextDouble() + random.nextDouble() + 1.0D) * 0.15F;
        return direction.normalize().scale(speed * 0.4F).add(0.0D, 0.1F, 0.0D);
    }

    public static Vec3 fallingVelocity(Vec3 velocity) {
        return velocity.add(0.0D, -0.04D * (double) 0.6F, 0.0D);
    }

    public static Vec3 dampedVelocity(Vec3 velocity) {
        return velocity.scale((double) 0.98F);
    }

    @Override
    public void tick() {
        if (this.isRemoved()) {
            return;
        }
        super.tick();
        if (this.level().isClientSide || this.isRemoved()) {
            return;
        }
        if (this.age >= this.lifetime
                || !GhastEliteTearPayload.finitePosition(this.position())
                || !GhastEliteTearPayload.finiteVelocity(this.getDeltaMovement())) {
            this.discard();
            return;
        }
        // 同一身份的状态快照兼顾读档及中途进入视觉范围的玩家，不会另建粒子。
        if (this.age == 0 || this.tickCount % 20 == 0) {
            this.sendVisual(GhastEliteTearPayload.STATE);
        }
        Vec3 velocity = fallingVelocity(this.getDeltaMovement());
        AABB swept = this.getBoundingBox().expandTowards(velocity).inflate(1.0E-7D);
        AABB blocks = swept.inflate(1.0D);
        if (!this.level()
                .hasChunksAt(
                        Mth.floor(blocks.minX),
                        Mth.floor(blocks.minZ),
                        Mth.floor(blocks.maxX),
                        Mth.floor(blocks.maxZ))) {
            this.discard();
            return;
        }
        ++this.age;
        HitResult hit = this.sweep(velocity, swept, blocks);
        if (hit != null && !EventHooks.onProjectileImpact(this, hit)) {
            this.setPos(hit.getLocation().add(0.0D, HITBOX_DROP - this.getBbHeight() * 0.5D, 0.0D));
            this.onHit(hit);
            this.sendVisual(GhastEliteTearPayload.BREAK);
            this.ended = true;
            this.discard();
            return;
        }
        this.setPos(this.position().add(velocity));
        this.setDeltaMovement(dampedVelocity(velocity));
    }

    /** 以物理体积扩张障碍，再用原版 AABB.clip 扫掠；按最先接触点选择方块或玩家。 */
    private HitResult sweep(Vec3 velocity, AABB swept, AABB blocks) {
        Vec3 start = this.position().add(0.0D, this.getBbHeight() * 0.5D - HITBOX_DROP, 0.0D);
        Vec3 end = start.add(velocity);
        HitResult nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        CollisionContext context = CollisionContext.of(this);
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(blocks.minX, blocks.minY, blocks.minZ),
                BlockPos.containing(blocks.maxX, blocks.maxY, blocks.maxZ))) {
            for (AABB box : this.level()
                    .getBlockState(pos)
                    .getCollisionShape(this.level(), pos, context)
                    .toAabbs()) {
                AABB expanded = this.expandTarget(box.move(pos));
                Optional<Vec3> contact = contact(expanded, start, end);
                if (contact.isPresent() && start.distanceToSqr(contact.get()) < distance) {
                    Vec3 point = contact.get();
                    distance = start.distanceToSqr(point);
                    nearest = new BlockHitResult(
                            point, contactFace(expanded, point), pos.immutable(), expanded.contains(start));
                }
            }
        }
        for (Entity target : this.level().getEntities(this, swept, this::canHitEntity)) {
            Optional<Vec3> contact = contact(this.expandTarget(target.getBoundingBox()), start, end);
            if (contact.isPresent() && start.distanceToSqr(contact.get()) < distance) {
                distance = start.distanceToSqr(contact.get());
                nearest = new EntityHitResult(target, contact.get());
            }
        }
        return nearest;
    }

    private AABB expandTarget(AABB box) {
        return box.inflate(this.getBbWidth() * 0.5D, this.getBbHeight() * 0.5D, this.getBbWidth() * 0.5D);
    }

    private static Optional<Vec3> contact(AABB box, Vec3 start, Vec3 end) {
        // AABB.clip 排除 t=0；已接触表面且继续向内运动时仍须立即命中。
        return box.contains(start) || box.contains(start.lerp(end, 1.0E-7D))
                ? Optional.of(start)
                : box.clip(start, end);
    }

    private static Direction contactFace(AABB box, Vec3 point) {
        Direction nearest = Direction.UP;
        double distance = Double.POSITIVE_INFINITY;
        for (Direction face : Direction.values()) {
            double separation =
                    switch (face) {
                        case DOWN -> Math.abs(point.y - box.minY);
                        case UP -> Math.abs(point.y - box.maxY);
                        case NORTH -> Math.abs(point.z - box.minZ);
                        case SOUTH -> Math.abs(point.z - box.maxZ);
                        case WEST -> Math.abs(point.x - box.minX);
                        case EAST -> Math.abs(point.x - box.maxX);
                    };
            if (separation < distance) {
                nearest = face;
                distance = separation;
            }
        }
        return nearest;
    }

    @Override
    protected AABB makeBoundingBox() {
        return super.makeBoundingBox().move(0.0D, -HITBOX_DROP, 0.0D);
    }

    @Override
    public boolean isPushedByFluid(FluidType type) {
        return false;
    }

    @Override
    protected void doWaterSplashEffect() {}

    @Override
    protected boolean canHitEntity(Entity entity) {
        return entity instanceof Player player
                && !player.isCreative()
                && !player.isSpectator()
                && super.canHitEntity(entity);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (!this.level().isClientSide && this.canHitEntity(result.getEntity())) {
            result.getEntity()
                    .hurt(
                            TFDamageTypes.getIndirectEntityDamageSource(
                                    this.level(),
                                    TFDamageTypes.GHAST_TEAR,
                                    this,
                                    this.getOwner(),
                                    TFEntities.UR_GHAST.get()),
                            DAMAGE);
        }
    }

    private void sendVisual(int kind) {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        GhastEliteTearPayload payload = new GhastEliteTearPayload(
                level.dimension(),
                this.getUUID(),
                this.getId(),
                kind,
                this.position(),
                this.getDeltaMovement(),
                this.age,
                this.lifetime,
                level.getGameTime());
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(this) <= VISUAL_RANGE * VISUAL_RANGE) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.isRemoved() && !this.ended) {
            this.sendVisual(GhastEliteTearPayload.END);
            this.ended = true;
        }
        super.remove(reason);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Age", this.age);
        tag.putInt("Lifetime", this.lifetime);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.lifetime = Mth.clamp(tag.getInt("Lifetime"), MIN_LIFETIME, MAX_LIFETIME);
        this.age = Mth.clamp(tag.getInt("Age"), 0, this.lifetime);
    }
}
