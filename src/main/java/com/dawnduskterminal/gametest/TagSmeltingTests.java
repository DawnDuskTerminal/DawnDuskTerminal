package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.crafting.TagSmeltingRecipe;
import com.dawnduskterminal.registry.ModExperimentItems;
import com.dawnduskterminal.registry.ModItems;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 试验物品的三条烧炼配方
 *
 * <p>060 是那支往下探的金探针，烧成金锭，无条件。
 *
 * <p>015 与 059 的产物按通用标签现查（{@code c:ingots/silver} 与 {@code c:ingots/platinum}）：
 * 这一局里有没有银、有没有铂，取决于整合包装了什么模组，所以配方写的是标签而不是某个模组的物品 ID。
 * 测试环境里这两种金属都不存在，于是这两条配方匹配不上——正是「没装就当没有」的那一半；
 * 另一半（标签里有东西就烧得出）用一条把标签换成 {@code c:ingots/gold} 的同类配方验证，
 * 那条标签原版就有，不依赖任何模组
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class TagSmeltingTests {
    /** 015 是一只普通物品（银） */
    private static final ItemStack SILVER_INPUT =
            new ItemStack(ModExperimentItems.textured().get(15).get());

    @GameTest(template = "ddt_empty")
    public static void goldProbeSmeltsIntoGoldIngot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack input = new ItemStack(ModExperimentItems.EXPERIMENT_060.get());
        Optional<ItemStack> result = smelt(level, input);
        helper.assertTrue(
                result.isPresent(), "experiment_060 must always have a smelting recipe, nothing matched for " + input);
        helper.assertTrue(
                result.get().is(Items.GOLD_INGOT) && result.get().getCount() == 1,
                "experiment_060 must smelt into one gold ingot, got " + result.get());
        helper.succeed();
    }

    /** 没有银就没有这条配方：015 的配方在数据里，但匹配不上，输入物不会被吞掉 */
    @GameTest(template = "ddt_empty")
    public static void experiment015WaitsForSilverToExist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(
                recipeExists(level, "experiment_015_smelting"),
                "the silver recipe must ship with the mod, it is disabled by the empty c:ingots/silver tag");
        helper.assertTrue(
                smelt(level, SILVER_INPUT).isEmpty(),
                "a pack without silver must not smelt experiment_015, the tag is empty here");
        helper.succeed();
    }

    /** 没有铂就没有这条配方：059 是那块铂催化网格，一样匹配不上 */
    @GameTest(template = "ddt_empty")
    public static void experiment059WaitsForPlatinumToExist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(
                recipeExists(level, "experiment_059_smelting"),
                "the platinum recipe must ship with the mod, it is disabled by the empty c:ingots/platinum tag");
        ItemStack input = new ItemStack(ModItems.EXPERIMENT_059.get());
        helper.assertTrue(
                smelt(level, input).isEmpty(),
                "a pack without platinum must not smelt experiment_059, the tag is empty here");
        helper.succeed();
    }

    /**
     * 「标签里有东西就烧得出」那一半：同一条配方换成原版就有的 {@code c:ingots/gold}，
     * 输入用 015，产物必须是金锭
     */
    @GameTest(template = "ddt_empty")
    public static void aPopulatedTagResolvesToItsFirstItem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TagSmeltingRecipe gold = new TagSmeltingRecipe(
                "",
                CookingBookCategory.MISC,
                Ingredient.of(ModExperimentItems.textured().get(15).get()),
                TagKey.create(Registries.ITEM, ResourceLocation.parse("c:ingots/gold")),
                0.7F,
                200);
        SingleRecipeInput input = new SingleRecipeInput(SILVER_INPUT);
        helper.assertTrue(gold.matches(input, level), "a tag that has gold in it must match, the metal exists");
        helper.assertTrue(
                gold.assemble(input, level.registryAccess()).is(Items.GOLD_INGOT),
                "the result must be the first item of the tag, got " + gold.assemble(input, level.registryAccess()));
        helper.assertTrue(
                gold.getResultItem(level.registryAccess()).is(Items.GOLD_INGOT),
                "the displayed result must come from the tag too");

        TagSmeltingRecipe missing = new TagSmeltingRecipe(
                "",
                CookingBookCategory.MISC,
                Ingredient.of(ModExperimentItems.textured().get(15).get()),
                TagKey.create(Registries.ITEM, ResourceLocation.parse("c:ingots/platinum")),
                0.7F,
                200);
        helper.assertFalse(missing.matches(input, level), "an empty tag must not match, the metal does not exist here");
        helper.assertTrue(
                missing.assemble(input, level.registryAccess()).isEmpty(), "an empty tag must resolve to nothing");
        helper.succeed();
    }

    /** 序列化器已经注册：配方 JSON 里写的 {@code type} 就是它 */
    @GameTest(template = "ddt_empty")
    public static void theSerializerIsRegistered(GameTestHelper helper) {
        helper.assertTrue(
                BuiltInRegistries.RECIPE_SERIALIZER.containsKey(DawnDuskTerminal.id("tag_smelting")),
                "dawnduskterminal:tag_smelting must be registered, the two metal recipes use it as their type");
        helper.succeed();
    }

    private static Optional<ItemStack> smelt(ServerLevel level, ItemStack input) {
        SingleRecipeInput recipeInput = new SingleRecipeInput(input);
        return level.getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, recipeInput, level)
                .map(holder -> holder.value().assemble(recipeInput, level.registryAccess()));
    }

    private static boolean recipeExists(ServerLevel level, String path) {
        return level.getRecipeManager().byKey(DawnDuskTerminal.id(path)).isPresent();
    }

    private TagSmeltingTests() {}
}
