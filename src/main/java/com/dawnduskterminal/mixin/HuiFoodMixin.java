package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.HuiArmor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FoodData.class)
abstract class HuiFoodMixin {
    @Shadow
    private int tickTimer;

    /** 原版活动消耗已结算，后续自然恢复和饥饿伤害由套装接管。 */
    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;getGameRules()Lnet/minecraft/world/level/GameRules;"),
            cancellable = true)
    private void ddt$replaceRecovery(Player player, CallbackInfo ci) {
        if (HuiArmor.equipped(player) || HuiArmor.downed(player)) {
            tickTimer = 0;
            ci.cancel();
        }
    }
}
