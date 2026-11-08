package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModCreativeTabs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.CreativeModeTabRegistry;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 创造栏的顺序与内容：试验物品栏必须紧挨在主栏目后面，并且把每一个带序号的试验物品都摆出来
 *
 * <p>NeoForge 的 {@link CreativeModeTabRegistry} 给创造栏做拓扑排序，比较器是注册名的字母序，
 * 而且没写 {@code tabsBefore}/{@code tabsAfter} 的栏目都只挂一条「排在最后一个原版栏目之后」的边，
 * 所以光靠注册先后（也就是 {@code ModCreativeTabs} 里字段的声明顺序）并不能让两个栏目挨在一起，
 * 别的模组的栏目会按名字插进来。要挨着就必须显式声明边，这里把那条声明钉住
 *
 * <p>内容那条断言是给「某个号从 {@code simpleItems()} 挪进 {@code TAKEN}」这件事兜底的：
 * 栏目里带序号的条目一半来自 {@code simpleItems()}、一半靠 {@code sequence.put} 逐个点名，
 * 从前者挪到后者的号如果忘了补一行 {@code sequence.put}，物品还在注册表里、却从创造栏里整个消失，
 * 而不是报错
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class CreativeTabTests {
    @GameTest(template = "ddt_empty")
    public static void experimentTabSitsRightAfterMainTab(GameTestHelper helper) {
        CreativeModeTab main = ModCreativeTabs.MAIN.get();
        CreativeModeTab experiment = ModCreativeTabs.EXPERIMENT_ITEMS.get();
        helper.assertTrue(
                experiment.tabsBefore.contains(DawnDuskTerminal.id("main")),
                "experiment_items must declare dawnduskterminal:main as a tab before it, got " + experiment.tabsBefore);

        List<CreativeModeTab> sorted = CreativeModeTabRegistry.getSortedCreativeModeTabs();
        int mainIndex = sorted.indexOf(main);
        int experimentIndex = sorted.indexOf(experiment);
        helper.assertTrue(
                mainIndex >= 0 && experimentIndex >= 0,
                "both tabs must appear in the sorted tab list, main=" + mainIndex + " experiment=" + experimentIndex);
        helper.assertTrue(
                experimentIndex == mainIndex + 1,
                "experiment_items must sit right after main, got " + mainIndex + " and " + experimentIndex + " in "
                        + sorted.stream().map(CreativeModeTabRegistry::getName).toList());
        helper.succeed();
    }

    /**
     * 试验物品栏把每一个注册过的 {@code dawnduskterminal:experiment_NNN} 都摆出来，且按序号升序
     *
     * <p>内容靠 {@code buildContents} 真的算一遍，不是读字段：客户端那一栏取的就是这份结果。
     * 115 号是暮色森林那件，注册名在它自己的命名空间里，由栏目最后单点一次补上，所以这一圈不查它
     */
    @GameTest(template = "ddt_empty")
    public static void experimentTabListsEveryNumberedItemInOrder(GameTestHelper helper) {
        CreativeModeTab tab = ModCreativeTabs.EXPERIMENT_ITEMS.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                FeatureFlags.VANILLA_SET, true, helper.getLevel().registryAccess()));
        List<Item> listed =
                tab.getDisplayItems().stream().map(ItemStack::getItem).toList();

        List<Integer> numbers = new ArrayList<>();
        for (int number = 1; number <= 115; number++) {
            ResourceLocation id = ResourceLocation.parse("dawnduskterminal:experiment_%03d".formatted(number));
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == Items.AIR) {
                continue;
            }
            helper.assertTrue(
                    listed.contains(item),
                    id + " is registered but missing from the experiment tab, the tab would hide it completely");
            numbers.add(number);
        }

        List<Integer> order = new ArrayList<>();
        for (int number : numbers) {
            ResourceLocation id = ResourceLocation.parse("dawnduskterminal:experiment_%03d".formatted(number));
            order.add(listed.indexOf(BuiltInRegistries.ITEM.get(id)));
        }
        List<Integer> sortedOrder = new ArrayList<>(order);
        Collections.sort(sortedOrder);
        helper.assertTrue(
                order.equals(sortedOrder),
                "the experiment tab must list the numbered items in ascending order, got numbers " + numbers
                        + " at slots " + order);
        helper.succeed();
    }

    private CreativeTabTests() {}
}
