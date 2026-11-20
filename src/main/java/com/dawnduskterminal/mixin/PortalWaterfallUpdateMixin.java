package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.PortalWaterfallColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class PortalWaterfallUpdateMixin {
    @Inject(method = "onBlockStateChange", at = @At("TAIL"))
    private void ddt$refreshWaterfalls(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        PortalWaterfallColumns.blockChanged((ServerLevel) (Object) this, pos, oldState, newState);
    }
}
