package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModChronoOres;
import com.dawnduskterminal.registry.ModCreativeTabs;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.ChronoVeinPalette;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 维度里的六种主世界矿石换成了自己的两档方块，生成量与高度带也按调色板重算
 *
 * <p>四条线索各钉一段：方块本身（硬度照宿主石、不进创造栏）、特征表（目标方块、次数、各自的
 * 高度带、不区分空气暴露）、战利品表，以及矿脉那条写死的路径——它只换方块，频率不动。
 * 生成参数用 {@link PlacedFeature#DIRECT_CODEC} 编码回 JSON 再读：次数与高度是私有字段，
 * 它的编码结果就是唯一公开读得到的地方
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class OverworldOreTests {
    /** 六条矿石特征。每种矿只有一条，各自的次数与高度带互不相同 */
    private static final List<String> ORE_FEATURES =
            List.of("ore_copper", "ore_iron", "ore_gold", "ore_redstone", "ore_diamond", "ore_lapis");

    /** 原版十二块矿石，顺序与 {@link ModChronoOres#overworldOres()} 一一对应 */
    private static final List<Block> VANILLA_ORES = List.of(
            Blocks.COPPER_ORE,
            Blocks.IRON_ORE,
            Blocks.GOLD_ORE,
            Blocks.REDSTONE_ORE,
            Blocks.LAPIS_ORE,
            Blocks.DIAMOND_ORE,
            Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.DEEPSLATE_IRON_ORE,
            Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE);

    /**
     * 一条矿石特征的期望：每区块次数，以及高度带的两个端点
     *
     * <p>原版每区块尝试分别是铜 32、铁 110、金 4.5、红石 12、青金石 6、钻石 13；这里的次数是调过之后的落地值，
     * 铜、金、红石、青金石、钻石都是六成取整，铁当前是 33（由作者直接调定，改动时这张表跟着改）。
     * 高度带各自不同，按原版的深浅次序从高到低排列；铜是唯一不铺到基岩的一种
     */
    private static final String[][] ORE_LAYOUT = {
        // 特征, 次数, 下界锚点, 下界值, 上界（absolute）
        {"ore_copper", "19", "absolute", "-32", "200"},
        {"ore_iron", "33", "above_bottom", "0", "176"},
        {"ore_gold", "3", "above_bottom", "0", "128"},
        {"ore_redstone", "7", "above_bottom", "0", "112"},
        {"ore_lapis", "4", "above_bottom", "0", "144"},
        {"ore_diamond", "8", "above_bottom", "0", "96"},
    };

    private static PlacedFeature placed(GameTestHelper helper, String path) {
        Registry<PlacedFeature> registry =
                helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        PlacedFeature feature = registry.get(DawnDuskTerminal.id(path));
        helper.assertTrue(feature != null, "placed feature dawnduskterminal:" + path + " must be registered");
        return feature;
    }

    /** 把放置特征编码回 JSON，读里面的次数与高度范围 */
    private static JsonObject encode(PlacedFeature feature) {
        JsonElement encoded = PlacedFeature.DIRECT_CODEC
                .encodeStart(JsonOps.INSTANCE, feature)
                .getOrThrow();
        return encoded.getAsJsonObject();
    }

    private static JsonObject placementOfType(JsonObject encoded, String type) {
        for (JsonElement element : encoded.getAsJsonArray("placement")) {
            JsonObject modifier = element.getAsJsonObject();
            if (modifier.get("type").getAsString().equals(type)) {
                return modifier;
            }
        }
        return null;
    }

    /**
     * 矿脉里写死的那十二块原版矿石，进维度后一律变成两档副本
     *
     * <p>换的只是方块：{@code OreVeinifier} 的频率与高度带没有任何改动，这里也不去碰
     */
    @GameTest(template = "ddt_empty")
    public static void vanillaOresBecomeThePaletteOres(GameTestHelper helper) {
        List<Block> palette = ModChronoOres.overworldOres().stream()
                .map(holder -> (Block) holder.get())
                .toList();
        helper.assertTrue(
                palette.size() == VANILLA_ORES.size(),
                "each vanilla ore needs one palette copy, got " + palette.size() + " copies");
        for (int index = 0; index < VANILLA_ORES.size(); index++) {
            Block vanilla = VANILLA_ORES.get(index);
            Block expected = palette.get(index);
            helper.assertTrue(
                    ChronoVeinPalette.remap(vanilla.defaultBlockState()).is(expected),
                    "vein filler " + vanilla + " must become " + expected);
        }
        helper.succeed();
    }

    /** 硬度照抄宿主石：前六块是苍穹石，后六块是紫苏岩 */
    @GameTest(template = "ddt_empty")
    public static void paletteOresKeepTheirHostStoneHardness(GameTestHelper helper) {
        Block canopy = ModTerrainBlocks.CANOPY_STONE.get();
        Block charnockite = ModTerrainBlocks.CHARNOCKITE.get();
        List<Block> palette = ModChronoOres.overworldOres().stream()
                .map(holder -> (Block) holder.get())
                .toList();
        for (int index = 0; index < palette.size(); index++) {
            Block ore = palette.get(index);
            Block host = index < palette.size() / 2 ? canopy : charnockite;
            helper.assertTrue(
                    ore.defaultDestroyTime() == host.defaultDestroyTime(),
                    ore + " must be as hard as " + host + ", got " + ore.defaultDestroyTime() + " against "
                            + host.defaultDestroyTime());
            helper.assertTrue(
                    ore.defaultBlockState().requiresCorrectToolForDrops(),
                    ore + " must require the correct tool for drops, like every other ore");
        }
        helper.succeed();
    }

    /** 十二块矿石只作为世界生成的产物，创造栏里一个都不出现 */
    @GameTest(template = "ddt_empty")
    public static void overworldOresStayOutOfTheCreativeTabs(GameTestHelper helper) {
        CreativeModeTab tab = ModCreativeTabs.MAIN.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                FeatureFlags.VANILLA_SET, true, helper.getLevel().registryAccess()));
        List<Item> listed =
                tab.getDisplayItems().stream().map(ItemStack::getItem).toList();
        for (var item : ModChronoOres.overworldOreItems()) {
            helper.assertTrue(
                    !listed.contains(item.get()),
                    item.get() + " must stay out of the creative tab, it is world generation only");
        }
        helper.succeed();
    }

    /**
     * 十二份战利品表都要真的加载到
     *
     * <p>方块默认按自己的 id 去 {@code data/dawnduskterminal/loot_table/blocks/<id>.json} 找表，
     * 名字写错时拿到的是空表，挖下来什么都不掉，而且不报错
     */
    @GameTest(template = "ddt_empty")
    public static void paletteOresShipTheirOwnLootTables(GameTestHelper helper) {
        var registries = helper.getLevel().getServer().reloadableRegistries();
        for (var holder : ModChronoOres.overworldOres()) {
            LootTable table = registries.getLootTable(holder.get().getLootTable());
            helper.assertTrue(
                    table != LootTable.EMPTY,
                    "loot table " + holder.get().getLootTable().location() + " for " + holder.get()
                            + " is missing, the block would drop nothing");
        }
        helper.succeed();
    }

    /**
     * 六条特征只放调色板方块，上下两档各一个目标，且不区分空气暴露
     *
     * <p>目标只写一块的后果是整层矿石消失：上层的矿进不了深层的石头，反过来也一样。
     * {@code discard_chance_on_air_exposure} 必须是 0：这套矿不做「埋得深才留得住」那一套
     */
    @GameTest(template = "ddt_empty")
    public static void oreFeaturesPlaceOnlyThePaletteOres(GameTestHelper helper) {
        Set<Block> palette = new HashSet<>(ModChronoOres.overworldOres().stream()
                .map(holder -> (Block) holder.get())
                .toList());
        for (String path : ORE_FEATURES) {
            PlacedFeature feature = placed(helper, path);
            ConfiguredFeature<?, ?> configured = feature.feature().value();
            helper.assertTrue(
                    configured.config() instanceof OreConfiguration, path + " must stay a minecraft:ore feature");
            OreConfiguration configuration = (OreConfiguration) configured.config();
            helper.assertTrue(
                    configuration.discardChanceOnAirExposure == 0.0F,
                    path + " must not differentiate on air exposure, got " + configuration.discardChanceOnAirExposure);
            helper.assertTrue(
                    configuration.targetStates.size() == 2,
                    path + " must target both the stone and the deepslate tier, got "
                            + configuration.targetStates.size());
            for (OreConfiguration.TargetBlockState target : configuration.targetStates) {
                Block placed = target.state.getBlock();
                helper.assertTrue(palette.contains(placed), path + " must place a palette ore, got " + placed);
            }
        }
        helper.succeed();
    }

    /**
     * 次数按原版的六成取整，高度带六种各不相同
     *
     * <p>这条同时守着「别把六种矿写成同一个高度带」：上半部分逐条核对端点，最后再验一遍
     * 六个上界互不相等
     */
    @GameTest(template = "ddt_empty")
    public static void oreFeaturesKeepTheReducedCountsAndTheirOwnHeightBands(GameTestHelper helper) {
        Set<String> tops = new HashSet<>();
        for (String[] row : ORE_LAYOUT) {
            String path = row[0];
            JsonObject encoded = encode(placed(helper, path));
            JsonObject count = placementOfType(encoded, "minecraft:count");
            helper.assertTrue(count != null, path + " must carry a minecraft:count placement");
            helper.assertTrue(
                    count.get("count").getAsInt() == Integer.parseInt(row[1]),
                    path + " must roll " + row[1] + " attempts, got " + count.get("count"));

            JsonObject heightRange = placementOfType(encoded, "minecraft:height_range");
            helper.assertTrue(heightRange != null, path + " must carry a height range");
            JsonObject height = heightRange.getAsJsonObject("height");
            helper.assertTrue(
                    height.get("type").getAsString().equals("minecraft:uniform"),
                    path + " must spread uniformly over its own band, got " + height.get("type"));
            JsonObject min = height.getAsJsonObject("min_inclusive");
            helper.assertTrue(
                    min.has(row[2]) && min.get(row[2]).getAsInt() == Integer.parseInt(row[3]),
                    path + " must start at " + row[2] + " " + row[3] + ", got " + min);
            JsonObject max = height.getAsJsonObject("max_inclusive");
            helper.assertTrue(
                    max.has("absolute") && max.get("absolute").getAsInt() == Integer.parseInt(row[4]),
                    path + " must end at y = " + row[4] + ", got " + max);
            tops.add(row[4]);
        }
        helper.assertTrue(tops.size() == ORE_LAYOUT.length, "every ore needs its own height band, got tops " + tops);
        helper.succeed();
    }

    /** 赭石、绿片岩、榴辉岩、暮光石：两条特征各自压到六成，取整后下层落到一半 */
    @GameTest(template = "ddt_empty")
    public static void wasteStoneFeaturesKeepTheReducedCounts(GameTestHelper helper) {
        String[][] expected = {
            {"ore_granite_upper", "rarity_filter", "chance", "10"},
            {"ore_granite_lower", "count", "count", "1"},
            {"ore_diorite_upper", "rarity_filter", "chance", "10"},
            {"ore_diorite_lower", "count", "count", "1"},
            {"ore_andesite_upper", "rarity_filter", "chance", "10"},
            {"ore_andesite_lower", "count", "count", "1"},
            {"ore_tuff", "count", "count", "1"},
        };
        for (String[] row : expected) {
            JsonObject encoded = encode(placed(helper, row[0]));
            JsonObject modifier = placementOfType(encoded, "minecraft:" + row[1]);
            helper.assertTrue(modifier != null, row[0] + " must carry a minecraft:" + row[1] + " placement");
            helper.assertTrue(
                    modifier.get(row[2]).getAsInt() == Integer.parseInt(row[3]),
                    row[0] + " must roll " + row[3] + " attempts, got " + modifier.get(row[2]));
        }
        helper.succeed();
    }

    private OverworldOreTests() {}
}
