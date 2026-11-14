package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.client.animation.YsmAnimatable;
import com.dawnduskterminal.client.animation.YsmEntityRenderer;
import com.dawnduskterminal.entity.GhastEliteEntity;
import com.dawnduskterminal.geckolib3.core.event.predicate.AnimationEvent;
import com.dawnduskterminal.geckolib3.core.molang.context.AnimationContext;
import com.dawnduskterminal.geckolib3.core.processor.IBone;
import com.dawnduskterminal.registry.ModEntities;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 「大恶魂」的客户端注册
 *
 * <p>几何、动画与贴图各一份，按项目统一布局放置：{@code geo/<名称>.geo.json}、
 * {@code animations/<名称>.animation.json}、{@code textures/entity/<名称>.png}。
 * 三份文件均为模型作者提供的原件，未作任何改写。
 *
 * <p>动画共两段，均由并行动画控制器逐帧求值，没有主动画：
 * {@code parallel0} 为七根触手与四肢的柏林噪声摆动；
 * {@code parallel1} 为每帧执行的 {@code timeline} 程序（布条与触手的链式物理，
 * 以 {@code ysm.bone_pivot_abs} 读取各段当前位置，以 {@code fn.*} 的四元数函数计算朝向，
 * 以 {@code ysm.first_order} 做一阶滤波）。
 */
public final class GhastEliteRenderer {
    private GhastEliteRenderer() {}

    private static final ResourceLocation GEO = DawnDuskTerminal.id("geo/ghast_elite.geo.json");

    private static final ResourceLocation ANIMATIONS = DawnDuskTerminal.id("animations/ghast_elite.animation.json");

    private static final ResourceLocation TEXTURE = DawnDuskTerminal.id("textures/entity/ghast_elite.png");

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.GHAST_ELITE.get(), Renderer::new);
        // 火球必须注册渲染器：缺失时 EntityRenderDispatcher 取到 null，客户端将抛出空指针异常并崩溃
        event.registerEntityRenderer(
                ModEntities.GHAST_ELITE_FIREBALL.get(), context -> new ThrownItemRenderer<>(context, 3.0F, true));
    }

    private static final class Renderer extends YsmEntityRenderer<GhastEliteEntity, Animatable> {
        private Renderer(EntityRendererProvider.Context context) {
            super(context, Animatable::new);
            // 碰撞箱为 8 格见方，影子半径取其一半
            this.shadowRadius = 4.0F;
        }

        /** 几何的 {@code visible_bounds} 已是实际尺寸，不再按模型包元数据缩放 */
        @Override
        protected float scaleOf(GhastEliteEntity entity) {
            return 1.0F;
        }
    }

    private static final class Animatable extends YsmAnimatable<GhastEliteEntity> {
        private Animatable(GhastEliteEntity entity) {
            super(entity, GEO, ANIMATIONS, TEXTURE);
        }

        /** 不存在主动画：全部通道均由并行动画层写入 */
        @Override
        protected String mainClip(AnimationEvent<YsmAnimatable<GhastEliteEntity>> event) {
            return null;
        }

        /**
         * 三种面部状态：几何由「一张底图 {@code thehead} 与两片位于脸前的薄片」构成
         *
         * <p>{@code annoyed} 与 {@code atack} 是两片薄片，名称取自模型包。可见的薄片将遮盖底图，
         * 因此切换面部状态无须更换贴图，只需依据 {@link GhastEliteEntity#getFace()} 设置可见性。
         * 两片薄片处于同一深度，不得同时可见。
         *
         * <p>状态取自实体同步数据 {@code DATA_FACE}，不直接读取 {@code getTarget()}：后者仅在服务端有值。
         * 该逻辑位于 {@code setCustomAnimations} 的末尾，因此于动画写入骨骼之后、读取骨骼矩阵之前生效。
         */
        @Override
        public boolean setCustomAnimations(AnimationContext<?> context, AnimationEvent<?> event) {
            boolean updated = super.setCustomAnimations(context, event);
            GhastEliteEntity.Face face = this.entity.getFace();
            hideBone("annoyed", face != GhastEliteEntity.Face.ANNOYED);
            hideBone("atack", face != GhastEliteEntity.Face.ATTACK);
            return updated;
        }

        private void hideBone(String name, boolean hidden) {
            IBone bone = this.getAnimationProcessor().getBone(name);
            if (bone != null) {
                bone.setHidden(hidden);
            } else if (DawnDuskTerminal.LOGGER.isDebugEnabled()) {
                // 该方法每帧执行：仅在调试级别启用时才构造日志文本，避免逐帧产生无用字符串
                DawnDuskTerminal.LOGGER.debug("大恶魂的模型缺少骨骼 {}", name);
            }
        }
    }
}
