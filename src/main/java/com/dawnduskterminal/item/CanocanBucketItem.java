package com.dawnduskterminal.item;

import com.dawnduskterminal.registry.ModItems;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

/**
 * 「罐装的不稳定液体」与「罐装的热情暮光」这两件：装的是苍穹罐舀起来的东西，倒空之后还给一个苍穹罐
 *
 * <p>原版 {@code BucketItem} 的空容器写死在 {@code craftRemainder(Items.BUCKET)} 上，那是铁的。
 * 这两桶的容器是 {@code canocan}，所以覆写 NeoForge 的两条物品栈敏感的钩子：
 * {@code IItemExtension#hasCraftingRemainingItem(ItemStack)} 与
 * {@code IItemExtension#getCraftingRemainingItem(ItemStack)}。
 * 酿造台（{@code BrewingStandBlockEntity#doBrew}）与合成（{@code Recipe#getRemainingItems}）
 * 走的都是这两条，于是酿完、合完退回来的是苍穹罐
 *
 * <p>不写 {@code Properties#craftRemainder} 有两个原因：一是它只认一个固定物品，
 * 而热情暮光那桶在 {@code ModScorchingTwilight} 里注册，那时 {@code ModItems.CANOCAN} 还没绑定，
 * 取不到；二是物品栈敏感的钩子按调用那一刻取，注册顺序就无所谓了
 *
 * <p>右键把流体泼出去那一条不经上面两条钩子（{@code BucketItem#use} 调的是静态的
 * {@code getEmptySuccessItem}，里面写死铁桶），由 {@code mixin/BucketItemEmptySuccessMixin} 接住
 */
public class CanocanBucketItem extends BucketItem {
    public CanocanBucketItem(Fluid fluid, Properties properties) {
        super(fluid, properties);
    }

    @Override
    public boolean hasCraftingRemainingItem(ItemStack stack) {
        return true;
    }

    @Override
    public ItemStack getCraftingRemainingItem(ItemStack stack) {
        return new ItemStack(ModItems.CANOCAN.get());
    }
}
