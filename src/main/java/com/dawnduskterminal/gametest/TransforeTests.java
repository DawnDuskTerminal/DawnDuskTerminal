package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModChronoOres;
import com.dawnduskterminal.registry.ModItems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFItems;

/**
 * 易矿那一串：矿石 → 易矿 → 转换粉 → 强效转换粉 → 逆转转换粉
 *
 * <p>矿石是 {@code dawnduskterminal:transfore_ore}，只长在上层那一档宿主石里、高度带 y10~280、数量少；
 * 挖下来掉 {@link ModItems#TRANSFORE}，烧成暮色森林的转换粉，再往上两条无序配方。
 * 断言分成四块：掉落（真的走战利品表）、两条无序配方与一条烧炼（走 {@code RecipeManager}）、
 * 世界生成（每个本模组群系都挂了这条特征）、资源与名字
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class TransforeTests {
    /** 矿石特征在 placed_feature 里的 ID */
    private static final ResourceLocation PLACED_FEATURE = DawnDuskTerminal.id("ore_transfore");

    /** 区块坐标：随便挑一块空地，模板是 ddt_empty */
    private static final BlockPos ORE_POS = BlockPos.ZERO;

    /**
     * 挖掘掉落：铁镐挖掉的是易矿本体，精准采集挖掉的是矿石方块，空手什么也不掉
     *
     * <p>走的是真的战利品表（{@code Block#getDrops}），不是查文件
     */
    @GameTest(template = "ddt_empty")
    public static void oreMinesIntoTransfore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(ORE_POS);
        BlockState ore = ModChronoOres.TRANSFORE_ORE.get().defaultBlockState();

        List<ItemStack> plain = Block.getDrops(ore, level, pos, null, null, new ItemStack(Items.IRON_PICKAXE));
        helper.assertTrue(
                plain.size() == 1
                        && plain.get(0).is(ModItems.TRANSFORE.get())
                        && plain.get(0).getCount() == 1,
                "an iron pickaxe must drop one dawnduskterminal:transfore, got " + plain);

        ItemStack silk = new ItemStack(Items.IRON_PICKAXE);
        silk.enchant(
                level.registryAccess()
                        .registryOrThrow(Registries.ENCHANTMENT)
                        .getHolderOrThrow(Enchantments.SILK_TOUCH),
                1);
        List<ItemStack> silkDrops = Block.getDrops(ore, level, pos, null, null, silk);
        helper.assertTrue(
                silkDrops.size() == 1 && silkDrops.get(0).is(ModChronoOres.TRANSFORE_ORE_ITEM.get()),
                "silk touch must drop the ore block itself, got " + silkDrops);

        // 空手不掉这件事不看战利品表：表里没有「工具等级」那条条件，原版是先由
        // Block#playerDestroy 问 canHarvestBlock，通了才去查表。所以判据落在工具等级上
        helper.assertTrue(
                new ItemStack(Items.IRON_PICKAXE).isCorrectToolForDrops(ore),
                "an iron pickaxe must be the right tool for transfore_ore");
        helper.assertFalse(
                new ItemStack(Items.WOODEN_PICKAXE).isCorrectToolForDrops(ore),
                "a wooden pickaxe must not be good enough for transfore_ore");
        helper.succeed();
    }

    /** 进镐子标签与铁镐标签，且硬度/抗性照苍穹石那一档的矿石 */
    @GameTest(template = "ddt_empty")
    public static void oreBlockPropertiesMatchTheUpperOreTier(GameTestHelper helper) {
        Block block = ModChronoOres.TRANSFORE_ORE.get();
        BlockState state = block.defaultBlockState();
        helper.assertTrue(
                state.is(BlockTags.MINEABLE_WITH_PICKAXE), "transfore_ore must be in #minecraft:mineable/pickaxe");
        helper.assertTrue(state.is(BlockTags.NEEDS_IRON_TOOL), "transfore_ore must be in #minecraft:needs_iron_tool");
        helper.assertTrue(
                block.defaultDestroyTime() == 2.5F,
                "the ore must take 2.5 to break, got " + block.defaultDestroyTime());
        helper.assertTrue(
                block.getExplosionResistance() == 9.0F,
                "the ore must resist explosions like the other chrono ores (9), got " + block.getExplosionResistance());
        helper.succeed();
    }

    /** 易矿烧成暮色森林的转换粉：200 tick、0.7 经验，输入是真正注册的那件物品 */
    @GameTest(template = "ddt_empty")
    public static void transforeSmeltsIntoTransformationPowder(GameTestHelper helper) {
        var smelting = helper.getLevel()
                .getRecipeManager()
                .getRecipeFor(
                        RecipeType.SMELTING,
                        new SingleRecipeInput(new ItemStack(ModItems.TRANSFORE.get())),
                        helper.getLevel());
        helper.assertTrue(smelting.isPresent(), "dawnduskterminal:transfore must be smeltable in a furnace");
        ItemStack result =
                smelting.get().value().getResultItem(helper.getLevel().registryAccess());
        helper.assertTrue(
                result.is(TFItems.TRANSFORMATION_POWDER.get()),
                "transfore must smelt into twilightforest:transformation_powder, got " + result);
        helper.succeed();
    }

    /** 两条无序配方：易矿 + 弦 + 光辉粉 + 稳定药水 → 强效转换粉；强效转换粉 + 狄拉克 → 逆转转换粉 */
    @GameTest(template = "ddt_empty")
    public static void powderChainCraftsBothDirections(GameTestHelper helper) {
        RecipeHolder<?> potent = crafting(
                helper,
                List.of(
                        new ItemStack(ModItems.TRANSFORE.get()),
                        new ItemStack(ModItems.STRING.get()),
                        new ItemStack(ModItems.ILLUSTRIOUS_POWDER.get()),
                        new ItemStack(ModItems.STABILITY_POTION.get())));
        assertRecipeId(helper, potent, "potent_transformation_powder");
        ItemStack potentResult = potent.value().getResultItem(helper.getLevel().registryAccess());
        helper.assertTrue(
                potentResult.is(ModItems.POTENT_TRANSFORMATION_POWDER.get()),
                "the four-ingredient recipe must yield dawnduskterminal:potent_transformation_powder, got "
                        + potentResult);
        helper.assertTrue(
                potentResult.getCount() == 1, "the potent powder recipe must yield 1, got " + potentResult.getCount());

        RecipeHolder<?> reversed = crafting(
                helper,
                List.of(
                        new ItemStack(ModItems.POTENT_TRANSFORMATION_POWDER.get()),
                        new ItemStack(ModItems.DIRAC.get())));
        assertRecipeId(helper, reversed, "reversed_transformation_powder");
        ItemStack reversedResult =
                reversed.value().getResultItem(helper.getLevel().registryAccess());
        helper.assertTrue(
                reversedResult.is(ModItems.REVERSED_TRANSFORMATION_POWDER.get()),
                "the dirac recipe must yield dawnduskterminal:reversed_transformation_powder, got " + reversedResult);
        helper.assertTrue(
                reversedResult.getCount() == 1,
                "the reversed powder recipe must yield 1, got " + reversedResult.getCount());
        helper.succeed();
    }

    /**
     * 世界生成：特征本身只换上层那一档宿主石，高度带 y10~280、每区块 2 次；
     * 而且每一个本模组群系都挂着这条特征
     *
     * <p>群系那一半查的是加载后的 {@code Biome} 注册表，不是文件：漏写一个群系、或者写了一个
     * 不存在的特征 ID，这里都会红
     */
    @GameTest(template = "ddt_empty")
    public static void oreIsPlacedInEveryChronoBiome(GameTestHelper helper) {
        JsonObject configured =
                readJson(helper, "/data/dawnduskterminal/worldgen/configured_feature/ore_transfore.json");
        JsonObject config = configured.getAsJsonObject("config");
        helper.assertTrue(config.get("size").getAsInt() == 8, "a vein must be 8 blocks, got " + config.get("size"));
        JsonObject target = config.getAsJsonArray("targets").get(0).getAsJsonObject();
        helper.assertTrue(
                target.getAsJsonObject("state").get("Name").getAsString().equals("dawnduskterminal:transfore_ore"),
                "the feature must place transfore_ore, got " + target.getAsJsonObject("state"));
        helper.assertTrue(
                target.getAsJsonObject("target")
                        .get("tag")
                        .getAsString()
                        .equals("dawnduskterminal:stone_ore_replaceables"),
                "the ore belongs to the upper host-stone tier, got " + target.getAsJsonObject("target"));

        JsonObject placed = readJson(helper, "/data/dawnduskterminal/worldgen/placed_feature/ore_transfore.json");
        helper.assertTrue(
                placed.getAsJsonArray("placement")
                                .get(0)
                                .getAsJsonObject()
                                .get("count")
                                .getAsInt()
                        == 20,
                "the ore is placed 20 times per chunk, got " + placed.getAsJsonArray("placement"));
        JsonObject height =
                placed.getAsJsonArray("placement").get(2).getAsJsonObject().getAsJsonObject("height");
        helper.assertTrue(
                height.get("min_inclusive").getAsJsonObject().get("absolute").getAsInt() == 10
                        && height.get("max_inclusive")
                                        .getAsJsonObject()
                                        .get("absolute")
                                        .getAsInt()
                                == 280,
                "the ore belongs to y10~280, got " + height);

        Registry<Biome> biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        int checked = 0;
        for (var entry : biomes.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            if (!id.getNamespace().equals(DawnDuskTerminal.MOD_ID)) {
                continue;
            }
            checked++;
            boolean found = entry.getValue().getGenerationSettings().features().stream()
                    .flatMap(HolderSet::stream)
                    .map(Holder::unwrapKey)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .anyMatch(key -> key.location().equals(PLACED_FEATURE));
            helper.assertTrue(found, id + " must place " + PLACED_FEATURE + " among its features");
        }
        helper.assertTrue(checked > 0, "the chrono dimension must own at least one biome, found " + checked);
        helper.succeed();
    }

    /** 资源与名字：两件物品 + 矿石方块 + 矿物的模型、贴图与中英语言键 */
    @GameTest(template = "ddt_empty", batch = "transfore_assets")
    public static void assetsAndNamesArePinned(GameTestHelper helper) {
        JsonObject blockModel = readJson(helper, "/assets/dawnduskterminal/models/block/transfore_ore.json");
        helper.assertTrue(
                blockModel
                        .getAsJsonObject("textures")
                        .get("all")
                        .getAsString()
                        .equals("dawnduskterminal:block/transfore"),
                "the ore block must read its own texture, got " + blockModel.getAsJsonObject("textures"));

        for (String item : List.of("transfore", "potent_transformation_powder", "reversed_transformation_powder")) {
            JsonObject model = readJson(helper, "/assets/dawnduskterminal/models/item/" + item + ".json");
            helper.assertTrue(
                    model.getAsJsonObject("textures")
                            .get("layer0")
                            .getAsString()
                            .equals("dawnduskterminal:item/" + item),
                    "item/" + item + " must read its own texture, got " + model.getAsJsonObject("textures"));
            helper.assertTrue(
                    TransforeTests.class.getResource("/assets/dawnduskterminal/textures/item/" + item + ".png") != null,
                    "textures/item/" + item + ".png must ship inside the mod resources");
        }
        helper.assertTrue(
                TransforeTests.class.getResource("/assets/dawnduskterminal/textures/block/transfore.png") != null,
                "textures/block/transfore.png must ship inside the mod resources");

        for (String lang : new String[] {"en_us", "zh_cn"}) {
            JsonObject words = readJson(helper, "/assets/dawnduskterminal/lang/" + lang + ".json");
            for (String key : List.of(
                    "block.dawnduskterminal.transfore_ore",
                    "item.dawnduskterminal.transfore",
                    "item.dawnduskterminal.potent_transformation_powder",
                    "item.dawnduskterminal.reversed_transformation_powder")) {
                helper.assertTrue(
                        words.has(key), lang + " must translate " + key + ", otherwise the tab shows a raw key");
            }
        }
        helper.succeed();
    }

    /** 在合成台里试一次，返回命中的那条配方 */
    private static RecipeHolder<?> crafting(GameTestHelper helper, List<ItemStack> items) {
        CraftingInput input = CraftingInput.of(items.size(), 1, items);
        return helper.getLevel()
                .getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
                .orElseThrow(() -> new AssertionError("no crafting recipe matches those " + items.size()
                        + " items (a malformed ingredient drops" + " the whole recipe at load time)"));
    }

    private static void assertRecipeId(GameTestHelper helper, RecipeHolder<?> holder, String path) {
        ResourceLocation expected = ResourceLocation.fromNamespaceAndPath(DawnDuskTerminal.MOD_ID, path);
        helper.assertTrue(
                holder.id().equals(expected), "the matching recipe must be " + expected + ", got " + holder.id());
    }

    /** 从 mod 资源里读一份 JSON：路径以 {@code /} 开头，相对于 jar 根 */
    private static JsonObject readJson(GameTestHelper helper, String resource) {
        try (InputStream stream = TransforeTests.class.getResourceAsStream(resource)) {
            helper.assertTrue(stream != null, resource + " must ship inside the mod resources");
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException problem) {
            throw new AssertionError("reading " + resource + " failed", problem);
        }
    }

    private TransforeTests() {}
}
