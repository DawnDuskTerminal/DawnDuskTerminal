package com.dawnduskterminal.mixin;

import com.dawnduskterminal.client.PortalWaterfallMesh;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(BlockRenderDispatcher.class)
public abstract class PortalWaterfallLiquidViewMixin {
    @ModifyVariable(method = "renderLiquid", at = @At("HEAD"), argsOnly = true)
    private BlockAndTintGetter ddt$waterfallNeighbors(BlockAndTintGetter region) {
        var view = PortalWaterfallMesh.activeView();
        return view == null ? region : view;
    }
}
