package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.client.animation.AnimatedStripTexture;
import com.dawnduskterminal.client.animation.YsmAnimatable;
import com.dawnduskterminal.client.animation.YsmEntityRenderer;
import com.dawnduskterminal.entity.StringEntity;
import com.dawnduskterminal.geckolib3.core.event.predicate.AnimationEvent;
import com.dawnduskterminal.geckolib3.core.util.Color;
import com.dawnduskterminal.registry.ModEntities;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 「弦」的客户端接线
 *
 * <p>小型与大型是两个不同的 geo 模型，贴图直接指向流体方块的两张图：
 * {@code textures/block/portal_fluid_still.png} 与 {@code textures/block/portal_fluid_flow.png}。
 * geo 里的 texture_width/height 是单帧尺寸 16x16 与 32x32。
 *
 * <p>姿态完全由模型作者的两条动画文件驱动（{@code animations/string.animation.json} 与
 * {@code animations/string_large.animation.json}），两条片段 {@code parallel0} / {@code parallel1}
 * 由并行动画控制器循环播放，里面用的 {@code ysm.bone_pivot_abs}、{@code ysm.perlin_noise}、
 * {@code math.random} 都是运行时真算的，不做任何替换。
 *
 * <p>这两张贴图是 32 帧的竖条，由 {@link AnimatedStripTexture} 按 {@code .mcmeta} 逐帧上传。
 *
 * <p>渲染类型用半透明的自发光实体层：流体贴图的 alpha 是 230/255，默认的 cutout 会把它当成不透明；
 * 自发光让整张贴图不受天光与方块光压暗，全身一直亮。
 *
 * <p><b>为什么不用原版的 {@code RenderType.entityTranslucentEmissive}</b>：那个类型的写掩码是
 * {@code COLOR_WRITE}，<strong>不写深度</strong>。1.21 的绘制顺序是「实体 → 半透明区块层（流体就这一层）
 * → 云」，流体与云都在实体之后画、按深度测试混合；实体一旦不进深度缓冲，这两层就会整片盖在它身上，
 * 看起来就是被流体和天上的云挡在后面。这里按 {@code entityTranslucent} 的配方自己配一个：
 * 自发光 shader、不挂光图、保留半透明，写掩码用默认的 {@code COLOR_DEPTH_WRITE}。
 *
 * <p>模型里没有死亡片段，死亡时姿态层照旧按动画摆动，只是不再位移。
 */
public final class StringRenderer {
    /** 每种贴图一个类型（小型与大型各一张），第一次用时配好缓存住 */
    private static final Map<ResourceLocation, RenderType> GLOW_TYPES = new ConcurrentHashMap<>();

    private static final ResourceLocation SMALL_GEO = DawnDuskTerminal.id("geo/string.geo.json");
    private static final ResourceLocation LARGE_GEO = DawnDuskTerminal.id("geo/string_large.geo.json");
    private static final ResourceLocation SMALL_TEXTURE = DawnDuskTerminal.id("textures/block/portal_fluid_still.png");
    private static final ResourceLocation LARGE_TEXTURE = DawnDuskTerminal.id("textures/block/portal_fluid_flow.png");
    /**
     * 小型与大型各有一条动画条文件，内容就是模型作者的原件（{@code parallel0} 是弦环的朝向解算，
     * {@code parallel1} 是辐条摆动与本体自转）
     */
    private static final ResourceLocation ANIMATIONS = DawnDuskTerminal.id("animations/string.animation.json");

    private static final ResourceLocation LARGE_ANIMATIONS =
            DawnDuskTerminal.id("animations/string_large.animation.json");

    private StringRenderer() {}

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.STRING.get(), Renderer::new);
    }

    /**
     * 自发光 + 写深度 + 半透明，就是 {@code entityTranslucent} 换个自发光着色器
     *
     * <p>两个类型只差三处：着色器（{@code RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER}）、
     * 不挂光图（{@code NO_LIGHTMAP}）、写掩码保留默认的 {@code COLOR_DEPTH_WRITE}
     */
    private static RenderType glowRenderType(ResourceLocation texture) {
        return GLOW_TYPES.computeIfAbsent(
                texture,
                location -> RenderType.create(
                        "ddt_string_glow/" + location,
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

    private static final class Renderer extends YsmEntityRenderer<StringEntity, Animatable> {
        private Renderer(EntityRendererProvider.Context context) {
            super(context, Animatable::new);
        }

        @Override
        protected float scaleOf(StringEntity entity) {
            return entity.getRenderScale();
        }

        /**
         * 贴图是竖向的动画条（16x512 与 32x1024），几何只声明了一帧的高度，
         * 因此这里换成「当前帧」的动态贴图，UV 的 0..1 才正好覆盖一帧
         */
        @Override
        public RenderType getRenderType(
                StringEntity animatable,
                float partialTick,
                PoseStack poseStack,
                @Nullable MultiBufferSource bufferSource,
                @Nullable VertexConsumer buffer,
                int packedLight,
                ResourceLocation texture) {
            return glowRenderType(AnimatedStripTexture.frameOf(texture));
        }

        @Override
        public Color getRenderColor(
                StringEntity animatable,
                float partialTick,
                PoseStack poseStack,
                @Nullable MultiBufferSource bufferSource,
                @Nullable VertexConsumer buffer,
                int packedLight) {
            return Color.WHITE;
        }
    }

    private static final class Animatable extends YsmAnimatable<StringEntity> {
        private Animatable(StringEntity entity) {
            super(entity, SMALL_GEO, ANIMATIONS, SMALL_TEXTURE);
        }

        @Override
        public ResourceLocation getModelLocation() {
            return this.entity.isLargeVariant() ? LARGE_GEO : SMALL_GEO;
        }

        @Override
        public ResourceLocation getAnimationFileLocation() {
            return this.entity.isLargeVariant() ? LARGE_ANIMATIONS : ANIMATIONS;
        }

        @Override
        public ResourceLocation getTextureLocation() {
            return this.entity.isLargeVariant() ? LARGE_TEXTURE : SMALL_TEXTURE;
        }

        /** 模型里没有 main 片段：姿态全在 parallel0 / parallel1 里，由并行动画控制器循环播放 */
        @Override
        protected String mainClip(AnimationEvent<YsmAnimatable<StringEntity>> event) {
            return null;
        }
    }
}
