package com.dawnduskterminal.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/**
 * 大恶魂的火球：<strong>不和其他弹体相撞</strong>、<strong>不做直接命中伤害</strong>、<strong>爆炸威力 2</strong>
 *
 * <p>写法照暮色森林的 {@code UrGhastFireball}：自己带一个威力常量，重写 {@code onHit}，
 * 先按命中类型派发（命中生物 / 命中方块 + {@code PROJECTILE_LAND} 游戏事件），再按自己的威力炸，
 * 最后丢弃。原版 {@code LargeFireball.onHit} 用的是它自己的 private 字段，没有 setter，
 * 因此不沿用父类那条路。
 *
 * <p>{@code canHitEntity} 不认弹体：一轮三发同 tick 出膛，原版会把彼此当成可命中的实体而互相引爆。
 *
 * <h2>没有直接命中伤害</h2>
 *
 * <p>原版 {@code LargeFireball.onHitEntity} 会先打 6 点，把目标的 {@code invulnerableTime} 点着，
 * 同一 tick 紧接着的爆炸伤害随即被 {@code LivingEntity#hurt} 拒绝——「打中人」就等于恒定 6 点、
 * 爆炸形同装饰。这里把 {@code onHitEntity} 空掉，伤害全部由爆炸结算。
 */
public class GhastEliteFireball extends LargeFireball {
    /** 爆炸威力：原版恶魂是 1，这里给 boss 翻一倍。公开给游戏测试钉住这个数 */
    public static final int EXPLOSION_POWER = 2;

    public GhastEliteFireball(EntityType<? extends GhastEliteFireball> type, Level level) {
        super(type, level);
    }

    /**
     * 与 {@code AbstractHurtingProjectile(EntityType, LivingEntity, Vec3, Level)} 同样的出手方式：
     * 方向归一化后按 {@code accelerationPower}（0.1）起飞，{@code hasImpulse} 置真，
     * 之后每 tick 自己加速——速度因此和原版恶魂、暮色恶魂的火球一致。
     */
    public GhastEliteFireball(Level level, LivingEntity owner, Vec3 direction) {
        super(com.dawnduskterminal.registry.ModEntities.GHAST_ELITE_FIREBALL.get(), level);
        this.setOwner(owner);
        this.setRot(owner.getYRot(), owner.getXRot());
        this.setDeltaMovement(direction.normalize().scale(this.accelerationPower));
        this.hasImpulse = true;
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return !(entity instanceof AbstractHurtingProjectile) && super.canHitEntity(entity);
    }

    /** 不做直接命中伤害，见类注释 */
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
                // MISS 不炸
            }
        }

        if (!this.level().isClientSide) {
            boolean grief = EventHooks.canEntityGrief(this.level(), this.getOwner());
            this.level()
                    .explode(
                            this,
                            this.getX(),
                            this.getY(),
                            this.getZ(),
                            EXPLOSION_POWER,
                            grief,
                            Level.ExplosionInteraction.MOB);
            this.discard();
        }
    }
}
