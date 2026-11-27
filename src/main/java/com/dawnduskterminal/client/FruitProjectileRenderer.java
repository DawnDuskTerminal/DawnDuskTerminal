package com.dawnduskterminal.client;

import com.dawnduskterminal.entity.FruitProjectileEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 西瓜炮与南瓜炮的炮弹外观：一格大的方块，按实体同步过来的三个角度摆正
 *
 * <p>方块模型占 {@code [0,1]}，实体位置在方块底面中心，所以先把旋转中心挪到
 * {@code (0.5, 0.5, 0.5)}，转完再挪回去。旋转按 X、Y、Z 的顺序合成：X 与 Y 取实体同步过来的静态角，
 * 只有最后应用的 Z 轴在上一 tick 与本 tick 之间插值，于是合成结果是绕一条固定轴的匀速转动，
 * 角速度矢量不会在空中改变方向。渲染走方块图集，贴图坐标由 {@link InventoryMenu#BLOCK_ATLAS} 提供。
 */
public final class FruitProjectileRenderer extends EntityRenderer<FruitProjectileEntity> {
    private final BlockRenderDispatcher blockRenderer;

    public FruitProjectileRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blockRenderer = context.getBlockRenderDispatcher();
        this.shadowRadius = 0.5F;
        this.shadowStrength = 0.5F;
    }

    @Override
    public void render(
            FruitProjectileEntity entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight) {
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        BlockState state = entity.kind().block().defaultBlockState();
        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.XP.rotationDegrees(entity.spinX()));
        poseStack.mulPose(Axis.YP.rotationDegrees(entity.spinY()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(entity.spinZ(partialTick)));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        this.blockRenderer.renderSingleBlock(state, poseStack, bufferSource, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(FruitProjectileEntity entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
