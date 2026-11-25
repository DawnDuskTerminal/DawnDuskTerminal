package com.dawnduskterminal.mixin;

import com.dawnduskterminal.registry.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.block.TFPortalBlock;

@Mixin(TFPortalBlock.class)
public abstract class TwilightPortalCatalystMixin {
    // 暮色森林允许跨维度开门时，晨昏线水池中的钻石仍归本模组处理。
    @Inject(method = "tryToCreatePortal", at = @At("HEAD"), cancellable = true, remap = false)
    private void ddt$reserveChronoDiamond(
            Level level, BlockPos pos, ItemEntity item, Player player, CallbackInfoReturnable<Boolean> result) {
        if (level.dimension().equals(ModDimensions.CHRONO) && item.getItem().is(Items.DIAMOND)) {
            result.setReturnValue(false);
        }
    }
}
