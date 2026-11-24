package com.dawnduskterminal.mixin;

import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.world.ChronoTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 保持晨昏线底层净空，避免含水层在加载后重新灌入该层。 */
@Mixin(FlowingFluid.class)
public abstract class ChronoBottomFluidMixin {
    @Inject(method = "canSpreadTo", at = @At("HEAD"), cancellable = true)
    private void ddt$keepBottomDry(
            BlockGetter view,
            BlockPos start,
            BlockState from,
            Direction direction,
            BlockPos target,
            BlockState to,
            FluidState existing,
            Fluid incoming,
            CallbackInfoReturnable<Boolean> cir) {
        if (target.getY() <= ChronoTerrain.BOTTOM_Y
                && view instanceof Level level
                && level.dimension().equals(ModDimensions.CHRONO)) {
            cir.setReturnValue(false);
        }
    }
}
