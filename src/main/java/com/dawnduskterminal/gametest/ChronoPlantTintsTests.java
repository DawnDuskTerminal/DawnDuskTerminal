package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.ChronoPlantTints;
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
 * 植物着色的规则：稀疏丛林彩虹、河海类紫色，其余群系不动
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
