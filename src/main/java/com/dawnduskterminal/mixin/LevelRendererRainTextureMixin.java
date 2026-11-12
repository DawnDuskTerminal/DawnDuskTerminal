package com.dawnduskterminal.mixin;

import com.dawnduskterminal.client.ChronoRainTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 晨昏线维度的雨丝换成转好色相的那张贴图
 *
 * <h2>为什么是换贴图而不是换颜色</h2>
 *
 * <p>原版雨丝的顶点色写死白色，颜色全在贴图上。要把 224° 的冷蓝变成 286° 的紫，只能逐像素转色相；
 * 而乘法类的做法（{@code ColorModulator}、{@code RenderSystem#setShaderColor}、顶点色）改的是亮度与彩度，
 * 蓝乘什么都不会变成紫。贴图本身由 {@code tools/recolor_rain.py} 生成，见 {@link ChronoRainTextures}
 *
 * <h2>插入点</h2>
 *
 * <p>原版 {@code LevelRenderer#renderSnowAndRain} 里换贴图只有两处（先雨、后雪），两处都调
 * {@code RenderSystem.setShaderTexture}。对整个方法做一次 {@link Redirect} 就能同时盖住两处，
 * 由 {@link ChronoRainTextures#swap} 判断该不该换：只有「原版雨丝路径 + 当前维度是晨昏线」才换，
 * 雪与别的维度原样放行
 *
 * <p>方法签名里的 {@code LightTexture} 在 {@code net.minecraft.client.renderer} 包下（不是
 * {@code ...renderer.texture}），写错包名会在客户端启动时直接
 * {@code Critical injection failure} 崩掉
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererRainTextureMixin {
    private static final String RENDER_SNOW_AND_RAIN =
            "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V";

    private static final String SET_SHADER_TEXTURE =
            "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V";

    @Redirect(method = RENDER_SNOW_AND_RAIN, at = @At(value = "INVOKE", target = SET_SHADER_TEXTURE))
    private static void ddt$swapRainTexture(int unit, ResourceLocation location) {
        RenderSystem.setShaderTexture(unit, ChronoRainTextures.swap(location));
    }
}
