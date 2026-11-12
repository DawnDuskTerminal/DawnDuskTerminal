package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModDimensions;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;

/**
 * 晨昏线维度的雨丝贴图：把原版的 {@code rain.png} 换成转好色相的那一张
 *
 * <h2>为什么不改颜色，改贴图</h2>
 *
 * <p>原版雨丝的顶点色写死 {@code (1,1,1,α)}，颜色只可能来自贴图。原版那张是 224° 的冷蓝，
 * 要变成 {@code portal_fluid} 的 286° 紫，只能逐像素转色相——色相是 HSV 里的环形比例，乘法（正片叠底、
 * {@code ColorModulator}、顶点着色）改的是亮度与彩度，把蓝乘到再暗也还是蓝，转不到环的另一侧。
 * 贴图由 {@code tools/recolor_rain.py} 从原版复制一份再转色相，成品在
 * {@code assets/dawnduskterminal/textures/environment/chrono_rain.png}
 *
 * <h2>怎么只换雨、不换雪</h2>
 *
 * <p>原版 {@code LevelRenderer#renderSnowAndRain} 里只有两处换贴图：先雨、后雪。雨贴图的路径是常量
 * {@code minecraft:textures/environment/rain.png}，见 {@link #VANILLA_RAIN}。所以只要在这两处换贴图时
 * 问一句「这个路径是不是原版雨、当前维度是不是晨昏线」，是就把参数换成我们那张，雪那条路径
 * （{@code snow.png}）原样放行
 *
 * <p>贴图路径写在这里是一份复制品（原版那个常量是私有的）。哪天原版改了路径，这里对不上，
 * 表现是「雨变回原版的蓝色」而不是崩掉——这条换贴图的注入本身不依赖任何坐标与状态
 */
public final class ChronoRainTextures {
    /**
     * 转好色相的雨丝，由 {@code tools/recolor_rain.py} 生成
     *
     * <p><b>路径必须带 {@code textures/} 与 {@code .png}</b>：贴图这一路（{@code TextureManager} 与
     * {@code SimpleTexture}）拿到的就是资源包里那条完整路径，原版自己的 {@code RAIN_LOCATION} 也是这么写的。
     * 少了 {@code .png} 不会崩，只会在日志里刷 {@code Failed to load texture}、雨画成紫黑格子；
     * 粒子贴图表里的条目相反，那是图集里的 sprite 名，不带 {@code .png}
     */
    public static final ResourceLocation RAIN = DawnDuskTerminal.id("textures/environment/chrono_rain.png");

    /** 原版雨丝贴图的路径，照抄 {@code LevelRenderer.RAIN_LOCATION} */
    private static final ResourceLocation VANILLA_RAIN =
            ResourceLocation.withDefaultNamespace("textures/environment/rain.png");

    /** 贴图在不在资源包里，第一次用到时查一次，见 {@code ddt$available} */
    @Nullable
    private static Boolean available;

    private ChronoRainTextures() {}

    /** 换贴图时把原版雨丝换成本模组那一张；别的路径、别的维度一律原样返回 */
    public static ResourceLocation swap(ResourceLocation original) {
        if (!VANILLA_RAIN.equals(original)) {
            return original;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().equals(ModDimensions.CHRONO)) {
            return original;
        }
        return ddt$available() ? RAIN : original;
    }

    /**
     * 贴图真的在资源包里？
     *
     * <p>打包漏了这张图时 {@code TextureManager} 会拿紫黑格子顶上，雨会变成一格一格的马赛克；
     * 这里先查一次资源表，查不到就退回原版贴图并告警一次。结果缓存：资源包在运行期不会凭空多出文件
     */
    private static boolean ddt$available() {
        Boolean cached = available;
        if (cached == null) {
            cached = Minecraft.getInstance()
                    .getResourceManager()
                    .getResource(RAIN)
                    .isPresent();
            available = cached;
            if (!cached) {
                DawnDuskTerminal.LOGGER.warn(
                        "DawnDuskTerminal {} is missing from the resource pack, the rain keeps the vanilla texture",
                        RAIN);
            }
        }
        return cached;
    }
}
