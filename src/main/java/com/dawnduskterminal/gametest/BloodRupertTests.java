package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModExperimentItems;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFItems;

/**
 * 血鲁珀特那六条配方：钼支线四条、炽热之泪两条
 *
 * <p>所有断言都走 {@code RecipeManager}，也就是真的拿一叠原料「合成」一遍，而不是查文件：
 * 配方 JSON 里字段名写错时整条配方会在加载时被丢掉，文件还在、合成台里却什么也出不来。
 * 尤其盯住两条都产出试验物品030 的配方——原料数不同（1 颗粗钼铅矿与 2 颗），
 * 匹配到的配方 ID 也必须对得上，否则玩家手里的试验物品029 会被当成前者白白吃掉一份
 *
 * <p>试验物品030 现在有自己的图标（{@code textures/item/experiment_030.png}），
 * 所以它从占位表搬到了 {@link ModExperimentItems#textured()}；这条的对应断言在
 * {@code ExperimentItemTests#placeholdersOnlyCoverTheBlankIcons} 与
 * {@code ExperimentItemTests#placeholderTableMatchesTheIcons} 里
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class BloodRupertTests {
    /** 六条配方都在这个命名空间下 */
    private static final String NAMESPACE = "dawnduskterminal";

    /**
     * 血鲁珀特增殖：一颗血鲁珀特 + 石榴石 + 光辉粉 + 三颗恶魂之泪 无序合成四颗血鲁珀特
     *
     * <p>这里一并验「产出就是血鲁珀特自己」，因为配方是自己喂给自己，产物写错一个字母也照样能合成，
     * 只是会变成别的物品
     */
    @GameTest(template = "ddt_empty", batch = "blood_rupert")
    public static void bloodRupertMultipliesIntoFour(GameTestHelper helper) {
        var recipe = crafting(
                helper,
                List.of(
                        new ItemStack(ModItems.BLOOD_RUPERT.get()),
                        new ItemStack(ModItems.GARNET.get()),
                        new ItemStack(ModItems.ILLUSTRIOUS_POWDER.get()),
                        new ItemStack(Items.GHAST_TEAR),
                        new ItemStack(Items.GHAST_TEAR),
                        new ItemStack(Items.GHAST_TEAR)));
        assertRecipeId(helper, recipe, "blood_rupert");
        ItemStack result = resultOf(helper, recipe);
        helper.assertTrue(
                result.is(ModItems.BLOOD_RUPERT.get()),
                "the duplication recipe must yield dawnduskterminal:blood_rupert, got " + result);
        helper.assertTrue(result.getCount() == 4, "the duplication recipe must yield 4, got " + result.getCount());
        helper.succeed();
    }

    /**
     * 试验物品030 的两条来路：一颗血鲁珀特配一颗粗钼铅矿出 1 个，
     * 试验物品029 配两颗粗钼铅矿出 2 个；两条都要一口炼药锅
     */
    @GameTest(template = "ddt_empty", batch = "blood_rupert")
    public static void experiment030IsPressedOutOfTheCauldron(GameTestHelper helper) {
        var fromBloodRupert = crafting(
                helper,
                List.of(
                        new ItemStack(ModItems.BLOOD_RUPERT.get()),
                        new ItemStack(ModItems.RAW_SCARLET_MOLYBDENUM.get()),
                        new ItemStack(Items.CAULDRON)));
        assertRecipeId(helper, fromBloodRupert, "experiment_030");
        ItemStack single = resultOf(helper, fromBloodRupert);
        helper.assertTrue(
                single.is(ModExperimentItems.textured().get(30).get()),
                "the cauldron recipe must yield experiment_030, got " + single);
        helper.assertTrue(single.getCount() == 1, "the blood rupert recipe must yield 1, got " + single.getCount());

        var from029 = crafting(
                helper,
                List.of(
                        new ItemStack(ModExperimentItems.textured().get(29).get()),
                        new ItemStack(ModItems.RAW_SCARLET_MOLYBDENUM.get()),
                        new ItemStack(ModItems.RAW_SCARLET_MOLYBDENUM.get()),
                        new ItemStack(Items.CAULDRON)));
        assertRecipeId(helper, from029, "experiment_030_from_experiment_029");
        ItemStack doubled = resultOf(helper, from029);
        helper.assertTrue(doubled.is(single.getItem()), "both routes must yield experiment_030, got " + doubled);
        helper.assertTrue(doubled.getCount() == 2, "the experiment_029 route must yield 2, got " + doubled.getCount());
        helper.succeed();
    }

    /**
     * 试验物品030 烧成绯色钼锭：一条 200 tick、0.7 经验的熔炉配方
     *
     * <p>输入是 {@code textured().get(30)}，也就是玩家手里那件真正注册的试验物品030，
     * 而不是随手 new 出来的同 ID 物品
     */
    @GameTest(template = "ddt_empty", batch = "blood_rupert")
    public static void experiment030SmeltsIntoScarletMolybdenumIngot(GameTestHelper helper) {
        ItemStack input = new ItemStack(ModExperimentItems.textured().get(30).get());
        var smelting = helper.getLevel()
                .getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(input), helper.getLevel());
        helper.assertTrue(smelting.isPresent(), "dawnduskterminal:experiment_030 must be smeltable in a furnace");
        ItemStack result = resultOf(helper, smelting.get());
        helper.assertTrue(
                result.is(ModItems.SCARLET_MOLYBDENUM_INGOT.get()),
                "dawnduskterminal:experiment_030 must smelt into dawnduskterminal:scarlet_molybdenum_ingot, got "
                        + result);
        helper.succeed();
    }

    /**
     * 炽热之泪的两条来路：血鲁珀特配玻璃瓶、恶魂之泪与炽热的方块出 1 个，
     * 试验物品029 配同样三样出 2 个
     */
    @GameTest(template = "ddt_empty", batch = "blood_rupert")
    public static void fieryTearsComeOutOfBothDrops(GameTestHelper helper) {
        var fromBloodRupert = crafting(
                helper,
                List.of(
                        new ItemStack(ModItems.BLOOD_RUPERT.get()),
                        new ItemStack(Items.GLASS_BOTTLE),
                        new ItemStack(Items.GHAST_TEAR),
                        new ItemStack(ModTerrainBlocks.BLAZING_BLOCK_ITEM.get())));
        assertRecipeId(helper, fromBloodRupert, "fiery_tears_from_blood_rupert");
        ItemStack single = resultOf(helper, fromBloodRupert);
        helper.assertTrue(
                single.is(TFItems.FIERY_TEARS.get()),
                "the recipe must yield twilightforest:fiery_tears, got " + single);
        helper.assertTrue(single.getCount() == 1, "the blood rupert recipe must yield 1, got " + single.getCount());

        var from029 = crafting(
                helper,
                List.of(
                        new ItemStack(ModExperimentItems.textured().get(29).get()),
                        new ItemStack(Items.GLASS_BOTTLE),
                        new ItemStack(Items.GHAST_TEAR),
                        new ItemStack(ModTerrainBlocks.BLAZING_BLOCK_ITEM.get())));
        assertRecipeId(helper, from029, "fiery_tears_from_experiment_029");
        ItemStack doubled = resultOf(helper, from029);
        helper.assertTrue(doubled.is(TFItems.FIERY_TEARS.get()), "both routes must yield fiery tears, got " + doubled);
        helper.assertTrue(doubled.getCount() == 2, "the experiment_029 route must yield 2, got " + doubled.getCount());
        helper.succeed();
    }

    /**
     * 物品、模型、贴图与两条语言键：缺一件就是紫黑格或者悬停时一行原始键
     *
     * <p>试验物品030 的图标已经换成自己的那张，所以它必须在 {@code textured()} 里、不在占位表里；
     * {@code ExperimentItemTests#placeholderTableMatchesTheIcons} 会逐号对照全部模型，这里只钉它这一号
     */
    @GameTest(template = "ddt_empty", batch = "blood_rupert_assets")
    public static void bloodRupertAndExperiment030AssetsArePinned(GameTestHelper helper) {
        helper.assertFalse(ModItems.BLOOD_RUPERT.get().getDescriptionId().isEmpty(), "blood_rupert must be registered");

        for (String item : List.of("blood_rupert", "experiment_030")) {
            JsonObject model = readJson(helper, "/assets/dawnduskterminal/models/item/" + item + ".json");
            helper.assertTrue(
                    model.get("parent").getAsString().equals("minecraft:item/generated"),
                    "item/" + item + " must stay on minecraft:item/generated, got " + model.get("parent"));
            helper.assertTrue(
                    model.getAsJsonObject("textures")
                            .get("layer0")
                            .getAsString()
                            .equals("dawnduskterminal:item/" + item),
                    "item/" + item + " must read its own texture, got " + model.getAsJsonObject("textures"));
            helper.assertTrue(
                    BloodRupertTests.class.getResource("/assets/dawnduskterminal/textures/item/" + item + ".png")
                            != null,
                    "textures/item/" + item + ".png must ship inside the mod resources");
            for (String lang : new String[] {"en_us", "zh_cn"}) {
                JsonObject words = readJson(helper, "/assets/dawnduskterminal/lang/" + lang + ".json");
                helper.assertTrue(
                        words.has("item.dawnduskterminal." + item),
                        lang + " must name " + item + ", otherwise the tab shows a raw key");
            }
        }

        helper.assertTrue(
                ModExperimentItems.textured().containsKey(30),
                "experiment_030 owns its icon now, so it must be listed in ModExperimentItems.textured()");
        helper.assertFalse(
                ModExperimentItems.placeholders().containsKey(30),
                "experiment_030 owns its icon now, it must not stay a placeholder");
        helper.succeed();
    }

    /** 拿一叠原料在合成台里试一次，返回命中的那条配方 */
    private static RecipeHolder<?> crafting(GameTestHelper helper, List<ItemStack> items) {
        CraftingInput input = CraftingInput.of(items.size(), 1, items);
        return helper.getLevel()
                .getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
                .orElseThrow(() -> new AssertionError("no crafting recipe matches those " + items.size()
                        + " items (a malformed ingredient drops" + " the whole recipe at load time)"));
    }

    /** 配方 ID 必须就是预期的那一条：两条配方产出同一件物品时，靠它区分走的是哪一条 */
    private static void assertRecipeId(GameTestHelper helper, RecipeHolder<?> holder, String path) {
        ResourceLocation expected = ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
        helper.assertTrue(
                holder.id().equals(expected), "the matching recipe must be " + expected + ", got " + holder.id());
    }

    private static ItemStack resultOf(GameTestHelper helper, RecipeHolder<?> holder) {
        return holder.value().getResultItem(helper.getLevel().registryAccess());
    }

    /** 从 mod 资源里读一份 JSON：路径以 {@code /} 开头，相对于 jar 根 */
    private static JsonObject readJson(GameTestHelper helper, String resource) {
        try (InputStream stream = BloodRupertTests.class.getResourceAsStream(resource)) {
            helper.assertTrue(stream != null, resource + " must ship inside the mod resources");
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException problem) {
            throw new AssertionError("reading " + resource + " failed", problem);
        }
    }

    private BloodRupertTests() {}
}
