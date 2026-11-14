package com.dawnduskterminal.client.animation;

import com.dawnduskterminal.DawnDuskTerminal;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 将竖向动画条的当前帧上传至单帧大小的动态贴图。
 *
 * <p>实体贴图不经过原版图集动画；弦的几何仅声明单帧高度，因此需要单独提取当前帧。
 * {@code .mcmeta} 的 {@code animation.frametime} 指定帧间隔，缺失或不可读时按每 tick 一帧处理。
 *
 * <p>仅在渲染线程使用。缓存拥有竖条源图，纹理管理器拥有动态纹理及其目标图。
 * 资源重载时由 {@link #invalidate()} 分别释放；静态回退与加载失败也缓存至本次资源代次结束。
 */
public final class AnimatedStripTexture {
    private static final Map<ResourceLocation, Strip> STRIPS = new HashMap<>();

    private AnimatedStripTexture() {}

    /**
     * 返回动画条的当前帧位置，并更新动态贴图。
     *
     * <p>单帧贴图或加载失败时返回原始位置，同一资源代次内不再重试。
     */
    public static ResourceLocation frameOf(ResourceLocation strip) {
        Minecraft client = Minecraft.getInstance();
        return frameOf(strip, client.getResourceManager(), client.getTextureManager());
    }

    static ResourceLocation frameOf(ResourceLocation strip, ResourceManager resources, TextureManager textures) {
        Strip state = STRIPS.get(strip);
        if (state == null && !STRIPS.containsKey(strip)) {
            state = Strip.load(strip, resources, textures);
            STRIPS.put(strip, state);
        }
        if (state == null) {
            return strip;
        }
        state.advance();
        return state.frameId;
    }

    /** 释放本次资源代次的图像与纹理，清除负缓存，后续访问时重新加载。 */
    public static void invalidate() {
        invalidate(Minecraft.getInstance().getTextureManager());
    }

    static void invalidate(TextureManager textures) {
        for (Strip strip : STRIPS.values()) {
            if (strip != null) {
                strip.source.close();
                textures.release(strip.frameId);
            }
        }
        STRIPS.clear();
    }

    private static final class Strip {
        private final NativeImage source;
        private final int frameSize;
        private final int frameCount;
        private final int frameTicks;
        private final NativeImage target;
        private final DynamicTexture dynamic;
        private final ResourceLocation frameId;

        private int shownFrame = -1;

        private Strip(
                NativeImage source,
                int frameSize,
                int frameCount,
                int frameTicks,
                ResourceLocation frameId,
                DynamicTexture dynamic) {
            this.source = source;
            this.frameSize = frameSize;
            this.frameCount = frameCount;
            this.frameTicks = frameTicks;
            this.target = dynamic.getPixels();
            this.dynamic = dynamic;
            this.frameId = frameId;
        }

        private static Strip load(ResourceLocation strip, ResourceManager resources, TextureManager textures) {
            NativeImage source = null;
            NativeImage pixels = null;
            DynamicTexture dynamic = null;
            ResourceLocation frameId =
                    ResourceLocation.fromNamespaceAndPath(strip.getNamespace(), "dynamic/" + strip.getPath());
            try {
                // 先关闭输入流，避免关闭异常发生在图像所有权移交之后。
                try (InputStream stream = resources.getResourceOrThrow(strip).open()) {
                    source = NativeImage.read(stream);
                }
                int frameSize = source.getWidth();
                // 竖条的宽度为单帧边长，高度除以宽度即帧数。
                int frameCount = Math.max(1, source.getHeight() / frameSize);
                if (frameCount == 1) {
                    return null;
                }
                int frameTicks = readFrameTicks(strip, resources);
                pixels = new NativeImage(frameSize, frameSize, false);
                dynamic = new DynamicTexture(pixels);
                pixels = null; // 构造成功后，目标图由动态纹理持有。
                textures.register(frameId, dynamic);
                Strip result = new Strip(source, frameSize, frameCount, frameTicks, frameId, dynamic);
                source = null; // 源图移交至缓存，动态纹理移交至纹理管理器。
                dynamic = null;
                return result;
            } catch (Exception error) {
                DawnDuskTerminal.LOGGER.warn("贴图动画条读取失败：{}", strip, error);
                return null;
            } finally {
                if (source != null) {
                    source.close();
                }
                if (dynamic != null) {
                    if (textures.getTexture(frameId, null) == dynamic) {
                        textures.release(frameId);
                    } else {
                        dynamic.close();
                    }
                } else if (pixels != null) {
                    pixels.close();
                }
            }
        }

        /** 读取以 tick 为单位的帧间隔；缺失或不可读时返回 1。 */
        private static int readFrameTicks(ResourceLocation strip, ResourceManager resources) {
            ResourceLocation meta =
                    ResourceLocation.fromNamespaceAndPath(strip.getNamespace(), strip.getPath() + ".mcmeta");
            try (InputStream stream = resources.getResourceOrThrow(meta).open()) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (json.has("animation") && json.getAsJsonObject("animation").has("frametime")) {
                    return Math.max(
                            1,
                            json.getAsJsonObject("animation").get("frametime").getAsInt());
                }
            } catch (Exception ignored) {
                // 元数据缺失或不可读时保留每 tick 一帧的播放规则。
            }
            return 1;
        }

        private void advance() {
            long ticks = Util.getMillis() / 50L;
            int frame = (int) ((ticks / this.frameTicks) % this.frameCount);
            if (frame == this.shownFrame) {
                return;
            }
            this.shownFrame = frame;
            int offsetY = frame * this.frameSize;
            for (int y = 0; y < this.frameSize; y++) {
                for (int x = 0; x < this.frameSize; x++) {
                    this.target.setPixelRGBA(x, y, this.source.getPixelRGBA(x, offsetY + y));
                }
            }
            this.dynamic.upload();
        }
    }
}
