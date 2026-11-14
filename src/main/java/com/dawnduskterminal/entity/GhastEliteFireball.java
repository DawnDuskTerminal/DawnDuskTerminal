package com.dawnduskterminal.entity;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModEntities;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * 大恶魂的火球：<strong>不与其他弹体相撞</strong>、<strong>不造成直接命中伤害</strong>、<strong>爆炸威力为 2</strong>、
 * <strong>爆炸伤害不受目标受击冷却限制</strong>
 *
 * <p>实现方式参照暮色森林的 {@code UrGhastFireball}：本类自带威力常量并重写 {@code onHit}，
 * 先按命中类型分派（命中生物、命中方块并触发 {@code PROJECTILE_LAND} 游戏事件），
 * 再以自身威力执行爆炸，最后丢弃实体。
 *
 * <h2>为何自行结算爆炸伤害</h2>
 *
 * <p>原版爆炸的伤害经 {@code LivingEntity#hurt} 结算，目标处于受击冷却（10 tick）期间时该次伤害被直接拒绝。
 * 一轮三发在同一 tick 内出膛，因此仅第一发的爆炸生效，其余两发不产生伤害。
 *
 * <p>本实现保留原版爆炸的全部表现（破坏方块、粒子、音效、击退），但关闭其对实体的伤害：
 * 传入的 {@code ExplosionDamageCalculator} 令 {@code shouldDamageEntity} 恒返回 {@code false}；
 * 伤害改由 {@link #damageEntities} 按原版公式自行结算。
 * 所用伤害类型 {@code dawnduskterminal:ghast_elite_explosion} 列入 {@code minecraft:bypasses_cooldown} 标签，
 * 因此三发各自结算，互不吞并。
 *
 * <h2>为何不造成直接命中伤害</h2>
 *
 * <p>原版 {@code LargeFireball.onHitEntity} 先造成 6 点伤害，使目标的受击冷却开始计时，
 * 同一 tick 随后的爆炸伤害因此被拒绝。本类将 {@code onHitEntity} 实现为空，全部伤害交由爆炸结算。
 */
public class GhastEliteFireball extends LargeFireball {
    /** 爆炸威力：原版恶魂为 1，此处取两倍以匹配首领定位。公开以供游戏测试断言该数值 */
    public static final int EXPLOSION_POWER = 2;

    /** 列入 {@code minecraft:bypasses_cooldown} 标签，见 data/dawnduskterminal/damage_type/ghast_elite_explosion.json */
    public static final ResourceKey<DamageType> EXPLOSION_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, DawnDuskTerminal.id("ghast_elite_explosion"));

    /** 原版伤害公式 {@code (exposure² + exposure) / 2 * 7 * reach + 1} 中的系数 7 */
    private static final double DAMAGE_COEFFICIENT = 7.0D;

    /** 原版伤害公式中的基础伤害 1 */
    private static final double DAMAGE_BASE = 1.0D;

    /** 爆炸仍然破坏方块、仍然产生击退，只是不再自行结算对实体的伤害 */
    private static final ExplosionDamageCalculator NO_ENTITY_DAMAGE = new ExplosionDamageCalculator() {
        @Override
        public boolean shouldDamageEntity(Explosion explosion, Entity entity) {
            return false;
        }
    };

    public GhastEliteFireball(EntityType<? extends GhastEliteFireball> type, Level level) {
        super(type, level);
    }

    /**
     * 出膛方式与 {@code AbstractHurtingProjectile(EntityType, LivingEntity, Vec3, Level)} 一致：
     * 方向归一化后按 {@code accelerationPower}（0.1）起飞，{@code hasImpulse} 置为真，
     * 此后每 tick 自行加速，速度因此与原版恶魂、暮色恶魂的火球一致。
     */
    public GhastEliteFireball(Level level, LivingEntity owner, Vec3 direction) {
        super(ModEntities.GHAST_ELITE_FIREBALL.get(), level);
        this.setOwner(owner);
        this.setRot(owner.getYRot(), owner.getXRot());
        this.setDeltaMovement(direction.normalize().scale(this.accelerationPower));
        this.hasImpulse = true;
    }

    /** 不与其他弹体相撞：一轮齐射三颗，相互碰撞会在途中将彼此抵消 */
    @Override
    protected boolean canHitEntity(Entity entity) {
        return !(entity instanceof AbstractHurtingProjectile) && super.canHitEntity(entity);
    }

    /** 不造成直接命中伤害，理由见类注释 */
    @Override
    protected void onHitEntity(EntityHitResult result) {}

    @Override
    protected void onHit(HitResult result) {
        switch (result.getType()) {
            case ENTITY -> {
                this.onHitEntity((EntityHitResult) result);
                this.level()
                        .gameEvent(GameEvent.PROJECTILE_LAND, result.getLocation(), GameEvent.Context.of(this, null));
            }
            case BLOCK -> {
                BlockHitResult blockHit = (BlockHitResult) result;
                this.onHitBlock(blockHit);
                this.level()
                        .gameEvent(
                                GameEvent.PROJECTILE_LAND,
                                blockHit.getBlockPos(),
                                GameEvent.Context.of(this, this.level().getBlockState(blockHit.getBlockPos())));
            }
            default -> {
                // 未命中任何目标时不产生爆炸
                return;
            }
        }

        if (!this.level().isClientSide) {
            Level level = this.level();
            Vec3 center = this.position();
            DamageSource source = level.damageSources().source(EXPLOSION_DAMAGE, this, this.getOwner());
            boolean grief = EventHooks.canEntityGrief(level, this.getOwner());
            level.explode(
                    this,
                    source,
                    NO_ENTITY_DAMAGE,
                    center.x,
                    center.y,
                    center.z,
                    EXPLOSION_POWER,
                    grief,
                    Level.ExplosionInteraction.MOB);
            this.damageEntities(level, center, source);
            this.discard();
        }
    }

    /**
     * 按原版 {@code ExplosionDamageCalculator} 的公式结算一次伤害，所用伤害类型不受受击冷却限制
     *
     * <p>{@code reach = 威力 * 2}；{@code exposure = (1 - 距离 / reach) * 遮挡比}；
     * 伤害 {@code = (exposure² + exposure) / 2 * 7 * reach + 1}。
     * 遮挡比取原版 {@code Explosion#getSeenPercent}，与原版爆炸自身的取值完全一致。
     *
     * <p>与原版爆炸的两处差异，均为本实现的有意取舍：
     * <ul>
     *   <li>原版在 {@code exposure <= 0} 时仍结算 {@code DAMAGE_BASE} 点伤害，本实现跳过一次完全被遮挡的伤害；
     *   <li>原版逐实体调用 {@code Entity#ignoreExplosion} 过滤，本实现不调用：
     *       该判定需要 {@code Explosion} 实例，而 {@code Level#explode} 不返回该实例；
     *       受影响的仅有覆写了该判定的实体类型。
     * </ul>
     */
    private void damageEntities(Level level, Vec3 center, DamageSource source) {
        float reach = EXPLOSION_POWER * 2.0F;
        AABB area = new AABB(center, center).inflate(reach + 1.0D);
        for (Entity entity : level.getEntities(this, area)) {
            double ratio = Math.sqrt(entity.distanceToSqr(center)) / reach;
            double exposure = (1.0D - ratio) * Explosion.getSeenPercent(center, entity);
            if (exposure <= 0.0D) {
                continue;
            }
            float amount = (float) ((exposure * exposure + exposure) / 2.0D * DAMAGE_COEFFICIENT * reach + DAMAGE_BASE);
            entity.hurt(source, amount);
        }
    }
}
