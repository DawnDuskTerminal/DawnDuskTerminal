package com.dawnduskterminal.mixin;

import com.dawnduskterminal.client.PortalWaterfallMesh;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LiquidBlockRenderer.class)
public abstract class PortalWaterfallSurfaceMixin {
    @WrapOperation(
            method = "tesselate",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/neoforged/neoforge/client/textures/FluidSpriteCache;getFluidSprites(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;)[Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite[] ddt$waterfallFlowingSprite(
            BlockAndTintGetter level, BlockPos pos, FluidState fluid, Operation<TextureAtlasSprite[]> original) {
        TextureAtlasSprite[] sprites = original.call(level, pos, fluid);
        if (level instanceof PortalWaterfallMesh.RenderView view && view.usesPortalWaterfallSprite(pos)) {
            return PortalWaterfallMesh.waterfallSprites(sprites);
        }
        return sprites;
    }
}
