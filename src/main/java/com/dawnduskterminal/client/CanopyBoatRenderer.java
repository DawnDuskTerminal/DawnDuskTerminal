package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.entity.CanopyBoatEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.RaftModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;
import org.joml.Quaternionf;

/**
 * 苍穹船的渲染器：竹筏的模型 + 自己那张 128x64 的贴图
 *
 * <p>没有继承原版 {@code BoatRenderer}：它按船变体查一张私有的 {@code Map<Boat.Type, Pair<贴图, 模型>>}，
 * 贴图是 Map 里那份、不经过 {@code getTextureLocation}，所以只覆写 {@code getTextureLocation} 换不掉颜色。
 * 这里照它的 {@code render} 逐行搬过来，把两处换掉：模型固定 {@link RaftModel}（竹筏那一套，与
 * {@code models} 一致的 128x64 展开），贴图固定 {@link #TEXTURE}
 *
 * <p>竹筏的模型层就是 {@code ModelLayers.createBoatModelName(BAMBOO)} → {@code boat/bamboo}，
 * 原版 {@code LayerDefinitions} 拿 {@code RaftModel.createBodyModel()} 烘的；原版 {@code BoatRenderer}
 * 对 BAMBOO 也是这么取。船体入水时的水色遮罩（{@code WaterPatchModel}）竹筏没有，所以不需要补
 */
public final class CanopyBoatRenderer extends EntityRenderer<CanopyBoatEntity> {
    /** 竹筏那张图的展开，只是换了配色 */
    private static final ResourceLocation TEXTURE = DawnDuskTerminal.id("textures/entity/boat/canopy_boat.png");

    private final RaftModel model;

    public CanopyBoatRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.8F;
        this.model = new RaftModel(context.bakeLayer(ModelLayers.createBoatModelName(Boat.Type.BAMBOO)));
    }

    @Override
    public ResourceLocation getTextureLocation(CanopyBoatEntity boat) {
        return TEXTURE;
    }

    @Override
    public void render(
            CanopyBoatEntity boat,
            float entityYaw,
            float partialTick,
            PoseStack pose,
            MultiBufferSource buffer,
            int packedLight) {
        pose.pushPose();
        pose.translate(0.0F, 0.375F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw));
        // 挨打之后那几下晃动，公式与原版一模一样
        float hurt = (float) boat.getHurtTime() - partialTick;
        float damage = boat.getDamage() - partialTick;
        if (damage < 0.0F) {
            damage = 0.0F;
        }
        if (hurt > 0.0F) {
            pose.mulPose(Axis.XP.rotationDegrees(Mth.sin(hurt) * hurt * damage / 10.0F * (float) boat.getHurtDir()));
        }
        float bubbleAngle = boat.getBubbleAngle(partialTick);
        if (!Mth.equal(bubbleAngle, 0.0F)) {
            pose.mulPose(new Quaternionf().setAngleAxis(bubbleAngle * (float) (Math.PI / 180.0), 1.0F, 0.0F, 1.0F));
        }
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.mulPose(Axis.YP.rotationDegrees(90.0F));
        this.model.setupAnim(boat, partialTick, 0.0F, -0.1F, 0.0F, 0.0F);
        VertexConsumer consumer = buffer.getBuffer(this.model.renderType(TEXTURE));
        this.model.renderToBuffer(pose, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(boat, entityYaw, partialTick, pose, buffer, packedLight);
    }
}
