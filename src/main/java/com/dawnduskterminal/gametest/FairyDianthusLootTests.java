package com.dawnduskterminal.gametest;

import com.dawnduskterminal.item.StellariaVegesItem;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 仙灵石竹挖掉之后的产物：精准采集留本体，否则一半概率掉繁缕菜
 *
 * <p>战利品表是数据文件，编译期管不到它；写错一个条件名照样能加载（只是永远不命中），
 * 所以这里真的跑一遍 {@code BlockState#getDrops}，把「用什么工具掉什么」量出来
 *
 * <p>没有精准采集那一支是 50% 的 {@code random_chance}，单次掷不出来结论，
 * 于是掷 {@link #ROLLS} 次看落在 35%~65% 之间——二项分布下 400 次掷出界外的概率不到一亿分之一
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class FairyDianthusLootTests {
    /** 50% 那条概率掷多少次 */
    private static final int ROLLS = 400;

    /** 两种仙灵石竹的掉落规则必须一样 */
    private static final List<Block> PLANTS =
            List.of(ModTerrainBlocks.FAIRY_DIANTHUS.get(), ModTerrainBlocks.FAIRY_DIANTHUS_SMALL.get());

    /** 带精准采集的镐：掉的是方块本体，两种都一样 */
    @GameTest(template = "ddt_empty")
    public static void silkTouchKeepsThePlantItself(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 origin = originOf(helper);
        ItemStack tool = silkTouchTool(level);
        for (Block block : PLANTS) {
            List<ItemStack> drops = drops(level, block, origin, tool);
            helper.assertTrue(
                    drops.size() == 1,
                    block.getName().getString() + " must drop exactly one stack with silk touch, got " + drops.size());
            ItemStack drop = drops.get(0);
            helper.assertTrue(
                    drop.is(block.asItem()),
                    block.getName().getString() + " must drop itself with silk touch, got " + drop);
            helper.assertTrue(drop.getCount() == 1, "the plant must drop a single block, got " + drop.getCount());
        }
        helper.succeed();
    }

    /** 没有精准采集：一半概率掉一个繁缕菜，另一半什么都不掉，方块本体绝不出现 */
    @GameTest(template = "ddt_empty")
    public static void noSilkTouchDropsStellariaHalfTheTime(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 origin = originOf(helper);
        ItemStack tool = new ItemStack(Items.STONE_SHOVEL);
        helper.assertFalse(
                tool.isEnchanted(), "the plain tool of this case must not carry silk touch or any other enchantment");
        for (Block block : PLANTS) {
            int vegetables = 0;
            for (int roll = 0; roll < ROLLS; roll++) {
                List<ItemStack> drops = drops(level, block, origin, tool);
                helper.assertTrue(
                        drops.size() <= 1,
                        block.getName().getString() + " must drop at most one stack, got " + drops.size());
                if (drops.isEmpty()) {
                    continue;
                }
                ItemStack drop = drops.get(0);
                helper.assertFalse(
                        drop.is(block.asItem()),
                        block.getName().getString() + " must never drop itself without silk touch, got " + drop);
                helper.assertTrue(
                        drop.is(ModTerrainBlocks.STELLARIA_VEGES_ITEM.get()),
                        "the only drop without silk touch is the stellaria veges, got " + drop);
                helper.assertTrue(drop.getCount() == 1, "one roll drops one vegetable, got " + drop.getCount());
                vegetables++;
            }
            helper.assertTrue(
                    vegetables > ROLLS * 35 / 100,
                    block.getName().getString() + " dropped its vegetable too rarely: " + vegetables + " of " + ROLLS);
            helper.assertTrue(
                    vegetables < ROLLS * 65 / 100,
                    block.getName().getString() + " dropped its vegetable too often: " + vegetables + " of " + ROLLS);
        }
        helper.succeed();
    }

    /** 繁缕菜：饥饿 2、饱和度 2 点，燃料值相当于半块煤炭 */
    @GameTest(template = "ddt_empty")
    public static void stellariaVegesIsFoodAndFuel(GameTestHelper helper) {
        Item item = ModTerrainBlocks.STELLARIA_VEGES_ITEM.get();
        ItemStack stack = new ItemStack(item);
        FoodProperties food = stack.get(DataComponents.FOOD);
        helper.assertTrue(food != null, "the stellaria veges must be edible, it has no food component");
        helper.assertTrue(food.nutrition() == 2, "the stellaria veges must restore 2 hunger, got " + food.nutrition());
        // FoodProperties#saturation 存的是算好的点数（系数 × 饥饿 × 2），不是系数
        helper.assertTrue(
                Math.abs(food.saturation() - 2.0F) < 1.0E-4F,
                "the stellaria veges must restore 2 saturation, got " + food.saturation());
        helper.assertTrue(
                StellariaVegesItem.BURN_TICKS * 2 == StellariaVegesItem.COAL_TICKS,
                "the fuel value must be exactly half a coal: " + StellariaVegesItem.BURN_TICKS + " ticks");
        helper.assertTrue(
                item.getBurnTime(stack, RecipeType.SMELTING) == StellariaVegesItem.BURN_TICKS,
                "the stellaria veges must burn for " + StellariaVegesItem.BURN_TICKS + " ticks, got "
                        + item.getBurnTime(stack, RecipeType.SMELTING));
        helper.succeed();
    }

    /** 掉落点：只用来填 {@code ORIGIN}，这两张表里没有按坐标取值的条件 */
    private static Vec3 originOf(GameTestHelper helper) {
        return Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 1, 1)));
    }

    /** 原版附魔书那套注册表查询：附魔在 1.21 是注册项，不能直接 new */
    private static ItemStack silkTouchTool(ServerLevel level) {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.enchant(level.registryAccess().holderOrThrow(Enchantments.SILK_TOUCH), 1);
        return tool;
    }

    /** 走方块自己的那条路：{@code getDrops} 会把方块状态填进 {@code BLOCK_STATE}，这里只补 ORIGIN 与 TOOL */
    private static List<ItemStack> drops(ServerLevel level, Block block, Vec3 origin, ItemStack tool) {
        LootParams.Builder builder = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, origin)
                .withParameter(LootContextParams.TOOL, tool);
        return block.defaultBlockState().getDrops(builder);
    }

    private FairyDianthusLootTests() {}
}
