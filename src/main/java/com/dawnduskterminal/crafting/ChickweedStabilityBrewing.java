package com.dawnduskterminal.crafting;

import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModPotions;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;

/**
 * 繁缕菜 + 粗制的药水 → 稳定药水（{@code dawnduskterminal:stability_potion}）
 *
 * <p>原版那条药水规则按物品换内容（{@code PotionBrewing.Mix<Item>} 只比物品本身），
 * 拿瓶粗制的药水去酿只会得到一只 {@code minecraft:potion} 里装着本模组的药水，
 * 换不出模组自己那件物品。这一条挂 NeoForge 的 {@link IBrewingRecipe}：
 * {@code PotionBrewing#mix} 会先问它，再问原版那几条，而它拿到的是整个物品栈，
 * 于是能按药水内容认输入，产物也就能落到模组那瓶上
 *
 * <p>只认普通瓶：喷溅与滞留那两档照原版规矩从成品瓶换，不从粗制的药水换
 */
public final class ChickweedStabilityBrewing implements IBrewingRecipe {
    @Override
    public boolean isInput(ItemStack input) {
        return input.is(Items.POTION) && contents(input).is(Potions.AWKWARD);
    }

    @Override
    public boolean isIngredient(ItemStack ingredient) {
        return ingredient.is(ModTerrainBlocks.STELLARIA_VEGES_ITEM.get());
    }

    @Override
    public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
        return isInput(input) && isIngredient(ingredient)
                ? PotionContents.createItemStack(ModItems.STABILITY_POTION.get(), ModPotions.STABILITY)
                : ItemStack.EMPTY;
    }

    private static PotionContents contents(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
    }
}
