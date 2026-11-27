package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.SunflowerPlant;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * 叶片模板的加载器
 *
 * <p>模板放在 {@code assets/dawnduskterminal/sunflower/leaves.json}，由
 * {@code tools/build_sunflower_assets.py} 生成，资源包可以覆盖。
 * 解析在后台线程完成（{@link #prepare}），换进静态字段在主线程完成（{@link #apply}），
 * 渲染线程读到的永远是完整的一份。
 *
 * <p>任何一处不合规（片数对不上、角度越界、字段缺失）都整份退回 {@link SunflowerPlant#fallback()}
 * 并记一条错误日志：宁可全部用兜底，也不要渲染出一个越界的叶片。
 */
public final class SunflowerLeaves extends SimplePreparableReloadListener<List<SunflowerPlant.Template>> {
    private static final ResourceLocation LOCATION = DawnDuskTerminal.id("sunflower/leaves.json");

    /** 一组模板最多几片叶 */
    private static final int MAX_LEAVES = 4;

    private static volatile List<SunflowerPlant.Template> templates = SunflowerPlant.fallback();

    public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SunflowerLeaves());
    }

    /** 当前生效的模板，永远非空 */
    public static List<SunflowerPlant.Template> templates() {
        return templates;
    }

    @Override
    protected List<SunflowerPlant.Template> prepare(ResourceManager manager, ProfilerFiller profiler) {
        return manager.getResource(LOCATION).map(SunflowerLeaves::parse).orElseGet(() -> {
            DawnDuskTerminal.LOGGER.error(
                    "missing {}; falling back to the built-in sunflower leaf templates", LOCATION);
            return SunflowerPlant.fallback();
        });
    }

    @Override
    protected void apply(List<SunflowerPlant.Template> parsed, ResourceManager manager, ProfilerFiller profiler) {
        templates = parsed;
    }

    private static List<SunflowerPlant.Template> parse(Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<SunflowerPlant.Template> parsed = new ArrayList<>();
            for (JsonElement element : GsonHelper.getAsJsonArray(root, "templates")) {
                parsed.add(parseTemplate(element.getAsJsonObject()));
            }
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("no templates");
            }
            return List.copyOf(parsed);
        } catch (Exception error) {
            DawnDuskTerminal.LOGGER.error(
                    "cannot read {}; falling back to the built-in sunflower leaf templates", LOCATION, error);
            return SunflowerPlant.fallback();
        }
    }

    private static SunflowerPlant.Template parseTemplate(JsonObject template) {
        int count = GsonHelper.getAsInt(template, "count");
        if (count < 1 || count > MAX_LEAVES) {
            throw new IllegalArgumentException("count must be 1.." + MAX_LEAVES + ", got " + count);
        }
        List<JsonElement> elements =
                GsonHelper.getAsJsonArray(template, "leaves").asList();
        if (elements.size() != count) {
            throw new IllegalArgumentException("count is " + count + " but there are " + elements.size() + " leaves");
        }
        List<SunflowerPlant.Leaf> leaves = new ArrayList<>(count);
        for (JsonElement element : elements) {
            JsonObject leaf = element.getAsJsonObject();
            float yaw = require(GsonHelper.getAsFloat(leaf, "yaw"), 0.0F, 360.0F, "yaw");
            float pitch = require(GsonHelper.getAsFloat(leaf, "pitch"), 0.0F, 60.0F, "pitch");
            float scale = require(GsonHelper.getAsFloat(leaf, "scale"), 0.5F, 1.5F, "scale");
            float y = require(GsonHelper.getAsFloat(leaf, "y"), 0.0F, 16.0F, "y");
            leaves.add(new SunflowerPlant.Leaf(yaw, pitch, scale, y));
        }
        return new SunflowerPlant.Template(List.copyOf(leaves));
    }

    private static float require(float value, float min, float max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be " + min + ".." + max + ", got " + value);
        }
        return value;
    }
}
