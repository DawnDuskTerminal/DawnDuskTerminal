package com.dawnduskterminal.client.animation;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 使用真实 NativeImage 检查生命周期，不启动 Minecraft 或初始化渲染线程。
 *
 * <p>动态纹理的 GPU 操作仅进入渲染队列，不执行；纹理管理器由本地替身记录注册与释放。
 * 本检查不能替代客户端动画与 F3+T 验收。
 */
public final class AnimatedStripTextureTest {
    private static final ResourceLocation IMAGE = ResourceLocation.fromNamespaceAndPath("dawnduskterminal", "r03.png");
    private static final ResourceLocation META =
            ResourceLocation.fromNamespaceAndPath("dawnduskterminal", "r03.png.mcmeta");
    private static int failures;

    private AnimatedStripTextureTest() {}

    public static void main(String[] args) throws Exception {
        check("正常动画与元数据", AnimatedStripTextureTest::animation);
        check("静态、损坏及缺失资源的负缓存", AnimatedStripTextureTest::fallbacks);
        check("缺失资源在重载后恢复", AnimatedStripTextureTest::recovery);
        check("连续十次重载释放图像", AnimatedStripTextureTest::reloads);
        check("注册前后异常及流关闭异常", AnimatedStripTextureTest::loadFailures);
        if (failures != 0) {
            throw new AssertionError("动画贴图检查失败项数：" + failures);
        }
        System.out.println("AnimatedStripTexture lifecycle checks passed");
    }

    private static void check(String name, Check action) throws Exception {
        try (Fixture fixture = new Fixture()) {
            action.run(fixture);
            System.out.println("PASS: " + name);
        } catch (AssertionError error) {
            failures++;
            System.err.println("FAIL: " + name + ": " + error.getMessage());
        }
    }

    private static void animation(Fixture fixture) throws Exception {
        fixture.resources.files.put(IMAGE, png(4));
        fixture.resources.files.put(META, "{\"animation\":{\"frametime\":3}}".getBytes(StandardCharsets.UTF_8));
        ResourceLocation frame = fixture.frame();
        expect(!frame.equals(IMAGE), "竖条应返回动态贴图");
        Object strip = fixture.strip();
        NativeImage source = fixture.source();
        NativeImage target = fixture.textures.pixels;
        expect((int) field(strip, "frameTicks") == 3, "应保留元数据中的帧间隔");
        int shown = (int) field(strip, "shownFrame");
        expect(target.getPixelRGBA(0, 0) == source.getPixelRGBA(0, shown * 2), "当前帧像素不匹配");
        for (int i = 0; i < 100; i++) {
            expect(fixture.frame().equals(frame), "动态贴图位置必须稳定");
        }
        expect(fixture.resources.reads.get(IMAGE) == 1, "动画源图不应逐帧读取");
        expect(fixture.textures.registrations == 1, "动态贴图不应重复注册");
        fixture.invalidate();
        expectClosed(source);
        expectClosed(target);
        expect(fixture.textures.releases == 1, "应通过纹理管理器释放目标图");
    }

    private static void fallbacks(Fixture fixture) throws Exception {
        for (byte[] bytes : new byte[][] {png(2), new byte[] {1, 2, 3}}) {
            fixture.resources.files.put(IMAGE, bytes);
            repeatedFallback(fixture);
            fixture.invalidate();
            fixture.resources.reads.clear();
        }
        fixture.resources.files.clear();
        repeatedFallback(fixture);
        expect(fixture.textures.registrations == 0, "静态或不可读资源不应注册动态纹理");
    }

    private static void repeatedFallback(Fixture fixture) {
        for (int i = 0; i < 100; i++) {
            expect(fixture.frame().equals(IMAGE), "静态回退应保留原始位置");
        }
        expect(fixture.resources.reads.get(IMAGE) == 1, "同一资源代次的回退只应读取一次");
    }

    private static void recovery(Fixture fixture) throws Exception {
        repeatedFallback(fixture);
        fixture.resources.files.put(IMAGE, png(4));
        expect(fixture.frame().equals(IMAGE), "补入资源后应等待重载再重试");
        fixture.invalidate();
        expect(!fixture.frame().equals(IMAGE), "重载后应恢复此前缺失的动画");
        fixture.source();
        expect(fixture.resources.reads.get(IMAGE) == 2, "重载后应重新读取一次");
        expect((int) field(fixture.strip(), "frameTicks") == 1, "缺少元数据时仍应每 tick 一帧");
    }

    private static void reloads(Fixture fixture) throws Exception {
        fixture.resources.files.put(IMAGE, png(4));
        fixture.resources.files.put(META, new byte[] {0});
        for (int i = 0; i < 10; i++) {
            ResourceLocation frame = fixture.frame();
            NativeImage source = fixture.source();
            NativeImage target = fixture.textures.pixels;
            expect((int) field(fixture.strip(), "frameTicks") == 1, "损坏元数据应回退到一 tick");
            fixture.invalidate();
            expectClosed(source);
            expectClosed(target);
            expect(fixture.textures.getTexture(frame, null) == null, "失效后纹理管理器不应保留动态纹理");
            fixture.invalidate();
            expect(fixture.textures.releases == i + 1, "重复失效不应重复释放");
        }
        expect(fixture.resources.reads.get(IMAGE) == 10, "十个资源代次应各读取一次");
        expect(fixture.textures.registrations == 10, "十个资源代次应各注册一次");
        System.out.println(
                "Reload counts: source loads=10, registrations=10, releases=10; both images closed each cycle");
    }

    private static void loadFailures(Fixture fixture) throws Exception {
        fixture.resources.files.put(IMAGE, png(4));
        for (int failure : new int[] {1, 2}) {
            fixture.textures.failure = failure;
            repeatedFallback(fixture);
            expectClosed(fixture.textures.pixels);
            expect(fixture.textures.images.isEmpty(), "注册失败后不得残留管理器条目");
            expect(fixture.textures.dynamic.getPixels() == null, "失败纹理应关闭其目标图");
            fixture.invalidate();
            fixture.resources.reads.clear();
        }
        fixture.textures.failure = 0;
        fixture.resources.failClose = true;
        repeatedFallback(fixture);
        fixture.invalidate();
        fixture.resources.failClose = false;
        expect(!fixture.frame().equals(IMAGE), "重载后应允许重试加载异常");
        fixture.source();
    }

    private static byte[] png(int height) throws IOException {
        try (NativeImage image = new NativeImage(2, height, false)) {
            image.fillRect(0, 0, 2, height, 0xFF112233);
            image.fillRect(0, 0, 2, 2, 0xFF445566);
            return image.asByteArray();
        }
    }

    private static void expectClosed(NativeImage image) {
        try {
            image.getPixelRGBA(0, 0);
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("原生图像未关闭");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    @FunctionalInterface
    private interface Check {
        void run(Fixture fixture) throws Exception;
    }

    private static final class Fixture implements AutoCloseable {
        private final MemoryResources resources = new MemoryResources();
        private final RecordingTextures textures = new RecordingTextures(this.resources);
        private final List<NativeImage> sources = new ArrayList<>();

        private ResourceLocation frame() {
            return AnimatedStripTexture.frameOf(IMAGE, this.resources, this.textures);
        }

        private Object strip() throws ReflectiveOperationException {
            Field field = AnimatedStripTexture.class.getDeclaredField("STRIPS");
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(null)).get(IMAGE);
        }

        private NativeImage source() throws ReflectiveOperationException {
            NativeImage source = (NativeImage) field(this.strip(), "source");
            this.sources.add(source);
            return source;
        }

        private void invalidate() {
            AnimatedStripTexture.invalidate(this.textures);
        }

        @Override
        public void close() {
            this.invalidate();
            this.sources.forEach(NativeImage::close);
            this.textures.close();
        }
    }

    private static final class RecordingTextures extends TextureManager {
        private final Map<ResourceLocation, AbstractTexture> images = new HashMap<>();
        private int registrations;
        private int releases;
        private int failure;
        private DynamicTexture dynamic;
        private NativeImage pixels;

        private RecordingTextures(ResourceManager resources) {
            super(resources);
        }

        @Override
        public void register(ResourceLocation location, AbstractTexture texture) {
            this.registrations++;
            this.dynamic = (DynamicTexture) texture;
            this.pixels = this.dynamic.getPixels();
            if (this.failure == 1) {
                throw new IllegalStateException("注册前故障注入");
            }
            this.images.put(location, texture);
            if (this.failure == 2) {
                throw new IllegalStateException("注册后故障注入");
            }
        }

        @Override
        public AbstractTexture getTexture(ResourceLocation location, AbstractTexture fallback) {
            return this.images.getOrDefault(location, fallback);
        }

        @Override
        public void release(ResourceLocation location) {
            this.releases++;
            AbstractTexture texture = this.images.remove(location);
            if (texture != null) {
                texture.close();
            }
        }

        @Override
        public void close() {
            this.images.values().forEach(AbstractTexture::close);
            this.images.clear();
            if (this.dynamic != null) {
                this.dynamic.close();
            }
        }
    }

    private static final class MemoryResources implements ResourceManager {
        private final Map<ResourceLocation, byte[]> files = new HashMap<>();
        private final Map<ResourceLocation, Integer> reads = new HashMap<>();
        private boolean failClose;

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            this.reads.merge(location, 1, Integer::sum);
            byte[] bytes = this.files.get(location);
            return bytes == null
                    ? Optional.empty()
                    : Optional.of(new Resource(null, () -> new ByteArrayInputStream(bytes) {
                        @Override
                        public void close() throws IOException {
                            if (MemoryResources.this.failClose) {
                                throw new IOException("流关闭故障注入");
                            }
                            super.close();
                        }
                    }));
        }

        @Override
        public Set<String> getNamespaces() {
            return Set.of(IMAGE.getNamespace());
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return this.getResource(location).stream().toList();
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String prefix, Predicate<ResourceLocation> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(
                String prefix, Predicate<ResourceLocation> filter) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }
    }
}
