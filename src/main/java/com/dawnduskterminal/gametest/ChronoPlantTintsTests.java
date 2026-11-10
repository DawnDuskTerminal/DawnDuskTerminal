package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.ChronoPlantTints;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 植物着色的规则：稀疏丛林彩虹、河海类紫色、仙灵石竹按坐标偏蓝绿，其余群系不动
 *
 * <p>取色本身在客户端跑，但规则是纯函数（{@link ChronoPlantTints}），所以能在专用服的游戏测试里直接断言。
 * 这一条同样钉住「副本在调色板标签里」：规则写在路径上，群系没进维度就等于没写
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class ChronoPlantTintsTests {
    private static final String MOD_ID = "dawnduskterminal";

    private static final TagKey<Biome> PALETTE =
            TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MOD_ID, "palette_biomes"));

    private static final List<String> RIVER_OR_OCEAN = List.of(
            "river",
            "frozen_river",
            "ocean",
            "deep_ocean",
            "cold_ocean",
            "deep_cold_ocean",
            "lukewarm_ocean",
            "deep_lukewarm_ocean",
            "warm_ocean",
            "frozen_ocean",
            "deep_frozen_ocean");

    /** 河海类的紫色必须与 portal_fluid 的浸没雾色同一个值：0x7F159E（127, 21, 158） */
    private static final int PORTAL_FOG = 0x7F159E;

    /**
     * 稀疏丛林的草与树叶都是彩虹色，而且<b>不能在坐标上成格</b>
     *
     * <p>暮色森林彩虹叶那个公式是线性的三角折返，等价于每 32 格平铺一次，地图上一眼就能看出来。
     * 这条用例把「没有短周期」写成断言：在旧公式会命中的 16/32 格位移上，颜色必须几乎都不相同
     */
    @GameTest(template = "ddt_empty")
    public static void sparseJungleRainbowHasNoVisiblePeriod(GameTestHelper helper) {
        ResourceLocation biome = DawnDuskTerminal.id("sparse_jungle");
        Set<Integer> colors = new LinkedHashSet<>();
        Set<Integer> sectors = new LinkedHashSet<>();
        for (int x = -128; x <= 128; x += 4) {
            for (int z = -128; z <= 128; z += 4) {
                int foliage = ChronoPlantTints.foliage(biome, x, z, 0x91BD59);
                int grass = ChronoPlantTints.grass(biome, x, z, 0x91BD59);
                helper.assertTrue(
                        foliage == grass,
                        "the grass of sparse_jungle must follow its leaves, " + x + "," + z + " differs");
                helper.assertTrue(
                        foliage == ChronoPlantTints.rainbow(x, z),
                        "the rainbow tint at " + x + "," + z + " must come from the rainbow rule");
                helper.assertTrue(
                        (foliage & 0xFF000000) == 0,
                        "a biome tint must be 0xRRGGBB without an alpha channel, got " + Integer.toHexString(foliage));
                colors.add(foliage);
                int sector = hueSector(foliage);
                if (sector >= 0) {
                    sectors.add(sector);
                }
            }
        }
        helper.assertTrue(
                colors.size() > 400,
                "the rainbow tint must vary all over the map, only " + colors.size() + " colours in the grid");
        helper.assertTrue(
                sectors.size() == 6,
                "a rainbow must cover the whole hue circle, only " + sectors.size() + " of 6 sectors showed up");

        int matched = 0;
        int samples = 0;
        int[][] offsets = {{16, 0}, {32, 0}, {0, 16}, {0, 32}, {32, 32}};
        for (int x = -100; x <= 100; x += 20) {
            for (int z = -100; z <= 100; z += 20) {
                int base = ChronoPlantTints.rainbow(x, z);
                for (int[] offset : offsets) {
                    samples++;
                    if (ChronoPlantTints.rainbow(x + offset[0], z + offset[1]) == base) {
                        matched++;
                    }
                }
            }
        }
        helper.assertTrue(
                matched * 100 < samples * 5,
                "the rainbow must not repeat on the old 16/32 block lattice: " + matched + " of " + samples
                        + " shifted samples were identical");
        helper.succeed();
    }

    /**
     * 主世界的同名群系一律不动
     *
     * <p>规则只看路径的话，{@code minecraft:sparse_jungle}、{@code minecraft:river} 会被一起染上，
     * 等于改了原版世界。这条用例把命名空间钉住。
     */
    @GameTest(template = "ddt_empty")
    public static void overworldBiomesKeepTheirOwnTint(GameTestHelper helper) {
        for (String path :
                new String[] {"sparse_jungle", "river", "ocean", "swamp", "plains", "jungle", "dark_forest"}) {
            ResourceLocation vanilla = ResourceLocation.withDefaultNamespace(path);
            helper.assertFalse(ChronoPlantTints.isChrono(vanilla), vanilla + " is not a chrono biome");
            helper.assertTrue(
                    ChronoPlantTints.grass(vanilla, 8, 8, 0x91BD59) == 0x91BD59,
                    "the overworld " + path + " must keep its grass tint");
            helper.assertTrue(
                    ChronoPlantTints.foliage(vanilla, 8, 8, 0x79C05A) == 0x79C05A,
                    "the overworld " + path + " must keep its foliage tint");
            helper.assertFalse(ChronoPlantTints.isRiverOrOcean(vanilla), vanilla + " must not count as a chrono river");
        }
        helper.succeed();
    }

    /** 两条河与九个海洋：草与树叶都固定 portal_fluid 的雾色，原色不参与计算 */
    @GameTest(template = "ddt_empty")
    public static void riversAndOceansUseThePortalFluidFogColour(GameTestHelper helper) {
        helper.assertTrue(
                ChronoPlantTints.PURPLE == PORTAL_FOG,
                "the river and ocean tint must be the portal fluid fog colour " + Integer.toHexString(PORTAL_FOG)
                        + ", got " + Integer.toHexString(ChronoPlantTints.PURPLE));
        for (String path : RIVER_OR_OCEAN) {
            ResourceLocation biome = DawnDuskTerminal.id(path);
            helper.assertTrue(ChronoPlantTints.isRiverOrOcean(biome), path + " must be treated as a river or ocean");
            for (int original : new int[] {0x91BD59, 0x79C05A, 0xFFFFFF}) {
                helper.assertTrue(
                        ChronoPlantTints.foliage(biome, 12, -34, original) == ChronoPlantTints.PURPLE,
                        path + " foliage must be the purple tint");
                helper.assertTrue(
                        ChronoPlantTints.grass(biome, 12, -34, original) == ChronoPlantTints.PURPLE,
                        path + " grass must be the purple tint");
            }
        }
        // 沙滩与石岸不是河海，别顺手染上
        for (String path : List.of("beach", "snowy_beach", "stony_shore")) {
            ResourceLocation biome = DawnDuskTerminal.id(path);
            helper.assertTrue(
                    ChronoPlantTints.grass(biome, 0, 0, 0x91BD59) == 0x91BD59, path + " must keep its own grass tint");
        }
        helper.succeed();
    }

    /** 其余群系原样交回；魔法森林与黑森林腹地的原有染色不受影响 */
    @GameTest(template = "ddt_empty")
    public static void otherBiomesKeepTheirOwnTint(GameTestHelper helper) {
        for (String path : List.of("plains", "forest", "savanna", "snowy_plains")) {
            ResourceLocation biome = DawnDuskTerminal.id(path);
            helper.assertTrue(
                    ChronoPlantTints.grass(biome, 8, 8, 0x91BD59) == 0x91BD59, path + " grass must stay untouched");
            helper.assertTrue(
                    ChronoPlantTints.foliage(biome, 8, 8, 0x79C05A) == 0x79C05A, path + " foliage must stay untouched");
        }

        ResourceLocation jungle = DawnDuskTerminal.id("jungle");
        helper.assertTrue(
                ChronoPlantTints.foliage(jungle, 128, 256, 0x79C05A) != 0x79C05A,
                "the enchanted forest tint of jungle must still be applied to its leaves");
        helper.assertTrue(
                ChronoPlantTints.grass(jungle, 128, 256, 0x91BD59) == 0x91BD59, "jungle grass must stay untouched");

        ResourceLocation darkForest = DawnDuskTerminal.id("dark_forest");
        helper.assertTrue(
                ChronoPlantTints.foliage(darkForest, 3, 5, 0x79C05A) == ChronoPlantTints.DARK_FOREST_FOLIAGE,
                "dark_forest leaves must stay the fixed dark_forest_center colour");
        helper.assertTrue(
                ChronoPlantTints.grass(darkForest, 3, 5, 0x91BD59) == 0x91BD59,
                "dark_forest grass must stay untouched");
        helper.succeed();
    }

    /** 规则写在群系路径上，所以这些群系必须真的在维度的调色板标签里 */
    @GameTest(template = "ddt_empty")
    public static void theTintedBiomesArePartOfTheDimension(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.BIOME);
        Set<String> present = new LinkedHashSet<>();
        int checked = 0;
        for (Holder<Biome> holder : biomes.getTag(PALETTE).orElseThrow()) {
            ResourceLocation key = holder.unwrapKey().map(ResourceKey::location).orElse(null);
            if (key == null) {
                continue;
            }
            present.add(key.getPath());
            checked++;
        }
        helper.assertTrue(checked > 40, "the palette tag must resolve to the biome copies, got " + checked);
        helper.assertTrue(
                present.contains("sparse_jungle"), "sparse_jungle must be part of the chrono palette, got " + present);
        for (String path : RIVER_OR_OCEAN) {
            helper.assertTrue(present.contains(path), path + " must be part of the chrono palette");
        }
        helper.succeed();
    }

    /**
     * 色相带：档位到色相偏移量的映射，两端正好把正中青 180° 转到标准绿 120° 与标准蓝 240°
     *
     * <p>色相是环形比例，不是乘数：偏移在贴图里烘成 25 条带（{@code tools/generate_hue_sheets.py}），
     * 每像素只换 HSV 的 h，明度 V 不变。所以这一条钉的是「档位 → 偏移角度」这条换算，
     * 以及两端是否真的落在 120° 与 240° 上
     */
    @GameTest(template = "ddt_empty")
    public static void fairyDianthusBandsCoverTheStandardHues(GameTestHelper helper) {
        helper.assertTrue(
                ChronoPlantTints.fairyDianthusHueShift(ChronoPlantTints.DIANTHUS_NEUTRAL_BAND) == 0.0F,
                "the neutral band must shift nothing, got "
                        + ChronoPlantTints.fairyDianthusHueShift(ChronoPlantTints.DIANTHUS_NEUTRAL_BAND));
        float blueEnd = ChronoPlantTints.fairyDianthusHueShift(0);
        float greenEnd = ChronoPlantTints.fairyDianthusHueShift(ChronoPlantTints.DIANTHUS_BANDS - 1);
        helper.assertTrue(
                blueEnd > 0.0F && greenEnd < 0.0F,
                "band 0 must be the blue end (positive shift) and the last band the green end");
        // 底图是正中青 180°：第 0 档 +60° 落到 240°（标准蓝），末档 −60° 落到 120°（标准绿）
        helper.assertTrue(
                Math.abs(180.0F + blueEnd - 240.0F) < 0.01F,
                "band 0 must land on the standard blue hue 240, got " + (180.0F + blueEnd));
        helper.assertTrue(
                Math.abs(180.0F + greenEnd - 120.0F) < 0.01F,
                "the last band must land on the standard green hue 120, got " + (180.0F + greenEnd));
        // 相邻档的步长一致，档位越界要夹住
        for (int band = 1; band < ChronoPlantTints.DIANTHUS_BANDS; band++) {
            float step =
                    ChronoPlantTints.fairyDianthusHueShift(band - 1) - ChronoPlantTints.fairyDianthusHueShift(band);
            helper.assertTrue(
                    Math.abs(step - ChronoPlantTints.DIANTHUS_HUE_STEP) < 0.001F,
                    "band " + band + " must differ from the previous one by " + ChronoPlantTints.DIANTHUS_HUE_STEP
                            + " degrees, got " + step);
        }
        helper.assertTrue(
                ChronoPlantTints.fairyDianthusHueShift(-7) == blueEnd, "a band below zero must clamp to the blue end");
        helper.assertTrue(
                ChronoPlantTints.fairyDianthusHueShift(999) == greenEnd,
                "a band past the end must clamp to the green end");
        helper.succeed();
    }

    /**
     * 三种植物的档位由坐标决定：整张图都用得上，而且没有 16/32 格那种周期
     *
     * <p>档位是 {@code 0..DIANTHUS_BANDS-1} 的整数，取样网格里必须两端都摸得到、而且组合够多；
     * 旧公式那种 16/32 格平铺在这里同样不许出现
     */
    @GameTest(template = "ddt_empty")
    public static void fairyDianthusBandsVaryWithPosition(GameTestHelper helper) {
        Set<Integer> bands = new LinkedHashSet<>();
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int x = -96; x <= 96; x += 3) {
            for (int z = -96; z <= 96; z += 3) {
                int band = ChronoPlantTints.fairyDianthusBand(x, z);
                bands.add(band);
                lowest = Math.min(lowest, band);
                highest = Math.max(highest, band);
                helper.assertTrue(
                        band >= 0 && band < ChronoPlantTints.DIANTHUS_BANDS,
                        "at " + x + "," + z + " the band " + band + " is out of range");
            }
        }
        helper.assertTrue(
                bands.size() > 20,
                "the hue bands must be used all over the map, only " + bands.size() + " of "
                        + ChronoPlantTints.DIANTHUS_BANDS + " showed up");
        helper.assertTrue(lowest <= 3, "the blue end of the range must show up, lowest band was " + lowest);
        helper.assertTrue(highest >= 20, "the green end of the range must show up, highest band was " + highest);

        int matched = 0;
        int samples = 0;
        int[][] offsets = {{16, 0}, {32, 0}, {0, 16}, {0, 32}, {32, 32}};
        for (int x = -100; x <= 100; x += 20) {
            for (int z = -100; z <= 100; z += 20) {
                int base = ChronoPlantTints.fairyDianthusBand(x, z);
                for (int[] offset : offsets) {
                    samples++;
                    if (ChronoPlantTints.fairyDianthusBand(x + offset[0], z + offset[1]) == base) {
                        matched++;
                    }
                }
            }
        }
        helper.assertTrue(
                matched * 100 < samples * 12,
                "the hue bands must not repeat on the old 16/32 block lattice: " + matched + " of " + samples
                        + " shifted samples were identical");
        helper.succeed();
    }

    /**
     * 手持物品用原图，方块才用色相带
     *
     * <p>色相偏移是烘进方块贴图里的，手持/掉落那一份如果直接父到方块模型就会一起带上带图——
     * 在手里看到的是一条被压扁的带图，不是这株植物的样子。这条用例把两边的模型文件钉住：
     * 物品模型必须显式覆盖成原图，方块模型必须仍是带图
     */
    @GameTest(template = "ddt_empty")
    public static void itemsUseThePlainTexture(GameTestHelper helper) {
        for (String name : List.of("fairy_dianthus", "fairy_dianthus_small")) {
            String model = resource("/assets/dawnduskterminal/models/item/" + name + ".json");
            helper.assertTrue(model != null, "models/item/" + name + ".json must ship inside the mod resources");
            helper.assertFalse(
                    model.contains("_hues"), "the held item of " + name + " must not use the hue sheet, got " + model);
            helper.assertTrue(
                    model.contains("\"0\": \"dawnduskterminal:block/" + name + "\""),
                    "models/item/" + name + ".json must point at the plain block texture, got " + model);
        }
        for (String block : List.of("block/fairy_dianthus_small", "block/fairy_dianthus/fairy_dianthus_stem_0000")) {
            String model = resource("/assets/dawnduskterminal/models/" + block + ".json");
            helper.assertTrue(model != null, "models/" + block + ".json must ship inside the mod resources");
            helper.assertTrue(
                    model.contains("_hues"), "the block model " + block + " must use the hue sheet, got " + model);
        }
        helper.succeed();
    }

    /**
     * 拐角模型逐面核对：转向件与向下拐角六个面各用哪块贴图
     *
     * <p>转向件：四个侧面按连接挑 12 像素高的那条，<b>上端</b>是 8×8 截面、<b>下端</b>是底面视图
     * `(0,0)-(8,8)`（示例如此，不是截面）。它「朝世界固定方向」靠整份模型做 y 旋转解决，
     * 不是靠换贴图。
     *
     * <p>向下拐角是转向件的竖直镜像（y → 16 − y）：四个侧面留在原方向、uv 上下对调；
     * <b>上下面互换</b>再各自对调。所以拐角的上端拿到的是转向件下端那块，反之亦然。
     */
    @GameTest(template = "ddt_empty")
    public static void cornerModelsFollowTheConnection(GameTestHelper helper) {
        JsonObject turn = jsonModel("/assets/dawnduskterminal/models/block/fairy_dianthus/fairy_dianthus_turn.json");
        JsonObject drop = jsonModel("/assets/dawnduskterminal/models/block/fairy_dianthus/fairy_dianthus_drop.json");
        helper.assertTrue(turn != null, "fairy_dianthus_turn.json must ship inside the mod resources");
        helper.assertTrue(drop != null, "fairy_dianthus_drop.json must ship inside the mod resources");

        assertFaces(helper, "turn", faces(turn), new float[][] {
            {0, 0, 4, 6}, {8, 8, 12, 14}, {0, 0, 4, 6}, {8, 0, 12, 6}, {12, 0, 16, 4}, {0, 0, 4, 4}
        });
        assertFaces(helper, "drop", faces(drop), new float[][] {
            {0, 6, 4, 0}, {8, 14, 12, 8}, {0, 6, 4, 0}, {8, 6, 12, 0}, {0, 4, 4, 0}, {12, 4, 16, 0}
        });

        JsonObject blockstate = jsonModel("/assets/dawnduskterminal/blockstates/fairy_dianthus.json");
        helper.assertTrue(blockstate != null, "blockstates/fairy_dianthus.json must ship inside the mod resources");
        for (JsonElement element : blockstate.getAsJsonArray("multipart")) {
            JsonObject entry = element.getAsJsonObject();
            JsonObject when = entry.getAsJsonObject("when");
            if (!when.has("part")) {
                continue;
            }
            String part = when.get("part").getAsString();
            if (!part.equals("turn") && !part.equals("drop")) {
                continue;
            }
            helper.assertTrue(
                    entry.getAsJsonObject("apply").has("y"),
                    "every " + part + " entry must carry a y rotation so the corner follows its connector, got "
                            + entry);
        }
        helper.succeed();
    }

    /** 六个面按 FACES 的顺序逐个核对 uv */
    private static void assertFaces(GameTestHelper helper, String label, JsonObject faces, float[][] expected) {
        String[] order = {"north", "east", "south", "west", "up", "down"};
        for (int i = 0; i < order.length; i++) {
            JsonArray uv = faces.getAsJsonObject(order[i]).getAsJsonArray("uv");
            float[] want = expected[i];
            boolean same = uv.get(0).getAsFloat() == want[0]
                    && uv.get(1).getAsFloat() == want[1]
                    && uv.get(2).getAsFloat() == want[2]
                    && uv.get(3).getAsFloat() == want[3];
            helper.assertTrue(
                    same, label + " face " + order[i] + " must be " + java.util.Arrays.toString(want) + ", got " + uv);
        }
    }

    /** 取模型里那个元素的 faces */
    private static JsonObject faces(JsonObject model) {
        return model.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces");
    }

    /** 读一个 JSON 资源；拿不到返回 null */
    private static JsonObject jsonModel(String path) {
        String text = resource(path);
        return text == null ? null : JsonParser.parseString(text).getAsJsonObject();
    }

    /** 模组资源里的一个文本文件；拿不到返回 null */
    private static String resource(String path) {
        try (InputStream stream = ChronoPlantTintsTests.class.getResourceAsStream(path)) {
            return stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * 色相所在的那一段：0 红→黄、1 黄→绿、2 绿→青、3 青→蓝、4 蓝→品红、5 品红→红。
     * 有两个通道相等时判不出来，返回 -1
     */
    private static int hueSector(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        if (max == min) {
            return -1;
        }
        int middle = red + green + blue - max - min;
        if (max == red) {
            return middle == green ? 0 : 5;
        }
        if (max == green) {
            return middle == red ? 1 : 2;
        }
        return middle == green ? 3 : 4;
    }
}
