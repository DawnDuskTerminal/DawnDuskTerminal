package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModChronoOres;
import com.dawnduskterminal.registry.ModItems;
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
import net.minecraft.world.item.BlockItem;
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

    /**
     * 两块恶魂皮：音效与挖掘属性照原版羊毛，物品放下就是这两块方块
     *
     * <p>羊毛那一档是 0.8 点硬度、{@code WOOL} 音效、不要求工具、挖掉掉自身；
     * 物品那一侧是 {@link BlockItem}，名字取的就是 {@code block.} 那条翻译键
     */
    @GameTest(template = "ddt_empty")
    public static void leatherBlocksCopyWool(GameTestHelper helper) {
        Block ghast = ModBlocks.CARMINITE_GHAST_LEATHER.get();
        Block carminited = ModBlocks.CARMINITED_LEATHER.get();
        for (Block block : new Block[] {ghast, carminited}) {
            assertSound(helper, block, SoundType.WOOL);
            float hardness = block.defaultBlockState().getDestroySpeed(helper.getLevel(), BlockPos.ZERO);
            helper.assertTrue(hardness == 0.8F, block + " must be as hard as wool (0.8), got " + hardness);
            helper.assertFalse(
                    block.defaultBlockState().requiresCorrectToolForDrops(),
                    block + " must not require a tool, wool does not either");
        }
        helper.assertTrue(
                ModItems.CARMINITE_GHAST_LEATHER.get() instanceof BlockItem ghastItem && ghastItem.getBlock() == ghast,
                "the ghast leather item must place the ghast leather block, got "
                        + ModItems.CARMINITE_GHAST_LEATHER.get());
        helper.assertTrue(
                ModItems.CARMINITED_LEATHER.get() instanceof BlockItem carminitedItem
                        && carminitedItem.getBlock() == carminited,
                "the carminited leather item must place the carminited leather block, got "
                        + ModItems.CARMINITED_LEATHER.get());
        helper.assertTrue(
                ModItems.CARMINITE_GHAST_LEATHER
                        .get()
                        .getDescriptionId()
                        .equals("block.dawnduskterminal.carminite_ghast_leather"),
                "a block item takes its name from the block, got "
                        + ModItems.CARMINITE_GHAST_LEATHER.get().getDescriptionId());
        for (String name : new String[] {"carminite_ghast_leather", "carminited_leather"}) {
            JsonObject loot = json(helper, "/data/dawnduskterminal/loot_table/blocks/" + name + ".json");
            helper.assertTrue(loot != null, "loot_table/blocks/" + name + ".json is missing");
            helper.assertTrue(
                    loot.getAsJsonArray("pools")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonArray("entries")
                            .get(0)
                            .getAsJsonObject()
                            .get("name")
                            .getAsString()
                            .equals("dawnduskterminal:" + name),
                    name + " must drop itself, otherwise breaking it eats the item");
        }
        helper.succeed();
    }

    /**
     * 高塔恶魂皮那一块的六个朝向：绕 x 转 0/90/180/270，再加两个「先绕 x 转 90、再绕 y 转 90/270」
     *
     * <p>随机的是「哪一面朝上」，六条各走一套不同的旋转。朝向不存方块状态，服务端看不见，所以这条只读 JSON
     */
    @GameTest(template = "ddt_empty")
    public static void ghastLeatherTurnsInSixDirections(GameTestHelper helper) {
        String name = "carminite_ghast_leather";
        JsonArray variants = variants(helper, name);
        helper.assertTrue(variants.size() == 6, name + " must ship six random orientations, got " + variants.size());
        Set<String> turns = new LinkedHashSet<>();
        for (JsonElement element : variants) {
            JsonObject variant = element.getAsJsonObject();
            helper.assertTrue(
                    variant.get("model").getAsString().equals("dawnduskterminal:block/" + name),
                    name + " must reuse its own model, got "
                            + variant.get("model").getAsString());
            turns.add((variant.has("x") ? variant.get("x").getAsInt() : 0) + "/"
                    + (variant.has("y") ? variant.get("y").getAsInt() : 0));
        }
        helper.assertTrue(
                turns.equals(Set.of("0/0", "180/0", "90/0", "270/0", "90/90", "90/270")),
                name + " must use six distinct orientations, got " + turns);
        helper.succeed();
    }

    /**
     * 活性恶魂皮那一块：方块模型贴 8x8 的连纹图集，粒子与物品图标贴 16x16 单图
     *
     * <p>与 {@code mounted_towerwood} 同一套：方块模型的 {@code all} 指连纹图集，
     * UV 由 {@code MountedTowerWoodModel} 按邻格逐面重映射；物品图标仍是 {@code item/generated} 的皮革
     */
    @GameTest(template = "ddt_empty")
    public static void carminitedLeatherUsesTheConnectedSheet(GameTestHelper helper) {
        String name = "carminited_leather";
        JsonObject state = json(helper, "/assets/dawnduskterminal/blockstates/" + name + ".json");
        helper.assertTrue(state != null, "blockstates/" + name + ".json is missing");
        JsonElement variant = state.getAsJsonObject("variants").get("");
        helper.assertTrue(
                variant != null && variant.isJsonObject(),
                name + " has a single plain state, its variant must not be a list, got " + variant);
        helper.assertTrue(
                variant.getAsJsonObject().get("model").getAsString().equals("dawnduskterminal:block/" + name),
                name + " must point at its own block model, got " + variant);

        JsonObject model = json(helper, "/assets/dawnduskterminal/models/block/" + name + ".json");
        helper.assertTrue(model != null, "models/block/" + name + ".json is missing");
        helper.assertTrue(
                model.get("parent").getAsString().equals("minecraft:block/cube_all"),
                name + " must parent minecraft:block/cube_all, got " + model.get("parent"));
        JsonObject textures = model.getAsJsonObject("textures");
        helper.assertTrue(
                textures.get("all").getAsString().equals("dawnduskterminal:block/carminite_leather_connected"),
                name + " must draw the connected sheet, got " + textures);
        helper.assertTrue(
                textures.get("particle").getAsString().equals("dawnduskterminal:block/carminite_leather"),
                name + " must take particles from the plain 16x16, got " + textures);

        JsonObject item = json(helper, "/assets/dawnduskterminal/models/item/" + name + ".json");
        helper.assertTrue(item != null, "models/item/" + name + ".json is missing");
        helper.assertTrue(
                item.get("parent").getAsString().endsWith("item/generated"),
                name + " keeps its flat leather icon, got " + item.get("parent"));
        helper.assertTrue(
                item.getAsJsonObject("textures")
                        .get("layer0")
                        .getAsString()
                        .equals("dawnduskterminal:item/carminiteleather"),
                name + " must keep its own item texture");
        helper.succeed();
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
