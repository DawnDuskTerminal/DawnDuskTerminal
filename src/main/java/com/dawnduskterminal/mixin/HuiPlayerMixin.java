package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.HuiArmor;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class HuiPlayerMixin {
    @Inject(method = "setItemSlot", at = @At("RETURN"))
    private void ddt$checkArmor(EquipmentSlot slot, ItemStack stack, CallbackInfo ci) {
        Player player = (Player) (Object) this;
        if (!player.level().isClientSide && slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR)
            HuiArmor.checkEquipment(player);
    }

    @ModifyReturnValue(method = "canEat", at = @At("RETURN"))
    private boolean ddt$allowRescueMeal(boolean allowed) {
        return allowed || HuiArmor.downed((Player) (Object) this);
    }

    @ModifyExpressionValue(
            method = "aiStep",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z",
                            ordinal = 0))
    private boolean ddt$replacePeacefulRecovery(boolean enabled) {
        return enabled && !HuiArmor.equipped((Player) (Object) this);
    }

    @WrapOperation(
            method = "actuallyHurt",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setHealth(F)V"))
    private void ddt$damageFood(Player player, float health, Operation<Void> original) {
        if (HuiArmor.downed(player)) HuiArmor.redirectDamage(player, player.getHealth() - health);
        else original.call(player, health);
    }

    @WrapOperation(
            method =
                    "eat(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/food/FoodProperties;)Lnet/minecraft/world/item/ItemStack;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/food/FoodData;eat(Lnet/minecraft/world/food/FoodProperties;)V"))
    private void ddt$meal(FoodData food, FoodProperties properties, Operation<Void> original) {
        float before = food.getSaturationLevel();
        original.call(food, properties);
        HuiArmor.afterMeal((Player) (Object) this, before);
    }
}
