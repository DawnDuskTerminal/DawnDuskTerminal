package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.block.MimicBlock;
import com.dawnduskterminal.registry.ModChronoOres;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(DawnDuskTerminal.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MimicTests {
    @GameTest(template = "ddt_empty")
    public static void mimicUsesTheHostTierAndKeepsItsDisguise(GameTestHelper helper) {
        BlockPos shallow = new BlockPos(3, 3, 3);
        BlockPos deep = new BlockPos(10, 3, 10);
        BlockState shallowState = placeMimic(helper, shallow, ModTerrainBlocks.CANOPY_STONE.get());
        BlockState deepState = placeMimic(helper, deep, ModTerrainBlocks.CHARNOCKITE.get());
        helper.assertTrue(!shallowState.getValue(MimicBlock.DEEP), "canopy stone must select the shallow tier");
        helper.assertTrue(deepState.getValue(MimicBlock.DEEP), "charnockite must select the deep tier");
        BlockPos absolute = helper.absolutePos(deep);
        ServerLevel level = helper.getLevel();
        Block ore = MimicBlock.disguise(level, deepState, absolute);
        helper.setBlock(deep.below(), ModChronoOres.CANOPY_STONE_DIAMOND_ORE.get());
        helper.assertTrue(ore == MimicBlock.disguise(level, deepState, absolute), "an unattached ore must be ignored");
        helper.assertTrue(
                ore != MimicBlock.disguise(level, shallowState, absolute), "the tiers must use distinct ore pools");
        helper.assertTrue(
                deepState.getLightEmission(helper.getLevel(), absolute) == 0, "the mimic must not emit light");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void mimicRejectsOtherSupports(GameTestHelper helper) {
        BlockPos pos = new BlockPos(5, 3, 5);
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(pos);
        helper.setBlock(pos.below(), Blocks.CALCITE);
        var mimic = ModTerrainBlocks.MIMIC.get();
        var feature = level.registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(DawnDuskTerminal.id("mimic"));
        helper.assertTrue(
                feature != null
                        && !feature.place(
                                level, level.getChunkSource().getGenerator(), RandomSource.create(0), absolute),
                "the feature must not generate mimics on calcite");
        helper.assertTrue(
                mimic.getStateForPlacement(level.getBlockState(absolute), level, absolute, Direction.DOWN) == null,
                "placement must reject calcite even outside world generation");

        BlockState state = placeMimic(helper, pos, ModTerrainBlocks.CHARNOCKITE.get(), Direction.DOWN);
        helper.setBlock(pos.east().below(), ModTerrainBlocks.GREENSCHIST.get());
        helper.assertTrue(
                mimic.getSpreader()
                        .spreadFromFaceTowardDirection(state, level, absolute, Direction.DOWN, Direction.EAST, false)
                        .isEmpty(),
                "spreading must reject greenschist");
        helper.setBlock(pos.below(), ModTerrainBlocks.TWILIGHT_STONE.get());
        helper.assertBlockPresent(Blocks.AIR, pos);
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void mimicPrefersTheOreItAttachesTo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = new BlockPos(5, 3, 5);
        BlockPos absolute = helper.absolutePos(pos);
        Block redstone = ModChronoOres.CHARNOCKITE_REDSTONE_ORE.get();
        BlockState state = placeMimic(helper, pos, redstone, Direction.DOWN);
        helper.setBlock(pos.below(), redstone.defaultBlockState().setValue(RedStoneOreBlock.LIT, true));
        helper.assertTrue(state.getValue(MimicBlock.DEEP), "deep ore must set the deep tier");
        helper.assertTrue(MimicBlock.disguise(level, state, absolute) == redstone, "the disguise must match its ore");
        helper.assertTrue(state.getLightEmission(level, absolute) == 0, "the mimic must not emit light");

        helper.setBlock(pos.above(), ModChronoOres.CANOPY_STONE_DIAMOND_ORE.get());
        var mimic = ModTerrainBlocks.MIMIC.get();
        BlockState twoFaces = mimic.getStateForPlacement(state, level, absolute, Direction.UP);
        helper.assertTrue(twoFaces != null, "the mimic must accept a second ore face");
        helper.assertTrue(
                MimicBlock.disguise(level, twoFaces, absolute) == redstone,
                "the lower ore must take precedence over the upper ore");
        helper.setBlock(pos.east(), Blocks.EMERALD_ORE);
        helper.assertTrue(
                mimic.getStateForPlacement(state, level, absolute, Direction.EAST) == null,
                "removed emerald ore must not be an eligible support");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void mimicGeneratesOnFloorsAndCanBePickedAndPlaced(GameTestHelper helper) {
        BlockPos generated = new BlockPos(3, 3, 3);
        Block host = ModTerrainBlocks.CHARNOCKITE.get();
        BlockState state = placeMimic(helper, generated, host, Direction.DOWN);
        ServerLevel level = helper.getLevel();
        BlockPos absolute = helper.absolutePos(generated);
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        ItemStack picked = state.getCloneItemStack(topHit(absolute), level, absolute, player);
        helper.assertTrue(picked.is(ModTerrainBlocks.MIMIC_ITEM.get()), "picking must return a mimic item");

        BlockPos support = new BlockPos(10, 2, 10);
        helper.setBlock(support, host.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, picked);
        helper.assertTrue(
                picked.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, topHit(helper.absolutePos(support))))
                        .consumesAction(),
                "the mimic item must place on the upward-facing side of the host");
        helper.assertBlockPresent(ModTerrainBlocks.MIMIC.get(), support.above());
        BlockState placed = level.getBlockState(helper.absolutePos(support.above()));
        helper.assertTrue(MultifaceBlock.hasFace(placed, Direction.DOWN), "the placed mimic must attach to the floor");
        helper.assertTrue(placed.getValue(MimicBlock.DEEP), "the placed mimic must use the new host's tier");
        helper.succeed();
    }

    private static BlockHitResult topHit(BlockPos pos) {
        return new BlockHitResult(Vec3.atBottomCenterOf(pos.above()), Direction.UP, pos, false);
    }

    @GameTest(template = "ddt_empty")
    public static void miningMimicConsumesDurabilityAndDropsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var player = FakePlayerFactory.getMinecraft(level);
        BlockPos first = new BlockPos(3, 3, 3);
        placeMimic(helper, first, ModTerrainBlocks.CANOPY_STONE.get());
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.enchant(level.registryAccess().holderOrThrow(Enchantments.UNBREAKING), 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        helper.assertTrue(player.gameMode.destroyBlock(helper.absolutePos(first)), "mining must break the mimic");
        int loss = tool.getDamageValue();
        helper.assertTrue(loss >= 64 && loss <= 128, "damage must be within 64–128, got " + loss);

        BlockPos second = new BlockPos(10, 3, 10);
        Block host = ModTerrainBlocks.CHARNOCKITE.get();
        placeMimic(helper, second, host);
        tool.setDamageValue(tool.getMaxDamage() - 63);
        helper.assertTrue(player.gameMode.destroyBlock(helper.absolutePos(second)), "mining must break the mimic");
        helper.assertTrue(tool.isEmpty(), "insufficient durability must break the tool");
        helper.assertBlockPresent(host, second.above());
        helper.assertEntityNotPresent(EntityType.ITEM);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.succeed();
    }

    private static BlockState placeMimic(GameTestHelper helper, BlockPos relative, Block host) {
        return placeMimic(helper, relative, host, Direction.UP);
    }

    private static BlockState placeMimic(GameTestHelper helper, BlockPos relative, Block host, Direction face) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative.relative(face), host.defaultBlockState());
        var feature = level.registryAccess()
                .registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(DawnDuskTerminal.id("mimic"));
        helper.assertTrue(
                feature != null
                        && feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(0), pos),
                "the old lichen feature must place the mimic");
        helper.assertBlockPresent(ModTerrainBlocks.MIMIC.get(), relative);
        BlockState placed = level.getBlockState(pos);
        helper.assertTrue(MultifaceBlock.hasFace(placed, face), "the mimic must attach to the host");
        return placed;
    }
}
