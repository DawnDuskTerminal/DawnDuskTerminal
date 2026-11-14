package com.dawnduskterminal.gametest;

import com.dawnduskterminal.client.animation.YsmPacks;
import com.dawnduskterminal.geckolib3.geo.raw.pojo.Converter;
import com.dawnduskterminal.geckolib3.geo.raw.pojo.RawGeoModel;
import com.dawnduskterminal.geckolib3.geo.raw.tree.RawGeometryTree;
import com.dawnduskterminal.geckolib3.geo.render.GeoBuilder;
import com.dawnduskterminal.geckolib3.geo.render.built.GeoBone;
import com.dawnduskterminal.geckolib3.geo.render.built.GeoModel;
import com.dawnduskterminal.geckolib3.util.json.JsonAnimationUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 实体模型与动画在 jar 里齐不齐、能不能被解析
 *
 * <p>模型加载失败是静默的：几何文件缺失只会让实体停在初始姿势，动画文件缺一条片段只会让某个通道不动，
 * 两者都不会抛异常。所以这里在服务端把每个实体的声明文件读一遍，至少保证「文件在、JSON 能解析、
 * 片段数与预期一致」。
 *
 * <p>文件直接从 classpath 读（{@code /assets/dawnduskterminal/...}），不走服务端的
 * {@code ResourceManager}：后者只提供 {@code data/}，{@code assets/} 在专用服务端上根本不可见。
 *
 * <p>只做结构化检查，不求值表达式：Molang 求值要客户端的绑定表，专用服务端上不该加载它们。
 * 表达式能不能求对只能在客户端里看。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class YsmPackResourceTests {
    /** 每个实体三份文件：几何、动画、贴图，路径与渲染器里的常量一一对应；动画为 null 表示这一套还没有动画 */
    private static final String[][] ENTITY_FILES = {
        {"geo/ghast_elite.geo.json", "animations/ghast_elite.animation.json", "textures/entity/ghast_elite.png", "2"},
        {"geo/ghastfruity.geo.json", "animations/ghast_universal.animation.json", "textures/entity/ghastfruity.png", "1"
        },
        {
            "geo/ghastfruity_hasblock.geo.json",
            "animations/ghast_universal.animation.json",
            "textures/entity/ghastfruity.png",
            "1"
        },
        {"geo/string.geo.json", "animations/string.animation.json", "textures/block/portal_fluid_still.png", "2"},
        {
            "geo/string_large.geo.json",
            "animations/string_large.animation.json",
            "textures/block/portal_fluid_flow.png",
            "2"
        },
        {"geo/expowheel.geo.json", "animations/expowheel.animation.json", "ysm/tower_ghast/textures/ghast.png", "1"},
        {"geo/e_57_blade.geo.json", "animations/e_57_blade.animation.json", "textures/entity/e_57_blade.png", "1"},
        // 备用模型：还没有实体接线，但文件必须在，免得以后接线时才发现少东西
        {"geo/ghasbigail.geo.json", null, "textures/entity/ghasbigail.png", "0"},
        {
            "geo/ghayelliupiter.geo.json",
            "animations/ghayelliupiter.animation.json",
            "textures/entity/ghayelliupiter.png",
            "6"
        },
    };

    @GameTest(template = "ddt_empty")
    public static void entityFilesAreComplete(GameTestHelper helper) {
        for (String[] entry : ENTITY_FILES) {
            checkGeometry(helper, entry[0]);
            if (entry[2] != null) {
                helper.assertTrue(open(entry[2]) != null, "缺少贴图 " + entry[2]);
            }
            if (entry[1] != null) {
                checkAnimations(helper, entry[1], Integer.parseInt(entry[3]));
            }
        }
        helper.succeed();
    }

    /** 高塔恶魂是 YSM 角色包（测试实体，后续会删），它的包结构仍然是目录式 */
    @GameTest(template = "ddt_empty")
    public static void towerGhastPackIsIntact(GameTestHelper helper) {
        for (String path : new String[] {
            "models/main.json",
            "animations/main.animation.json",
            "animations/extra.animation.json",
            "textures/ghast.png",
            "ysm.json"
        }) {
            helper.assertTrue(open(packFile("tower_ghast", path)) != null, "缺少模型包文件 " + path);
        }
        checkGeometry(helper, packFile("tower_ghast", "models/main.json"));
        checkAnimations(helper, packFile("tower_ghast", "animations/main.animation.json"), 50);
        checkAnimations(helper, packFile("tower_ghast", "animations/extra.animation.json"), 22);
        helper.succeed();
    }

    /**
     * 包内键名：{@code main.animation.json} 必须解析成 {@code main}
     *
     * <p>解析成 {@code main.animation} 会让 {@code YsmPack.animation()} 找不到主文件、退回排序第一，
     * 症状是所有形态叠加显示、走与站都没有动作。这条断言钉住它。
     */
    @GameTest(template = "ddt_empty")
    public static void animationFileKeysAreStable(GameTestHelper helper) {
        helper.assertTrue(
                "main"
                        .equals(YsmPacks.nameFor(
                                "ysm/tower_ghast/animations",
                                "ysm/tower_ghast/animations/main.animation.json",
                                ".json")),
                "main.animation.json 的键应为 main");
        helper.assertTrue(
                "main".equals(YsmPacks.nameFor("ysm/tower_ghast/models", "ysm/tower_ghast/models/main.json", ".json")),
                "models/main.json 的键应为 main");
        helper.succeed();
    }

    private static void checkGeometry(GameTestHelper helper, String path) {
        InputStream stream = open(path);
        helper.assertTrue(stream != null, "缺少几何文件 " + path);
        if (stream == null) {
            return;
        }
        int cubes = 0;
        try (InputStream input = stream) {
            RawGeoModel raw = Converter.fromInputStream(input);
            helper.assertTrue(raw != null && raw.getMinecraftGeometry() != null, "几何文件为空 " + path);
            GeoModel model = GeoBuilder.getGeoBuilder().constructGeoModel(RawGeometryTree.parseHierarchy(raw));
            helper.assertTrue(!model.topLevelBones().isEmpty(), "几何文件里没有骨骼 " + path);
            for (GeoBone bone : model.topLevelBones()) {
                cubes += countCubes(bone);
            }
        } catch (IOException | RuntimeException error) {
            helper.assertTrue(false, "几何文件读取失败 " + path + "：" + error);
        }
        helper.assertTrue(cubes > 0, "几何文件里没有可渲染的立方体 " + path);
    }

    private static void checkAnimations(GameTestHelper helper, String path, int expectedClips) {
        InputStream stream = open(path);
        helper.assertTrue(stream != null, "缺少动画文件 " + path);
        if (stream == null) {
            return;
        }
        try (InputStream input = stream) {
            JsonObject json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            int clips = JsonAnimationUtils.getAnimations(json).size();
            helper.assertTrue(clips == expectedClips, path + " 的片段数应为 " + expectedClips + "，实际 " + clips);
        } catch (IOException | RuntimeException error) {
            helper.assertTrue(false, "动画文件读取失败 " + path + "：" + error);
        }
    }

    private static InputStream open(String path) {
        return YsmPackResourceTests.class.getResourceAsStream("/assets/dawnduskterminal/" + path);
    }

    private static int countCubes(GeoBone bone) {
        int cubes = bone.cubes() == null ? 0 : bone.cubes().getCubeCount();
        for (GeoBone child : bone.children()) {
            cubes += countCubes(child);
        }
        return cubes;
    }

    private static String packFile(String pack, String path) {
        return "ysm/" + pack + "/" + path;
    }
}
