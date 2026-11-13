package com.dawnduskterminal.entity;

import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModFluids;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.world.TransformationBlast;
import com.dawnduskterminal.world.TransformationConversion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * 两瓶转换粉的投掷物
 *
 * <p>两种粉共用一个类，方向由实体类型决定（{@link #reversed()}）：注册成两条
 * {@code EntityType}，一条走强效、一条走反转。这样客户端渲染器只要认实体类型就能画出正确的那张图标，
 * 不必再同步一个字段
 *
 * <p>物理照原版投掷药水：继承 {@link ThrowableItemProjectile}，重力取 0.05
 * （{@code ThrownPotion#getDefaultGravity} 就是 0.05，喷溅药水那条浅浅的抛物线），空气阻力 0.99 由父类给，
 * 出手速度与仰角也照 {@code ThrowablePotionItem#use} 那一行 {@code shootFromRotation(..., -20°, 0.5, 1.0)}
 *
 * <p>命中就炸：{@link TransformationBlast#detonate} 做完转换与粒子，然后自己消失。
 * 不伤害任何实体——打中谁都是把谁换掉，不是打他
 *
 * <p>「命中」有两条：撞到方块或活物走父类那条，**碰到液体**走 {@link #tick()} 里补的那一条。
 * 液体没有碰撞箱，{@code ProjectileUtil#getHitResultOnMoveVector} 看不见它，射进水里会一路穿到底，
 * 于是落点变成了水底而不是水面——所以每 tick 自己看一眼脚下这一格的流体状态，非空就算落地。
 *
 * <p>但**自己那一侧的液体不算**：强效那瓶穿过水，反转那瓶穿过不稳定液体。否则往自己刚造出来的
 * 水里再扔一瓶就会立刻炸在半空，而它本来就是要把那一片水换成别的东西
 */
public class TransformationPowderEntity extends ThrowableItemProjectile {
    /** 作用半径，与 {@link TransformationConversion#RADIUS} 是同一个数 */
    public static final double SPLASH_RANGE = TransformationConversion.RADIUS;

    /** 投掷药水的重力 */
    private static final double POTION_GRAVITY = 0.05D;

    public TransformationPowderEntity(EntityType<? extends TransformationPowderEntity> type, Level level) {
        super(type, level);
    }

    public TransformationPowderEntity(
            EntityType<? extends TransformationPowderEntity> type, LivingEntity owner, Level level) {
        super(type, owner, level);
    }

    /** 这一颗是反转那瓶 */
    public boolean reversed() {
        return this.getType() == ModEntities.REVERSED_TRANSFORMATION_POWDER.get();
    }

    @Override
    protected Item getDefaultItem() {
        return this.reversed()
                ? ModItems.REVERSED_TRANSFORMATION_POWDER.get()
                : ModItems.POTENT_TRANSFORMATION_POWDER.get();
    }

    @Override
    protected double getDefaultGravity() {
        return POTION_GRAVITY;
    }

    /**
     * 碰到的这一格液体算不算落点
     *
     * <p>空的那一格当然不算（还没落地）；自己那一侧的液体也不算——强效那瓶造出来的就是水，
     * 反转那瓶造出来的就是不稳定液体，穿过其中任何一样都是正常的飞行，不是命中。
     * 流体类型同时覆盖源与流动两种，所以不必再判 {@code getFluidState().isSource()}
     */
    private boolean isLandingFluid(ServerLevel level) {
        FluidState fluid = level.getFluidState(this.blockPosition());
        if (fluid.isEmpty()) {
            return false;
        }
        FluidType own = this.reversed() ? ModFluids.PORTAL_FLUID_TYPE.get() : NeoForgeMod.WATER_TYPE.value();
        return fluid.getFluidType() != own;
    }

    /**
     * 碰到液体也算落点（自己那一侧的液体除外）
     *
     * <p>父类那一套只看方块碰撞与活物，液体两样都不是，所以这里每 tick 自己查一次脚下这一格的流体；
     * 一旦算作落点就在当前位置炸开（不是水面以下更深处），与撞上方块的处理完全一样
     */
    @Override
    public void tick() {
        super.tick();
        if (this.isRemoved() || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        if (!this.isLandingFluid(server)) {
            return;
        }
        TransformationBlast.detonate(server, this.position(), this.reversed());
        this.discard();
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (this.level() instanceof ServerLevel server && !this.isRemoved()) {
            TransformationBlast.detonate(server, this.position(), this.reversed());
            this.discard();
        }
    }
}
