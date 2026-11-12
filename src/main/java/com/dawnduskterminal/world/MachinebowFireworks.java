package com.dawnduskterminal.world;

import com.dawnduskterminal.item.FireworkMachineBowItem;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

/**
 * 火箭机弩打出去的火箭在爆炸时要补的那几条
 *
 * <p>原版火箭的爆炸伤害算在 {@code FireworkRocketEntity} 的私有 {@code dealExplosionDamage} 里，
 * 距离与视线都按原版那一套走，这里不碰：{@link LivingIncomingDamageEvent} 在伤害落地之前把
 * 星数、力量、火矢、无敌帧与冲击补上；冲击要削的那一次击退在伤害之后，另走
 * {@link LivingKnockBackEvent}。
 *
 * <ul>
 *   <li><b>烟火之心</b>：星数只影响伤害、不影响外观。原版这一下是按「外观一颗爆炸」算的
 *       {@code 5 + 2 × 1}，这里乘 {@code (5 + 2 × 星数) / 7} 换成星数对应的伤害，
 *       距离衰减与视线判定原样保留</li>
 *   <li><b>力量</b>：每级伤害乘 {@code 1 + 0.25 × 等级}</li>
 *   <li><b>火矢</b>：点着被爆炸波及的目标 5 秒，火焰免疫的除外</li>
 *   <li><b>无视无敌帧</b>：事件排在无敌帧判定之前，把计时清零之后这一下按满额进 {@code actuallyHurt}；
 *       多重射击三发同 tick 命中也是三发全额</li>
 *   <li><b>冲击</b>：反过来<b>减少</b>命中和爆炸的击退，每级减半，II 级起完全不击退。
 *       击退由 {@code LivingEntity#hurt} 里的 {@code knockback(0.4)} 施加，这一下在出手之后，
 *       所以出手前把目标登记进 {@link #PENDING_PUNCH}，由 {@link LivingKnockBackEvent} 按等级削掉</li>
 * </ul>
 *
 * <p>只认带标记的火箭：别的烟花（工作台拼的、别的弩打的）这一条都不碰
 */
public final class MachinebowFireworks {
    /** 这个字节标记了这一发是机关弩打出来的 */
    public static final String TAG = "DdtMachinebowRocket";

    /** 这一发的烟火之心数量：只参与伤害换算，外观固定一颗 */
    public static final String STARS = "DdtMachinebowStars";

    public static final String POWER = "DdtMachinebowPower";

    public static final String PUNCH = "DdtMachinebowPunch";

    public static final String FLAME = "DdtMachinebowFlame";

    /** 原版爆炸伤害公式的基础值，见 {@code FireworkRocketEntity#dealExplosionDamage} */
    public static final float EXPLOSION_BASE_DAMAGE = 5.0F;

    /** 原版每颗烟花之心给爆炸伤害加的点数 */
    public static final float DAMAGE_PER_STAR = 2.0F;

    /** 弹体外观只演一颗爆炸，换算星数伤害时的分母按它算 */
    public static final int VISUAL_EXPLOSIONS = 1;

    /** 冲击每级削掉的比例：I 级一半，II 级起完全 0 击退 */
    public static final float KNOCKBACK_REDUCTION_PER_PUNCH = 0.5F;

    /** 火矢点着的秒数，与原版箭的火焰附加一致 */
    public static final float FLAME_SECONDS = 5.0F;

    /**
     * 出手到击退之间要传的那一份「冲击等级」
     *
     * <p>只活在一次 {@code hurt} 里：出手前放进去，{@link LivingKnockBackEvent} 里取走；
     * 带一个游戏刻，伤害没落地（被别的模组取消、或者没有击退事件）留下的旧条目在下一拍
     * 自然失效，不会误削别的击退
     */
    private static final Map<LivingEntity, PendingPunch> PENDING_PUNCH = new WeakHashMap<>();

    private MachinebowFireworks() {}

    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (!(target.level() instanceof ServerLevel level)) {
            return;
        }
        DamageSource source = event.getSource();
        if (!source.is(DamageTypes.FIREWORKS)) {
            return;
        }
        if (!(source.getDirectEntity() instanceof FireworkRocketEntity rocket)) {
            return;
        }
        CompoundTag data = rocket.getPersistentData();
        if (!data.getBoolean(TAG)) {
            return;
        }

        // 无视受击无敌帧：这一句必须留在 setAmount 之前，后面那次 actuallyHurt 才拿得到满额
        target.invulnerableTime = 0;

        // 星数换算：原版这一下是按「外观一颗爆炸」算的 5 + 2 × 1，换成 5 + 2 × 星数
        int stars = data.getInt(STARS);
        if (stars > 0) {
            float visual = EXPLOSION_BASE_DAMAGE + DAMAGE_PER_STAR * VISUAL_EXPLOSIONS;
            event.setAmount(event.getAmount() * (EXPLOSION_BASE_DAMAGE + DAMAGE_PER_STAR * stars) / visual);
        }

        int power = data.getInt(POWER);
        if (power > 0) {
            event.setAmount(event.getAmount() * (1.0F + FireworkMachineBowItem.POWER_DAMAGE_PER_LEVEL * power));
        }

        int flame = data.getInt(FLAME);
        if (flame > 0 && !target.fireImmune()) {
            target.igniteForSeconds(FLAME_SECONDS);
        }

        int punch = data.getInt(PUNCH);
        if (punch > 0) {
            PENDING_PUNCH.put(target, new PendingPunch(punch, level.getGameTime()));
        }
    }

    /** 冲击削击退：I 级减半，II 级起 0 击退 */
    public static void onLivingKnockBack(LivingKnockBackEvent event) {
        PendingPunch pending = PENDING_PUNCH.remove(event.getEntity());
        if (pending == null
                || pending.punch() <= 0
                || pending.tick() != event.getEntity().level().getGameTime()) {
            return;
        }
        float reduction = Math.min(1.0F, KNOCKBACK_REDUCTION_PER_PUNCH * pending.punch());
        event.setStrength(event.getStrength() * (1.0F - reduction));
    }

    /** 一次出手要传给击退的那一份数据：冲击等级与它所属的游戏刻 */
    private record PendingPunch(int punch, long tick) {}
}
