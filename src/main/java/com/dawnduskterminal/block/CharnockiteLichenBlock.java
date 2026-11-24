package com.dawnduskterminal.block;

import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

public final class CharnockiteLichenBlock extends Block {
    public CharnockiteLichenBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean onDestroyedByPlayer(
            BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        if (player != null
                && (willHarvest
                        || (level.isClientSide() && !player.isCreative() && state.canHarvestBlock(level, pos, player)))
                && hasSilkTouch(level, player.getMainHandItem())) {
            return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        }
        return level.setBlockAndUpdate(pos, ModTerrainBlocks.CHARNOCKITE.get().defaultBlockState());
    }

    @Override
    public void playerDestroy(
            Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity entity, ItemStack tool) {
        // 镐可能已耗尽耐久。此处仍可根据挖掘前复制的工具执行精准采集。
        if (hasSilkTouch(level, tool) && level.getBlockState(pos).is(ModTerrainBlocks.CHARNOCKITE.get())) {
            level.removeBlock(pos, false);
        }
        super.playerDestroy(level, player, pos, state, entity, tool);
    }

    private static boolean hasSilkTouch(Level level, ItemStack tool) {
        return EnchantmentHelper.getTagEnchantmentLevel(
                        level.registryAccess().holderOrThrow(Enchantments.SILK_TOUCH), tool)
                > 0;
    }
}
