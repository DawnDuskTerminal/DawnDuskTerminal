package com.dawnduskterminal.item;

import javax.annotation.Nullable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * 繁缕菜：仙灵石竹没有精准采集时挖出来的那一把嫩叶
 *
 * <p>两个属性都不是物品自己的代码能表达的：能吃什么写在 {@code Properties#food} 里（见 {@code ModTerrainBlocks}），
 * 能烧多久只能覆写 {@link #getBurnTime}——原版没有「燃料表」这种数据，燃料值一律是这条方法给的
 * （木类方块与物品走同一条，见 {@code HollowLogItem}）
 *
 * <p>燃料值取半块煤炭：煤炭 {@link #COAL_TICKS 1600}，这里 800
 */
public class StellariaVegesItem extends Item {
    /** 煤炭在原版的燃烧时长，只用来给 {@link #BURN_TICKS} 做换算说明 */
    public static final int COAL_TICKS = 1600;

    /** 实际燃料值：相当于半块煤炭 */
    public static final int BURN_TICKS = COAL_TICKS / 2;

    public StellariaVegesItem(Properties properties) {
        super(properties);
    }

    @Override
    public int getBurnTime(ItemStack itemStack, @Nullable RecipeType<?> recipeType) {
        return BURN_TICKS;
    }
}
