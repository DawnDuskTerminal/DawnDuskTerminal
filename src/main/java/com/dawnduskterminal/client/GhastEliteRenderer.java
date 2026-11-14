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
 * 「大恶魂」的客户端接线
 *
 * <p>几何、动画、贴图各一份，按项目的统一布局放置：{@code geo/<名字>.geo.json}、
 * {@code animations/<名字>.animation.json}、{@code textures/entity/<名字>.png}。
 * 三份文件都是模型作者的原件，逐字节未改写。
 *
 * <p>片段只有两条：{@code parallel0} 是七根触手与四肢的柏林噪声摆动，{@code parallel1} 是一段
 * 每帧执行的 {@code timeline} 程序（布条与触手的链式物理，用 {@code ysm.bone_pivot_abs} 读各段当前位置、
 * 用 {@code fn.*} 的四元数函数算朝向、用 {@code ysm.first_order} 做一阶滤波）。
 * 它们由并行动画控制器逐帧求值，没有第三段主动画。
 */
public final class GhastEliteRenderer {
    private GhastEliteRenderer() {}

    private static final ResourceLocation GEO = DawnDuskTerminal.id("geo/ghast_elite.geo.json");

    private static final ResourceLocation ANIMATIONS = DawnDuskTerminal.id("animations/ghast_elite.animation.json");

    private static final ResourceLocation TEXTURE = DawnDuskTerminal.id("textures/entity/ghast_elite.png");

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.GHAST_ELITE.get(), Renderer::new);
        // 火球的渲染器漏了会让 EntityRenderDispatcher 拿到 null，客户端直接 NPE 崩掉
        event.registerEntityRenderer(
                ModEntities.GHAST_ELITE_FIREBALL.get(), context -> new ThrownItemRenderer<>(context, 3.0F, true));
    }

    private static final class Renderer extends YsmEntityRenderer<GhastEliteEntity, Animatable> {
        private Renderer(EntityRendererProvider.Context context) {
            super(context, Animatable::new);
            // 碰撞箱 8 格见方，影子半径取一半
            this.shadowRadius = 4.0F;
        }

        /** 几何的 visible_bounds 已经是实机尺寸，不再按模型包元数据缩放 */
        @Override
        protected float scaleOf(GhastEliteEntity entity) {
            return 1.0F;
        }
    }

    private static final class Animatable extends YsmAnimatable<GhastEliteEntity> {
        private Animatable(GhastEliteEntity entity) {
            super(entity, GEO, ANIMATIONS, TEXTURE);
        }

        /** 没有主动画：所有通道都由并行动画层写 */
        @Override
        protected String mainClip(AnimationEvent<YsmAnimatable<GhastEliteEntity>> event) {
            return null;
        }

        /**
         * 三张脸：几何里是「一张底图 {@code thehead} + 两片贴在脸前的薄片」
         *
         * <p>{@code annoyed} 与 {@code atack}（模型包里就这么拼）是两片薄片，谁可见谁就盖住底图，
         * 所以切换不需要换贴图，只按 {@link GhastEliteEntity#getFace()} 开关可见性。
         * 两片在同一深度上，绝不能同时可见。
         *
         * <p>状态取实体同步过来的 {@code DATA_FACE}，不直接读 {@code getTarget()}：后者只在服务端有值。
         * 写在 {@code setCustomAnimations} 末尾，因此是在动画写完骨骼之后、读骨骼矩阵之前生效。
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
            } else {
                DawnDuskTerminal.LOGGER.debug("大恶魂的模型里没有骨骼 {}", name);
            }
        }
    }
}
