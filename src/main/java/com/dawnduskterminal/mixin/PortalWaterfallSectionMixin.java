package com.dawnduskterminal.mixin;

import com.dawnduskterminal.client.PortalWaterfallMesh;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.List;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent.AdditionalSectionRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(SectionCompiler.class)
public abstract class PortalWaterfallSectionMixin {
    @WrapMethod(
            method =
                    "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;")
    private SectionCompiler.Results ddt$joinWaterfallAndFluidMeshes(
            SectionPos section,
            RenderChunkRegion region,
            VertexSorting sorting,
            SectionBufferBuilderPack pack,
            List<AdditionalSectionRenderer> additions,
            Operation<SectionCompiler.Results> original) {
        for (var addition : additions) {
            if (addition instanceof PortalWaterfallMesh waterfall) {
                var previous = PortalWaterfallMesh.activeView();
                PortalWaterfallMesh.setActiveView(waterfall.view(region));
                try {
                    return original.call(section, region, sorting, pack, additions);
                } finally {
                    PortalWaterfallMesh.setActiveView(previous);
                }
            }
        }
        return original.call(section, region, sorting, pack, additions);
    }
}
