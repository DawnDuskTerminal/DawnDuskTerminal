package com.dawnduskterminal.mixin;

import com.dawnduskterminal.block.Experiment114Block;
import com.dawnduskterminal.block.SusCakeBlock;
import com.dawnduskterminal.world.HuiArmor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import twilightforest.block.Experiment115Block;

@Mixin({Experiment115Block.class, Experiment114Block.class, SusCakeBlock.class})
abstract class HuiSliceMixin {
    @WrapOperation(
            method = "useWithoutItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;eat(IF)V"))
    private void ddt$eatSlice(
            FoodData food,
            int nutrition,
            float saturation,
            Operation<Void> original,
            @Local(argsOnly = true) Player player) {
        float before = food.getSaturationLevel();
        original.call(food, nutrition, saturation);
        HuiArmor.afterMeal(player, before);
    }
}
