package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModChronoOres;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 石类方块的随机纹理与音效
 *
 * <p>随机纹理是方块状态里的一张变体列表，客户端按方块位置哈希挑一条，服务端根本看不见。
 * 所以这两条必须直接读 classpath 里的资源文件：变体数、绕哪个轴转、有没有镜像模型，
 * 都能从 JSON 里读出来。
 *
 * <p>关键的一件事：<b>侧面只有靠镜像模型才会变</b>。整块绕 Y 轴转 90°/180° 只是换了一张同样的面，
 * 四个侧面的像素一模一样；只有换上 {@code cube_mirrored_all} 那套 UV 翻过来的模型，侧面才会
 * 出现正反两种。所以两组都带镜像模型：
 *
 * <ul>
 *   <li>有向（层理贴图）：原版 {@code minecraft:block/stone} 那四条，模型与镜像模型各转 0/180；
 *   <li>无向（噪点贴图）：四个 90° 旋转各配一份镜像，共八条。
 * </ul>
 *
 * <p>音效那一条走方块自己：紫苏岩一档（紫苏岩本体、赤钼矿、狄拉克矿与 {@code charnockite_}）
 * 必须与深板岩同音效。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class BlockStyleTests {
    /** 有向随机：苍穹石、绿片岩、暮光石、粉砂岩、榴辉岩 */
    private static final String[] DIRECTIONAL = {
        "canopy_stone", "greenschist", "twilight_stone", "siltstone", "eclogite"
    };

    /** 无向随机：紫苏岩、路之沙、不醒沙、混乱石 */
    private static final String[] NON_DIRECTIONAL = {"charnockite", "road_sand", "unconscious_sand", "chaos_stone"};

    /** 原版 stone 那套：模型与镜像模型各转 0/180，共四条 */
    @GameTest(template = "ddt_empty")
    public static void directionalStonesUseTheMirroredVariants(GameTestHelper helper) {
        for (String name : DIRECTIONAL) {
            JsonArray variants = variants(helper, name);
            helper.assertTrue(variants.size() == 4, name + " must ship four random variants, got " + variants.size());
            Set<String> models = new LinkedHashSet<>();
            Set<Integer> rotations = new LinkedHashSet<>();
            for (JsonElement element : variants) {
                JsonObject variant = element.getAsJsonObject();
                models.add(variant.get("model").getAsString());
                rotations.add(variant.has("y") ? variant.get("y").getAsInt() : 0);
            }
            helper.assertTrue(
                    models.equals(
                            Set.of("dawnduskterminal:block/" + name, "dawnduskterminal:block/" + name + "_mirrored")),
                    name + " must use both the plain and the mirrored model, got " + models);
            helper.assertTrue(
                    rotations.size() == 2 && rotations.contains(0) && rotations.contains(180),
                    name + " must rotate each model by 0 and 180 degrees only, got " + rotations);
            assertMirroredModel(helper, name);
        }
        helper.succeed();
    }

    /**
     * 无向那组：四个 90° 旋转各配一份镜像，共八条
     *
     * <p>纯旋转的 {@code minecraft:block/sand} 那四条在侧面像素上完全一致，四个侧面不会变；
     * 加进镜像模型之后侧面才有正反两种，这是这几块噪点方块能看到的唯一变化
     */
    @GameTest(template = "ddt_empty")
    public static void directionlessBlocksMirrorTheirQuarterTurns(GameTestHelper helper) {
        for (String name : NON_DIRECTIONAL) {
            JsonArray variants = variants(helper, name);
            helper.assertTrue(variants.size() == 8, name + " must ship eight random variants, got " + variants.size());
            Set<String> models = new LinkedHashSet<>();
            Set<Integer> rotations = new LinkedHashSet<>();
            Set<String> pairs = new LinkedHashSet<>();
            for (JsonElement element : variants) {
                JsonObject variant = element.getAsJsonObject();
                String model = variant.get("model").getAsString();
                int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
                models.add(model);
                rotations.add(y);
                pairs.add(model + "@" + y);
            }
            helper.assertTrue(
                    models.equals(
                            Set.of("dawnduskterminal:block/" + name, "dawnduskterminal:block/" + name + "_mirrored")),
                    name + " must use both the plain and the mirrored model, got " + models);
            helper.assertTrue(
                    rotations.equals(Set.of(0, 90, 180, 270)),
                    name + " must use all four quarter turns, got " + rotations);
            helper.assertTrue(pairs.size() == 8, name + " must pair every quarter turn with both models, got " + pairs);
            assertMirroredModel(helper, name);
        }
        helper.succeed();
    }

    /**
     * 紫苏岩那一档与深板岩同音效，苍穹石那一档仍是石头音效
     *
     * <p>矿石按宿主石取音效，这条覆盖紫苏岩本体、赤钼矿、狄拉克矿与 {@code charnockite_*} 六块矿石
     */
    @GameTest(template = "ddt_empty")
    public static void charnockiteFamilySoundsLikeDeepslate(GameTestHelper helper) {
        assertSound(helper, ModTerrainBlocks.CHARNOCKITE.get(), SoundType.DEEPSLATE);
        assertSound(helper, ModChronoOres.DEEP_SCARLET.get(), SoundType.DEEPSLATE);
        assertSound(helper, ModChronoOres.DIRAC_ORE.get(), SoundType.DEEPSLATE);
        assertSound(helper, ModTerrainBlocks.CANOPY_STONE.get(), SoundType.STONE);
        for (var holder : ModChronoOres.overworldOres()) {
            Block ore = holder.get();
            String path = BuiltInRegistries.BLOCK.getKey(ore).getPath();
            SoundType expected = path.startsWith("charnockite_") ? SoundType.DEEPSLATE : SoundType.STONE;
            assertSound(helper, ore, expected);
        }
        helper.succeed();
    }

    /**
     * 霞土：原版 {@code dirt} 那四条纯旋转，只转不镜像
     *
     * <p>它的贴图是噪点，侧面翻不翻看不出来，所以这里不配镜像模型——侧面不处理是这一条的既定选择，
     * 不是漏了
     */
    @GameTest(template = "ddt_empty")
    public static void rosyDirtKeepsTheQuarterTurns(GameTestHelper helper) {
        String name = "rosy_dirt";
        JsonArray variants = variants(helper, name);
        helper.assertTrue(variants.size() == 4, name + " must ship four random variants, got " + variants.size());
        Set<String> models = new LinkedHashSet<>();
        Set<Integer> rotations = new LinkedHashSet<>();
        for (JsonElement element : variants) {
            JsonObject variant = element.getAsJsonObject();
            models.add(variant.get("model").getAsString());
            helper.assertFalse(variant.has("x"), name + " only turns around the y axis");
            rotations.add(variant.has("y") ? variant.get("y").getAsInt() : 0);
        }
        helper.assertTrue(
                models.equals(Set.of("dawnduskterminal:block/" + name)),
                name + " must rotate its own single model, got " + models);
        helper.assertTrue(
                rotations.equals(Set.of(0, 90, 180, 270)), name + " must use all four quarter turns, got " + rotations);
        helper.assertTrue(
                json(helper, "/assets/dawnduskterminal/models/block/" + name + "_mirrored.json") == null,
                name + " does not mirror its sides, it must not ship a mirrored model");
        helper.succeed();
    }

    /** 镜像模型必须复用同一张贴图，靠 {@code cube_mirrored_all} 翻 UV，不另出一张 png */
    private static void assertMirroredModel(GameTestHelper helper, String name) {
        JsonObject mirrored = json(helper, "/assets/dawnduskterminal/models/block/" + name + "_mirrored.json");
        helper.assertTrue(mirrored != null, name + "_mirrored model is missing");
        helper.assertTrue(
                mirrored.get("parent").getAsString().equals("minecraft:block/cube_mirrored_all"),
                name + "_mirrored must parent minecraft:block/cube_mirrored_all, got " + mirrored.get("parent"));
        helper.assertTrue(
                mirrored.getAsJsonObject("textures").get("all").getAsString().equals("dawnduskterminal:block/" + name),
                name + "_mirrored must reuse the block's own texture, got " + mirrored.getAsJsonObject("textures"));
    }

    /** 音效取的是 NeoForge 那个带世界与坐标的版本，别用已经弃用的无参重载 */
    private static void assertSound(GameTestHelper helper, Block block, SoundType expected) {
        SoundType actual = block.defaultBlockState().getSoundType(helper.getLevel(), BlockPos.ZERO, null);
        helper.assertTrue(
                actual == expected,
                block + " must use the " + describe(expected) + " sound set, got " + describe(actual));
    }

    /** 音效组没有名字，用它的破坏音标识 */
    private static String describe(SoundType type) {
        return BuiltInRegistries.SOUND_EVENT.getKey(type.getBreakSound()).toString();
    }

    private static JsonArray variants(GameTestHelper helper, String name) {
        JsonObject state = json(helper, "/assets/dawnduskterminal/blockstates/" + name + ".json");
        helper.assertTrue(state != null, "blockstates/" + name + ".json is missing");
        JsonElement variants = state.getAsJsonObject("variants").get("");
        helper.assertTrue(variants != null, name + " must declare its only block state under the empty key");
        helper.assertTrue(
                variants.isJsonArray(), name + " must list several variants for the random texture, got " + variants);
        return variants.getAsJsonArray();
    }

    private static JsonObject json(GameTestHelper helper, String path) {
        try (InputStream stream = BlockStyleTests.class.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception error) {
            helper.assertTrue(false, "failed to read " + path + ": " + error);
            return null;
        }
    }

    private BlockStyleTests() {}
}
