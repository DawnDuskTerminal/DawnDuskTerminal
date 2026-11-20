package com.dawnduskterminal.mixin;

import com.dawnduskterminal.client.PortalWaterfallMesh;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class PortalWaterfallClientUpdateMixin {
    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void ddt$rebuildWaterfallSections(
            BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        PortalWaterfallMesh.dirtyIfChanged((ClientLevel) (Object) this, pos, oldState, newState);
    }
}
