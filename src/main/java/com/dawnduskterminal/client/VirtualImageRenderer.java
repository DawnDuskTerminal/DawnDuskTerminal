package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.client.animation.AnimatedStripTexture;
import com.dawnduskterminal.client.animation.YsmAnimatable;
import com.dawnduskterminal.client.animation.YsmEntityRenderer;
import com.dawnduskterminal.entity.VirtualImageEntity;
import com.dawnduskterminal.geckolib3.core.event.predicate.AnimationEvent;
import com.dawnduskterminal.geckolib3.core.util.Color;
import com.dawnduskterminal.geckolib3.geo.animated.AnimatedGeoBone;
import com.dawnduskterminal.geckolib3.geo.animated.AnimatedGeoModel;
import com.dawnduskterminal.registry.ModEntities;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 「虚像」的客户端接线：玩家比例的人形、流体贴图、套在外面的盔甲壳、全身抖动
 *
 * <h2>几何与贴图</h2>
 * 几何是一具玩家比例的人形骨架（头 8³、身 8×12×4、四肢 4×12×4，共 32 单位高），
 * 渲染时缩到 0.9375，与玩家模型同一档，身高落在 1.8 格上下；个体随机体型再乘上去。
 * 贴图是 {@code textures/block/portal_fluid_flow.png}——一张 32×1024 的 32 帧竖条。
 * 几何的 {@code texture_width/height} 写的是<b>单帧</b>尺寸 32×32，所有 UV 都落在这一帧之内；
 * 渲染时由 {@link AnimatedStripTexture} 把当前帧提取到单帧动态贴图，整只模型才真正跟着流体动。
 *
 * <h2>动作</h2>
 * 主动画只有一条 {@code idle}，内容是玩家那一套动作：走路甩臂迈腿、头跟着视线转、
 * 出手时右臂抬起来。表达式在 {@code animations/virtual_image.animation.json} 里，
 * 出手进度由动画实例每帧写进 {@code v.attack}，见 {@link Animatable#codeAnimations}。
 *
 * <h2>盔甲层</h2>
 * 几何里每个部位都挂了一根 {@code armor_*} 子骨骼：枢轴与父骨骼相同，立方体同样大小但 {@code inflate 0.6}。
 * 单帧只有 32×32，盔甲与本体共用同一帧的 UV。子骨骼没有自己的动画，因此完全跟着父骨骼动
 * —— 这就是一层套在身上的壳。渲染分两趟：第一趟画本体（不透明度 1），
 * 第二趟把本体立方体藏起来、只画盔甲壳（alpha {@value #ARMOR_ALPHA}）。
 * 两趟都用 {@link EmissiveRenderTypes}，所以整只生物都发光。
 *
 * <h2>抖动</h2>
 * 每个部位逐帧在 x、y、z 三个方向各叠一个 ±{@value #JITTER_PIXELS} 像素的随机平移，
 * 见 {@link Renderer#renderRecursively}。这是逐帧重掷的位移，不写进动画，也不改骨骼的持久状态：
 * 渲染完就把矩阵弹回去，下一帧重新掷。慢速摆动与逐帧随机各占一半，看起来才像整具影子在颤动，
 * 而不是每个像素各自闪烁。
 */
public final class VirtualImageRenderer {
    private static final ResourceLocation GEO = DawnDuskTerminal.id("geo/virtual_image.geo.json");
    private static final ResourceLocation ANIMATIONS = DawnDuskTerminal.id("animations/virtual_image.animation.json");
    private static final ResourceLocation TEXTURE = DawnDuskTerminal.id("textures/block/portal_fluid_flow.png");

    /** 玩家模型在渲染时的整体缩放，见 {@code PlayerRenderer#preRenderCallback} */
    private static final float SCALE = 0.9375F;

    /** 盔甲壳的 alpha：八成透明，仍看得出是一层壳 */
    private static final float ARMOR_ALPHA = 0.2F;

    /** 单轴单帧的最大平移量，单位是模型像素（16 像素为一格） */
    private static final float JITTER_PIXELS = 0.25F;

    /** 盔甲子骨骼的名字；本体与盔甲各一份，两趟渲染按这两张表开关 */
    private static final String[] ARMOR_BONES = {
        "armor_head", "armor_body", "armor_right_arm", "armor_left_arm", "armor_right_leg", "armor_left_leg"
    };

    private static final String[] BODY_BONES = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg"};

    private VirtualImageRenderer() {}

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.VIRTUAL_IMAGE.get(), Renderer::new);
    }

    private static final class Renderer extends YsmEntityRenderer<VirtualImageEntity, Animatable> {
        /** 当前渲染的动画时刻，用于给每个部位的抖动错开相位 */
        private float jitterTime;

        /** 逐帧序号：本体与盔甲两趟必须拿到同一帧的位移，否则壳会在身上来回错动 */
        private int jitterFrame;

        private Renderer(EntityRendererProvider.Context context) {
            super(context, Animatable::new);
        }

        @Override
        protected float scaleOf(VirtualImageEntity entity) {
            return SCALE * entity.getRenderScale();
        }

        /**
         * 贴图是 32×1024 的动画竖条，几何只声明了单帧 32×32。
         * 这里换成「当前帧」的动态贴图，UV 的 0..1 才正好覆盖一帧，模型才会真的在流动
         */
        @Override
        public RenderType getRenderType(
                VirtualImageEntity animatable,
                float partialTick,
                PoseStack poseStack,
                @Nullable MultiBufferSource bufferSource,
                @Nullable VertexConsumer buffer,
                int packedLight,
                ResourceLocation texture) {
            return EmissiveRenderTypes.of(AnimatedStripTexture.frameOf(texture));
        }

        @Override
        public Color getRenderColor(
                VirtualImageEntity animatable,
                float partialTick,
                PoseStack poseStack,
                @Nullable MultiBufferSource bufferSource,
                @Nullable VertexConsumer buffer,
                int packedLight) {
            return Color.WHITE;
        }

        /**
         * 两趟渲染：本体一趟、盔甲壳一趟
         *
         * <p>第二趟只改两样东西：把本体那六根骨骼的立方体藏掉（子骨骼照常往下画，所以四肢上的盔甲还在），
         * 并把这一趟的整体 alpha 传成 {@value #ARMOR_ALPHA}。矩阵、深度与渲染层都沿用父类配好的那一套，
         * 因此两趟的顶点顺序、法线取反与 native 批处理完全一致
         *
         * <p>渲染完把开关恢复成「本体可见、盔甲隐藏」：{@link AnimatedGeoModel} 是挂在动画实例上、
         * 跨帧复用的，留下上一帧的开关会让下一帧少画一层
         */
        @Override
        public void render(
                AnimatedGeoModel model,
                VirtualImageEntity entity,
                float partialTick,
                RenderType type,
                PoseStack poseStack,
                @Nullable MultiBufferSource bufferSource,
                @Nullable VertexConsumer buffer,
                int packedLight,
                int packedOverlay,
                float red,
                float green,
                float blue,
                float alpha) {
            this.jitterTime = entity.tickCount + partialTick;
            this.jitterFrame++;
            setArmorHidden(model, true);
            super.render(
                    model,
                    entity,
                    partialTick,
                    type,
                    poseStack,
                    bufferSource,
                    buffer,
                    packedLight,
                    packedOverlay,
                    red,
                    green,
                    blue,
                    1.0F);
            setBodyCubesHidden(model, true);
            setArmorHidden(model, false);
            super.render(
                    model,
                    entity,
                    partialTick,
                    type,
                    poseStack,
                    bufferSource,
                    buffer,
                    packedLight,
                    packedOverlay,
                    red,
                    green,
                    blue,
                    ARMOR_ALPHA);
            setBodyCubesHidden(model, false);
            setArmorHidden(model, true);
        }

        /**
         * 每一根骨骼连同它的整棵子树平移一小段：x、y、z 各取一个逐帧重掷的像素偏移
         *
         * <p>偏移放在子树外面而不是 {@code renderCubesOfBone} 里，有两个原因：
         * 父骨骼的位移顺着矩阵传给子骨骼，整条骨架才是连贯的颤动；盔甲壳是父骨骼的子骨骼，
         * 因此它跟着宿主一起抖、再叠自己的那一份，不会从身上飘开。
         * 位移量换算成格之后交给 {@link PoseStack#translate}，不写进动画，也不改骨骼的持久状态
         */
        @Override
        public void renderRecursively(
                AnimatedGeoBone bone,
                PoseStack poseStack,
                VertexConsumer buffer,
                int packedLight,
                int packedOverlay,
                float red,
                float green,
                float blue,
                float alpha) {
            poseStack.pushPose();
            poseStack.translate(
                    this.jitterPixels(bone, 0) / 16.0F,
                    this.jitterPixels(bone, 1) / 16.0F,
                    this.jitterPixels(bone, 2) / 16.0F);
            super.renderRecursively(bone, poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
            poseStack.popPose();
        }

        /**
         * 一根骨骼一个轴这一帧的像素位移
         *
         * <p>用（骨骼名、轴、帧序号）三个量算哈希，而不是顺次取随机数：本体趟与盔甲趟同处一帧，
         * 同一根骨骼必须拿到同一个值。慢速正弦与哈希随机各占一半，既有连续的轻微晃动，
         * 也有逐帧的细碎颤动
         */
        private float jitterPixels(AnimatedGeoBone bone, int axis) {
            int phase = bone.getName().hashCode() * 31 + axis;
            int hash = phase * 0x9E3779B1 + this.jitterFrame * 0x85EBCA6B;
            hash ^= hash >>> 16;
            hash *= 0x7FEB352D;
            hash ^= hash >>> 15;
            float random = ((hash & 0xFFFF) / 65535.0F) * 2.0F - 1.0F;
            float slow = Mth.sin(this.jitterTime * 1.7F + (phase & 0xFF) * 0.0245F);
            return (random + slow) * 0.5F * JITTER_PIXELS;
        }

        private static void setArmorHidden(AnimatedGeoModel model, boolean hidden) {
            for (String name : ARMOR_BONES) {
                AnimatedGeoBone bone = model.bones().get(name);
                if (bone != null) {
                    // 只藏自己这块，不连累子骨骼：盔甲骨骼本身没有子骨骼
                    bone.setHidden(hidden, false);
                }
            }
        }

        /**
         * 藏掉本体那六根骨骼的立方体，但保留它们的子骨骼
         *
         * <p>{@code setCubesHidden} 只影响 {@code renderCubesOfBone}，{@code renderChildBones} 照常往下走，
         * 于是四肢、头、躯干上的盔甲子骨骼仍在画面里
         */
        private static void setBodyCubesHidden(AnimatedGeoModel model, boolean hidden) {
            for (String name : BODY_BONES) {
                AnimatedGeoBone bone = model.bones().get(name);
                if (bone != null) {
                    bone.setCubesHidden(hidden);
                }
            }
        }
    }

    private static final class Animatable extends YsmAnimatable<VirtualImageEntity> {
        private Animatable(VirtualImageEntity entity) {
            super(entity, GEO, ANIMATIONS, TEXTURE);
        }

        /** 只有一条 {@code idle}，走路与出手都由这条片段里的表达式算出来 */
        @Override
        protected String mainClip(AnimationEvent<YsmAnimatable<VirtualImageEntity>> event) {
            return "idle";
        }

        /**
         * 把出手进度写进 {@code v.attack}
         *
         * <p>动画表达式读不到 {@code LivingEntity#getAttackAnim}，所以每帧由这里写一个 0~1 的标量进去；
         * 片段里用 {@code -105*v.attack} 把右臂抬起来。写变量发生在动画求值之前
         * （{@code AnimatableEntity#codeAnimations} 在 {@code tickAnimation} 前面），这一帧就生效
         */
        @Override
        public void codeAnimations(AnimationEvent<?> event) {
            this.setRoaming("attack", this.entity.getAttackAnim(event.getPartialTick()));
        }
    }
}
