package com.dawnduskterminal.world;

import com.dawnduskterminal.registry.ModDamageTypes;
import com.dawnduskterminal.registry.ModSounds;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * 复方侵蚀（Compound Erosion）：喝下之后每 2 秒挨一下不稳定液体的接触伤害，3 点
 *
 * <p>伤害类型与泡在 {@code portal_fluid} 里那一下是同一个（{@link ModDamageTypes#STABILITY}），
 * 音效也是 {@code portal_hurt}，所以死亡信息、免疫与音色都与「站在液体里挨打」一致。
 * 节拍照 {@link PortalSicknessEffect}：按剩余时长取 40 的倍数，也就是从喝下那一刻起每 2 秒一次；
 * 扣血被挡下（免疫、创造模式、无敌帧）时不播声音 —— 与 {@code PortalBlock#entityInside} 一样，
 * 声音只跟着真的落下去的那一下
 *
 * <p>颜色与「稳定」完全一样（{@code 0x7F159E}）：药水颜色是可见效果的加权平均，
 * 两瓶只有这一条效果时颜色也一模一样，光看瓶子分不出来，喝下去才知道
 */
public class CompoundErosionEffect extends MobEffect {
    /** 每一下的伤害 */
    public static final float DAMAGE = 3.0F;

    /** 每两下之间隔多少 tick */
    public static final int INTERVAL_TICKS = 40;

    public CompoundErosionEffect() {
        super(MobEffectCategory.HARMFUL, 0x7F159E);
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        if (entity.hurt(entity.damageSources().source(ModDamageTypes.STABILITY), DAMAGE)) {
            entity.level()
                    .playSound(
                            null,
                            entity.getX(),
                            entity.getY(),
                            entity.getZ(),
                            ModSounds.PORTAL_HURT.get(),
                            entity.getSoundSource(),
                            1.0F,
                            0.8F + entity.getRandom().nextFloat() * 0.4F);
        }
        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % INTERVAL_TICKS == 0;
    }
}
