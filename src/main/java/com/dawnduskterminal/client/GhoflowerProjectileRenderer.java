package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.entity.GhoflowerProjectile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * 鬼花射弹的外观：一个始终面向镜头的四边形，贴图是花正面那一块单独切出来的文件
 *
 * <p>不取 {@code textures/block/sunflower.png}：那张只被 stitch 进方块图集，
 * {@code TextureManager} 里没有它，实体渲染取到的是 missing texture（黑加品红）。
 * 所以 {@code tools/build_sunflower_assets.py} 把花正面（图集里的 {@code face} 区域）单独切一份到
 * {@code textures/entity/ghoflower_projectile.png}，这里按 {@code RenderType#entityCutoutNoCull} 去画。
 */
public class GhoflowerProjectileRenderer extends EntityRenderer<GhoflowerProjectile> {
    /**
     * 昼夜两张贴图：昼版与夜版各一张，实例化时按实体类型固定
     *
     * <p>路径必须写成完整的 {@code textures/...png}：{@code RenderType} 以该路径为纹理键，
     * 写成 {@code entity/ghoflower_projectile} 会取不到纹理。
     */
    public static final ResourceLocation DAY_TEXTURE = projectileTexture("ghoflower_projectile");

    public static final ResourceLocation NIGHT_TEXTURE = projectileTexture("ghoflower_projectile_night");

    private final ResourceLocation texture;

    /** 四边形半宽，格 */
    private static final float HALF = 0.25F;

    public GhoflowerProjectileRenderer(EntityRendererProvider.Context context, ResourceLocation texture) {
        super(context);
        this.texture = texture;
    }

    private static ResourceLocation projectileTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath(DawnDuskTerminal.MOD_ID, "textures/entity/" + name + ".png");
    }

    @Override
    public ResourceLocation getTextureLocation(GhoflowerProjectile entity) {
        return this.texture;
    }

    @Override
    public void render(
            GhoflowerProjectile entity,
            float yaw,
            float partialTick,
            PoseStack poses,
            MultiBufferSource buffers,
            int packedLight) {
        poses.pushPose();
        poses.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poses.mulPose(Axis.YP.rotationDegrees(180.0F));

        // 贴图在实例化时定死：昼版与夜版是两个实体类型，飞过晨昏线不会改颜色
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(this.texture));
        PoseStack.Pose pose = poses.last();
        vertex(consumer, pose, -HALF, -HALF, 0.0F, 1.0F, packedLight);
        vertex(consumer, pose, HALF, -HALF, 1.0F, 1.0F, packedLight);
        vertex(consumer, pose, HALF, HALF, 1.0F, 0.0F, packedLight);
        vertex(consumer, pose, -HALF, HALF, 0.0F, 0.0F, packedLight);
        poses.popPose();
    }

    private static void vertex(
            VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float u, float v, int packedLight) {
        consumer.addVertex(pose, x, y, 0.0F)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(packedLight)
                .setNormal(pose, 0.0F, 0.0F, 1.0F);
    }
}
