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
import net.minecraft.world.entity.LivingEntity;
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
import twilightforest.entity.monster.CarminiteGhastling;
import twilightforest.init.TFDamageTypes;
import twilightforest.init.TFEntities;

/** 大恶魂的服务端泪滴；客户端粒子只显示状态，不参与伤害或碰撞判定。 */
public final class GhastEliteTear extends Projectile {
    public static final int MIN_LIFETIME = 60;
    public static final int MAX_LIFETIME = 99;
    public static final float DAMAGE = 3.0F;
    public static final double VISUAL_RANGE = 512.0D;

    /**
     * 受击目标在物理体积之外的额外横向外扩量
     *
     * <p>物理体积仅 0.8 格见方，与视觉粒子的大小无关；只按该体积判定时，站位偏离不足一格即可免受伤害，
     * 泪雨因此失去威胁。此处仅放宽水平方向：竖直方向仍取物理体积的一半，
     * 保持「自上方落到身上才算命中」的语义，方块遮挡判定亦不受影响。
     *
     * <p>以宽 0.6 格的玩家为例，判定柱体宽度为 {@code 0.6 + 0.8 + 1.2 = 2.6} 格，是原判定 {@code 1.4} 格的近 3.4 倍面积。
     */
    public static final double ENTITY_HIT_MARGIN = 0.6D;

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

    /** 以物理体积扩张障碍、并额外放宽受击目标判定，再用原版 AABB.clip 扫掠；按最先接触点选择方块或目标。 */
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
                AABB expanded = this.expandBlockShape(box.move(pos));
                Optional<Vec3> contact = contact(expanded, start, end);
                if (contact.isPresent() && start.distanceToSqr(contact.get()) < distance) {
                    Vec3 point = contact.get();
                    distance = start.distanceToSqr(point);
                    nearest = new BlockHitResult(
                            point, contactFace(expanded, point), pos.immutable(), expanded.contains(start));
                }
            }
        }
        AABB targets = swept.inflate(ENTITY_HIT_MARGIN, 0.0D, ENTITY_HIT_MARGIN);
        for (Entity target : this.level().getEntities(this, targets, this::canHitEntity)) {
            Optional<Vec3> contact = contact(this.expandEntityBox(target.getBoundingBox()), start, end);
            if (contact.isPresent() && start.distanceToSqr(contact.get()) < distance) {
                distance = start.distanceToSqr(contact.get());
                nearest = new EntityHitResult(target, contact.get());
            }
        }
        return nearest;
    }

    /** 方块的判定体积：物理碰撞箱的一半，与外观以外的行为无关。 */
    private AABB expandBlockShape(AABB box) {
        return box.inflate(this.getBbWidth() * 0.5D, this.getBbHeight() * 0.5D, this.getBbWidth() * 0.5D);
    }

    /** 受击目标的判定体积：水平方向在物理体积之外再放宽 {@link #ENTITY_HIT_MARGIN} 格，竖直方向不放宽。 */
    private AABB expandEntityBox(AABB box) {
        double horizontal = this.getBbWidth() * 0.5D + ENTITY_HIT_MARGIN;
        return box.inflate(horizontal, this.getBbHeight() * 0.5D, horizontal);
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

    /**
     * 泪滴可命中的对象：所有者自身与其小鬼以外的全部生物
     *
     * <p>小鬼指暮色森林的 {@code carminite_ghastling}，以及任何带有
     * {@link GhastEliteEntity#MINION_TAG} 标记的实体；二者与本体一样不受泪滴伤害。
     * 创造与旁观玩家不产生伤害，因此也不拦下泪滴。
     *
     * <p>原版 {@code Projectile#canHitEntity} 只排除与所有者同乘载具的目标，不排除所有者本体，
     * 故此处必须显式排除。
     */
    @Override
    protected boolean canHitEntity(Entity entity) {
        if (!super.canHitEntity(entity)) {
            return false;
        }
        if (entity == this.getOwner()
                || entity instanceof CarminiteGhastling
                || entity.getPersistentData().getBoolean(GhastEliteEntity.MINION_TAG)) {
            return false;
        }
        if (entity instanceof Player player) {
            return !player.isCreative() && !player.isSpectator();
        }
        return entity instanceof LivingEntity;
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
