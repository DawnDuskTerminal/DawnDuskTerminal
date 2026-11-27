package com.dawnduskterminal.entity;

import com.dawnduskterminal.registry.ModDamageTypes;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.world.ChronoLineState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 鬼花发射的射弹
 *
 * <p>无重力、匀速直线，沿途撒粒子（昼版 {@code spore_blossom_air}、夜版 {@code enchanted_hit}）。
 * 命中位置按它离晨昏线的远近折算伤害：晨昏线上 10 点，靠近本方一侧升高、靠近对方一侧降低，
 * 详见 {@link #damageAt}。命中还播植物破坏音。
 * 外观由 {@code GhoflowerProjectileRenderer} 画成一个面向镜头的四边形，贴图单独切出、不进方块图集。
 *
 * <p>原版 {@link ThrowableProjectile#tick()} 每 tick 会乘 0.99 的阻力并施加重力，
 * 所以这里在 {@code super.tick()} 之后把速度原样写回去，位移才是一条直线。
 */
public class GhoflowerProjectile extends ThrowableProjectile {
    /** 晨昏线上的命中伤害，昼、夜两版都是这个值 */
    public static final float TERMINATOR_DAMAGE = 10.0F;

    /** 朝本方一侧每单位晨昏线参数增加的伤害 */
    public static final float HOME_SIDE_GAIN = 20.0F;

    /** 朝对方一侧每单位晨昏线参数减少的伤害 */
    public static final float AWAY_SIDE_LOSS = 10.0F;

    /** 初速度，格/tick */
    public static final double SPEED = 0.8D;

    /** 最长存活 tick 数，防止无重力时无限飞 */
    public static final int LIFETIME_TICKS = 100;

    public GhoflowerProjectile(EntityType<? extends GhoflowerProjectile> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
    }

    public GhoflowerProjectile(
            EntityType<? extends GhoflowerProjectile> type, double x, double y, double z, Level level) {
        super(type, x, y, z, level);
        this.setNoGravity(true);
    }

    /** 从 {@code from} 朝 {@code to} 发射一枚，宿主记成 {@code owner} */
    public static GhoflowerProjectile spawn(ServerLevel level, Vec3 from, Vec3 to, LivingEntity owner, boolean night) {
        EntityType<? extends GhoflowerProjectile> type =
                night ? ModEntities.GHOFLOWER_PROJECTILE_NIGHT.get() : ModEntities.GHOFLOWER_PROJECTILE.get();
        // 起点沿方向让开半格：武器从眼睛发射时，射弹会在第一 tick 撞上发射者自己
        Vec3 direction = to.subtract(from);
        Vec3 origin =
                direction.lengthSqr() > 1.0E-6D ? from.add(direction.normalize().scale(0.5D)) : from;
        GhoflowerProjectile projectile = new GhoflowerProjectile(type, origin.x, origin.y, origin.z, level);
        projectile.setOwner(owner);
        if (direction.lengthSqr() > 1.0E-6D) {
            Vec3 unit = direction.normalize();
            projectile.shoot(unit.x, unit.y, unit.z, (float) SPEED, 0.0F);
        }
        level.addFreshEntity(projectile);
        return projectile;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    /** 这一枚是不是夜版：夜版用夜贴图与附魔命中粒子 */
    public boolean isNightVariant() {
        return this.getType() == ModEntities.GHOFLOWER_PROJECTILE_NIGHT.get();
    }

    @Override
    public void tick() {
        if (this.isRemoved()) {
            return;
        }
        Vec3 velocity = this.getDeltaMovement();
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(
                    this.isNightVariant() ? ParticleTypes.ENCHANTED_HIT : ParticleTypes.SPORE_BLOSSOM_AIR,
                    this.getX(),
                    this.getY(),
                    this.getZ(),
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D);
        }
        super.tick();
        this.setDeltaMovement(velocity);
        if (!this.level().isClientSide && this.tickCount > LIFETIME_TICKS) {
            this.discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level() instanceof ServerLevel server) {
            if (result.getEntity() instanceof LivingEntity living) {
                living.hurt(this.damageSource(server), this.hitDamage());
            }
            playHitSound(server, this.blockPosition());
        }
        if (!this.level().isClientSide) {
            this.discard();
        }
    }

    /**
     * 命中伤害：昼版射弹越靠昼侧越高，夜版越靠夜侧越高。
     *
     * <p>{@code param} 是晨昏线参数，晨昏线上为 0、昼侧端点为 1、夜侧端点为 -1。
     * 两种射弹在晨昏线上都是 {@value #TERMINATOR_DAMAGE} 点；朝本方一侧每单位参数加
     * {@value #HOME_SIDE_GAIN}，到端点升到 30 点；朝对方一侧每单位参数减
     * {@value #AWAY_SIDE_LOSS}，到端点降到 0 点
     */
    public static float damageAt(boolean night, double param) {
        double homeward = Mth.clamp(night ? -param : param, -1.0D, 1.0D);
        double damage = homeward >= 0.0D
                ? TERMINATOR_DAMAGE + HOME_SIDE_GAIN * homeward
                : TERMINATOR_DAMAGE + AWAY_SIDE_LOSS * homeward;
        return (float) Mth.clamp(damage, 0.0D, (double) TERMINATOR_DAMAGE + HOME_SIDE_GAIN);
    }

    /** 命中点折算的伤害：参数取自服务端的晨昏线 */
    private float hitDamage() {
        return damageAt(this.isNightVariant(), ChronoLineState.serverLine().param(this.getX(), this.getZ()));
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (this.level() instanceof ServerLevel server) {
            playHitSound(server, result.getBlockPos());
        }
        if (!this.level().isClientSide) {
            this.discard();
        }
    }

    private static void playHitSound(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.GRASS_BREAK, SoundSource.HOSTILE, 1.0F, 1.0F);
    }

    /** 三条死亡信息随机取一条 */
    private DamageSource damageSource(ServerLevel level) {
        ResourceKey<DamageType> key =
                switch (this.random.nextInt(3)) {
                    case 0 -> ModDamageTypes.GHOFLOWER_FERTILISER;
                    case 1 -> ModDamageTypes.GHOFLOWER_BEAUTY;
                    default -> ModDamageTypes.GHOFLOWER_GHOST;
                };
        return level.damageSources().source(key, this, this.getOwner());
    }
}
