package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.HuiArmor;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class HuiHudMixin {
    @ModifyExpressionValue(
            method = "renderHealthLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getHealth()F"))
    private float ddt$redHearts(float health, @Local Player player) {
        return HuiArmor.downed(player) ? 0 : health;
    }

    @ModifyExpressionValue(
            method = "renderHealthLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getAbsorptionAmount()F"))
    private float ddt$goldHearts(float absorption, @Local Player player) {
        return absorption + HuiArmor.visibleShield(player);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void ddt$downedTint(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        Player player = Minecraft.getInstance().player;
        if (player != null && HuiArmor.downed(player))
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0x24A00000);
    }
}
