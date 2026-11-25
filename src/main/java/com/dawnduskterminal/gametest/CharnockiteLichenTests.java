package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DawnDuskTerminal.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CharnockiteLichenTests {
    @GameTest(template = "ddt_empty")
    public static void chronoDeepDarkDoesNotGenerateAncientCities(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var ancientCity =
                TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("has_structure/ancient_city"));
        var chronoDeepDark =
                registries.holderOrThrow(ResourceKey.create(Registries.BIOME, DawnDuskTerminal.id("deep_dark")));
        var vanillaDeepDark = registries.holderOrThrow(
                ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("deep_dark")));
        helper.assertTrue(!chronoDeepDark.is(ancientCity), "ancient cities must not generate in the chrono deep dark");
        helper.assertTrue(vanillaDeepDark.is(ancientCity), "the original deep dark must keep ancient cities");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void lichenSheetPatchPlacesSeveralSheets(GameTestHelper helper) {
        BlockPos center = new BlockPos(8, 2, 8);
        BlockState stone = ModTerrainBlocks.CHARNOCKITE.get().defaultBlockState();
        BlockPos.betweenClosedStream(center.offset(-5, -1, -5), center.offset(5, -1, 5))
                .forEach(pos -> helper.setBlock(pos, stone));
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(center);
        ConfiguredFeature<?, ?> patch = level.registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(DawnDuskTerminal.id("lichen_sheet_patch"));
        helper.assertTrue(patch != null, "deep dark must have a configured lichen sheet patch");
        helper.assertTrue(
                patch.place(level, level.getChunkSource().getGenerator(), RandomSource.create(0L), origin),
                "the lichen sheet patch must place on exposed charnockite");
        long sheets = BlockPos.betweenClosedStream(origin.offset(-5, 0, -5), origin.offset(5, 0, 5))
                .filter(pos -> level.getBlockState(pos).is(ModTerrainBlocks.LICHEN_SHEET.get()))
                .count();
        helper.assertTrue(sheets > 1, "a successful patch must place several lichen sheets on the test floor");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void veinFeaturePlacesGlowingLichenSheet(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 2, 2);
        helper.setBlock(pos.below(), ModTerrainBlocks.CHARNOCKITE.get().defaultBlockState());
        ServerLevel level = helper.getLevel();
        BlockPos absolutePos = helper.absolutePos(pos);
        ConfiguredFeature<?, ?> feature = level.registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(DawnDuskTerminal.id("lichen_sheet"));
        helper.assertTrue(feature != null, "deep dark must have a configured vein feature");
        helper.assertTrue(
                feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(0L), absolutePos),
                "the vein feature must place growth on charnockite");
        helper.assertBlockPresent(ModTerrainBlocks.LICHEN_SHEET.get(), pos);
        BlockState sheet = level.getBlockState(absolutePos);
        helper.assertTrue(sheet.getValue(BlockStateProperties.DOWN), "the sheet must attach to the floor");
        helper.assertTrue(sheet.getLightEmission(level, absolutePos) == 4, "the sheet must emit light level 4");
        helper.succeed();
    }

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
