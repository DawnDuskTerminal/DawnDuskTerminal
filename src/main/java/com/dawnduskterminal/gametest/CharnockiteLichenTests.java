package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DawnDuskTerminal.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CharnockiteLichenTests {
    @GameTest(template = "ddt_empty")
    public static void lichenEmitsLightLevelFour(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 2, 2);
        BlockState lichen = ModTerrainBlocks.CHARNOCKITE_LICHEN.get().defaultBlockState();
        ServerLevel level = helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        helper.assertTrue(
                lichen.getLightEmission(level, absolutePos) == 4, "charnockite lichen must emit light level 4");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void miningPeelsLichenUnlessThePickaxeHasSilkTouch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
        BlockPos regular = new BlockPos(2, 2, 2);
        mine(helper, regular, player, pickaxe, false);
        helper.assertBlockPresent(ModTerrainBlocks.CHARNOCKITE.get(), regular);
        helper.assertEntityNotPresent(EntityType.ITEM);

        pickaxe.enchant(level.registryAccess().holderOrThrow(Enchantments.SILK_TOUCH), 1);
        BlockPos silk = new BlockPos(6, 2, 2);
        mine(helper, silk, player, pickaxe, false);
        helper.assertTrue(level.isEmptyBlock(helper.absolutePos(silk)), "silk touch must remove the entire block");
        helper.assertItemEntityCountIs(ModTerrainBlocks.CHARNOCKITE_LICHEN_ITEM.get(), silk, 1, 1);

        BlockPos lastUse = new BlockPos(10, 2, 2);
        mine(helper, lastUse, player, pickaxe, true);
        helper.assertTrue(level.isEmptyBlock(helper.absolutePos(lastUse)), "silk touch must work on the last use");
        helper.assertItemEntityCountIs(ModTerrainBlocks.CHARNOCKITE_LICHEN_ITEM.get(), lastUse, 1, 1);
        helper.succeed();
    }

    private static void mine(
            GameTestHelper helper, BlockPos relative, Player player, ItemStack tool, boolean toolBroke) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        BlockState state = ModTerrainBlocks.CHARNOCKITE_LICHEN.get().defaultBlockState();
        helper.setBlock(relative, state);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        boolean canHarvest = state.canHarvestBlock(level, pos, player);
        if (toolBroke) {
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        helper.assertTrue(canHarvest, "an iron pickaxe must harvest charnockite lichen");
        helper.assertTrue(
                state.onDestroyedByPlayer(level, pos, player, canHarvest, state.getFluidState()),
                "mining must remove the lichen state");
        state.getBlock().playerDestroy(level, player, pos, state, null, tool);
    }
}
