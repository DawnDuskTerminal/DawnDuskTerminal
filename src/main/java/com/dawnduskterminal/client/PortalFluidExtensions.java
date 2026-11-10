package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.ChronoPlantTints;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import org.joml.Vector3f;

public final class PortalFluidExtensions implements IClientFluidTypeExtensions {
    public static final PortalFluidExtensions INSTANCE = new PortalFluidExtensions();

    public static final ResourceLocation STILL = DawnDuskTerminal.id("block/portal_fluid_still");
    public static final ResourceLocation FLOWING = DawnDuskTerminal.id("block/portal_fluid_flow");

    /** 原版的水下遮罩贴图，用来做「像水一样」的浸没覆盖层 */
    private static final ResourceLocation UNDERWATER =
            ResourceLocation.withDefaultNamespace("textures/misc/underwater.png");

    /**
     * 浸没时的雾色 {@code 0x7F159E}
     *
     * <p>与河海类群系的植物着色同一个颜色（{@link ChronoPlantTints#PURPLE}），两边共用一个常量，
     * 免得改了雾色之后水边的草还是旧的紫
     */
    private static final Vector3f SUBMERSION_FOG = rgb(ChronoPlantTints.PURPLE);

    private PortalFluidExtensions() {}

    /** 0xRRGGBB 拆成 0~1 的三个分量 */
    private static Vector3f rgb(int color) {
        return new Vector3f(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F);
    }

    @Override
    public ResourceLocation getStillTexture() {
        return STILL;
    }

    @Override
    public ResourceLocation getFlowingTexture() {
        return FLOWING;
    }

    @Override
    public int getTintColor() {
        return 0xFFFFFFFF;
    }

    @Override
    public ResourceLocation getRenderOverlayTexture(Minecraft mc) {
        return UNDERWATER;
    }

    @Override
    public Vector3f modifyFogColor(
            Camera camera,
            float partialTick,
            ClientLevel level,
            int renderDistance,
            float darkenWorldAmount,
            Vector3f fluidFogColor) {
        // 必须返回新实例：ClientHooks.getFogColor 会就地修改传回的向量
        return new Vector3f(SUBMERSION_FOG);
    }

    @Override
    public void modifyFogRender(
            Camera camera,
            FogRenderer.FogMode mode,
            float renderDistance,
            float partialTick,
            float nearDistance,
            float farDistance,
            FogShape shape) {
        // 接近水的雾浓度，略微更浓（-8 到 64 格，柱形）
        RenderSystem.setShaderFogStart(-8.0F);
        RenderSystem.setShaderFogEnd(64.0F);
        RenderSystem.setShaderFogShape(FogShape.CYLINDER);
    }
}
