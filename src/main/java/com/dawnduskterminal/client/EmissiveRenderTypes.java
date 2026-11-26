package com.dawnduskterminal.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * 本模组实体用的自发光半透明渲染层：一张贴图一个类型，第一次用到时配好并缓存
 *
 * <p>配方与 {@code entityTranslucent} 相同，只换三处：自发光着色器
 * （{@code RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER}）、不挂光图（{@code NO_LIGHTMAP}）、
 * 写掩码保留默认的 {@code COLOR_DEPTH_WRITE}。贴图不受天光与方块光压暗，全身一直亮。
 *
 * <p><b>为什么不用原版的 {@code RenderType.entityTranslucentEmissive}</b>：那个类型的写掩码是
 * {@code COLOR_WRITE}，<strong>不写深度</strong>。1.21 的绘制顺序是「实体 → 半透明区块层（流体就这一层）
 * → 云」，流体与云都在实体之后画、按深度测试混合；实体一旦不进深度缓冲，这两层就会整片盖在它身上，
 * 看起来就是被流体和天上的云挡在后面。
 *
 * <p>弦与虚像共用这一份定义：前者的贴图是流体方块的竖条，后者是同一张 {@code portal_fluid_flow}，
 * 两者的 alpha 都低于 255，落进 {@code cutout} 会被当成不透明。
 */
public final class EmissiveRenderTypes {
    private static final Map<ResourceLocation, RenderType> TYPES = new ConcurrentHashMap<>();

    private EmissiveRenderTypes() {}

    public static RenderType of(ResourceLocation texture) {
        return TYPES.computeIfAbsent(
                texture,
                location -> RenderType.create(
                        "ddt_emissive/" + location,
                        DefaultVertexFormat.NEW_ENTITY,
                        VertexFormat.Mode.QUADS,
                        1536,
                        true,
                        true,
                        RenderType.CompositeState.builder()
                                .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER)
                                .setTextureState(new RenderStateShard.TextureStateShard(location, false, false))
                                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                                .setCullState(RenderStateShard.NO_CULL)
                                .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                                .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                                .setOverlayState(RenderStateShard.OVERLAY)
                                .createCompositeState(true)));
    }
}
