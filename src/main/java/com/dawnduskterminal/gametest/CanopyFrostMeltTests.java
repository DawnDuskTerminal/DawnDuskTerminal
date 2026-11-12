package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 钉住苍穹霜唯一不许有的原版雪行为：本格方块光照高于 11 时自己融化
 * （{@code SnowLayerBlock.randomTick}）
 *
 * <p>测试对苍穹霜层与同光照下的原版雪层跑服务器对随机方块所做的同一个调用
 * （{@link GameTestHelper#randomTick}）。原版雪是对照组：它要是也不融化，说明光照根本没到，断言就证明
 * 不了任何东西，所以那个方向也要断言
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class CanopyFrostMeltTests {
    /** 原版融化的条件是「方块光照严格高于 11」 */
    private static final int MELTING_LIGHT = 11;

    private static final BlockPos FROST = new BlockPos(2, 2, 2);
    private static final BlockPos VANILLA_SNOW = new BlockPos(4, 2, 2);
    /** 炽热方块是光照 15，放在每层下方一格 */
    private static final BlockPos FROST_LIGHT = new BlockPos(2, 1, 2);

    private static final BlockPos VANILLA_LIGHT = new BlockPos(4, 1, 2);

    @GameTest(template = "ddt_empty")
    public static void canopyFrostSurvivesTheLightThatMeltsVanillaSnow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 用 setBlockAndUpdate（即 flag 3），因为光源只有带邻居/光照更新放置时光照引擎才会得知；
        // GameTestHelper.setBlock 用的是 flag 2，光照会一直是 0
        level.setBlockAndUpdate(
                helper.absolutePos(FROST_LIGHT),
                ModTerrainBlocks.BLAZING_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(
                helper.absolutePos(VANILLA_LIGHT),
                ModTerrainBlocks.BLAZING_BLOCK.get().defaultBlockState());
        helper.setBlock(FROST, ModTerrainBlocks.CANOPY_FROST.get());
        helper.setBlock(VANILLA_SNOW, Blocks.SNOW);

        // 光照引擎在服务器 tick 上跑，所以方块光照要过几个 tick 才到
        helper.runAfterDelay(10, () -> {
            BlockPos frostPos = helper.absolutePos(FROST);
            BlockPos snowPos = helper.absolutePos(VANILLA_SNOW);
            int frostLight = level.getBrightness(LightLayer.BLOCK, frostPos);
            int snowLight = level.getBrightness(LightLayer.BLOCK, snowPos);
            helper.assertTrue(
                    frostLight > MELTING_LIGHT && snowLight > MELTING_LIGHT,
                    "precondition: both layers must sit in block light > " + MELTING_LIGHT + ", got " + frostLight
                            + " and " + snowLight);

            helper.randomTick(VANILLA_SNOW);
            helper.assertFalse(
                    level.getBlockState(snowPos).is(Blocks.SNOW),
                    "control: vanilla snow must melt in this light, otherwise the assertion below proves nothing");

            helper.randomTick(FROST);
            helper.assertTrue(
                    level.getBlockState(frostPos).is(ModTerrainBlocks.CANOPY_FROST.get()),
                    "canopy frost must not melt in bright light");
            helper.succeed();
        });
    }

    /**
     * 掉落规则：只有精准采集掉自身，只有铲子掉苍穹霜球，别的一律什么都不掉
     *
     * <p>「别的一律什么都不掉」是这条用例的重点：改动之前这张战利品表没有任何工具条件，于是爆炸、
     * 活塞、别的方块换掉它这些非玩家来源也会掉出霜层本身。现在没有精准采集也没有铲子（工具是
     * {@link ItemStack#EMPTY}，与 {@code Block#dropResources(state, level, pos)} 那条路一致）就没有掉落，
     * 非玩家来源自然也不掉
     *
     * <p>数目按原版雪层那一套：几层就掉几个
     */
    @GameTest(template = "ddt_empty")
    public static void theLayerOnlyDropsForSilkTouchOrAShovel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(FROST);
        BlockState frost = ModTerrainBlocks.CANOPY_FROST.get().defaultBlockState();
        helper.assertTrue(
                frost.getValue(SnowLayerBlock.LAYERS) == 1, "a fresh canopy frost layer must start at one layer");

        helper.assertTrue(
                Block.getDrops(frost, level, pos, null, null, ItemStack.EMPTY).isEmpty(),
                "nothing may drop without silk touch or a shovel, otherwise anything that breaks the layer hands out"
                        + " the frost itself");

        ItemStack silk = new ItemStack(Items.DIAMOND_SHOVEL);
        silk.enchant(
                level.registryAccess()
                        .registryOrThrow(Registries.ENCHANTMENT)
                        .getHolderOrThrow(Enchantments.SILK_TOUCH),
                1);
        List<ItemStack> silkDrops = Block.getDrops(frost, level, pos, null, null, silk);
        helper.assertTrue(
                silkDrops.size() == 1
                        && silkDrops.get(0).is(ModTerrainBlocks.CANOPY_FROST_ITEM.get())
                        && silkDrops.get(0).getCount() == 1,
                "silk touch must drop one canopy frost layer per layer, got " + silkDrops);

        List<ItemStack> shovelDrops = Block.getDrops(frost, level, pos, null, null, new ItemStack(Items.IRON_SHOVEL));
        helper.assertTrue(
                shovelDrops.size() == 1
                        && shovelDrops.get(0).is(ModItems.CANOPY_FROST_BALL.get())
                        && shovelDrops.get(0).getCount() == 1,
                "a shovel must drop one canopy frost ball per layer, got " + shovelDrops);

        // 三层就是三个：数目跟着 layers 走，与原版雪层那套一致
        BlockState thick = frost.setValue(SnowLayerBlock.LAYERS, 3);
        List<ItemStack> thickDrops = Block.getDrops(thick, level, pos, null, null, new ItemStack(Items.IRON_SHOVEL));
        helper.assertTrue(
                thickDrops.size() == 1 && thickDrops.get(0).getCount() == 3,
                "three layers must yield three balls, got " + thickDrops);

        // 手里什么都没有，也没有精准采集：这是非玩家来源走的那条路
        helper.assertTrue(
                Block.getDrops(thick, level, pos, null, null, new ItemStack(Items.IRON_SWORD))
                        .isEmpty(),
                "a sword is neither a shovel nor silk touch, it must leave nothing behind");
        helper.succeed();
    }
}
