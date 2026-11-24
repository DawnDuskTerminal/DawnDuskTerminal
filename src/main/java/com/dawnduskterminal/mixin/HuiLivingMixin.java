package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.HuiArmor;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class HuiLivingMixin {
    @WrapMethod(method = "hurt")
    private boolean ddt$trackArmor(DamageSource source, float amount, Operation<Boolean> original) {
        if (!((Object) this instanceof Player player)
                || player.level().isClientSide
                || !(HuiArmor.equipped(player) || HuiArmor.downed(player) || HuiArmor.armoredHit(player))) {
            return original.call(source, amount);
        }
        boolean previous = HuiArmor.armoredHit(player);
        HuiArmor.setArmoredHit(player, HuiArmor.equipped(player));
        try {
            boolean result = original.call(source, amount);
            HuiArmor.settleDowned(player);
            return result;
        } finally {
            HuiArmor.setArmoredHit(player, previous);
        }
    }

    @ModifyReturnValue(method = "checkTotemDeathProtection", at = @At("RETURN"))
    private boolean ddt$delayDeath(boolean protectedByTotem) {
        if (!protectedByTotem && (Object) this instanceof Player player && HuiArmor.armoredHit(player)) {
            HuiArmor.enterDowned(player);
            return true;
        }
        return protectedByTotem;
    }

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void ddt$lockHealth(float amount, CallbackInfo ci) {
        if ((Object) this instanceof Player player
                && !player.level().isClientSide
                && (HuiArmor.downed(player) || (amount <= 0 && HuiArmor.delayDirectHealthLoss(player)))) ci.cancel();
    }
}
