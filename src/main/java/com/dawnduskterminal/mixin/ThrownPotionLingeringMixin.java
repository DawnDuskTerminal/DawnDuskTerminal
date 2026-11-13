package com.dawnduskterminal.mixin;

import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.LingeringPotionItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 滞留型稳定药水落地之后要真的留一片药水云
 *
 * <p>原版 {@code ThrownPotion#isLingering} 写死比 {@code Items.LINGERING_POTION} 这件物品本身，
 * 于是模组自己那瓶滞留药水扔出去只会按喷溅结算：一圈效果，没有药水云，与名字对不上。
 * 这里把判据放宽成「物品是 {@link LingeringPotionItem}」——
 * 原版那两瓶的行为不变，别的模组自建的滞留瓶也顺带修好
 */
@Mixin(ThrownPotion.class)
public abstract class ThrownPotionLingeringMixin {
    @Inject(method = "isLingering", at = @At("HEAD"), cancellable = true)
    private void ddt$acceptModLingeringPotion(CallbackInfoReturnable<Boolean> cir) {
        // getItem 声明在父类 ThrowableItemProjectile 上，@Shadow 不了，只能把 this 转过去取
        if (((ThrowableItemProjectile) (Object) this).getItem().getItem() instanceof LingeringPotionItem) {
            cir.setReturnValue(true);
        }
    }
}
