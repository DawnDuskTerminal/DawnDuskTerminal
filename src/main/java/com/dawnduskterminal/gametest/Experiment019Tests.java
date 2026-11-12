package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModItems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 试验物品 019：摆下来是一丛植物
 *
 * <p>钉住四件事：物品是那方块的 {@link BlockItem}（能摆下来的是方块本身，不是另一件物品）、方块走
 * {@link BushBlock} 的植物判定（身上站不住、底下的土没了自己消失）、属性照着原版花那一套
 * （空手一下就掉、踩不挡路、{@code PLANT} 地图色、{@code GRASS} 音效）、敲下来掉自己
 *
 * <p>「贴图是同一张」这条不在测试范围内：方块模型 {@code models/block/experiment_019.json} 引的就是
 * {@code dawnduskterminal:item/experiment_019}，改图只改一处
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class Experiment019Tests {
    private static final String BLOCK_ID = "dawnduskterminal:experiment_019";

    @GameTest(template = "ddt_empty")
    public static void itemPlacesThePlantBlock(GameTestHelper helper) {
        Block block = ModBlocks.EXPERIMENT_019.get();
        Item item = ModItems.EXPERIMENT_019.get();
        helper.assertTrue(
                item instanceof BlockItem,
                BLOCK_ID + " must be placed by a BlockItem, got "
                        + item.getClass().getSimpleName());
        helper.assertTrue(
                ((BlockItem) item).getBlock() == block,
                "the block item must place " + BLOCK_ID + ", got " + ((BlockItem) item).getBlock());
        helper.assertTrue(
                item.getDescriptionId().equals("block." + BLOCK_ID.replace(':', '.')),
                "a block item must take its name from the block, got " + item.getDescriptionId());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    @SuppressWarnings("deprecation") // 无参 getSoundType 是读方块音效的原版入口，NeoForge 标了废弃，原版也还在用
    public static void isAPlantLikeVanillaFlowers(GameTestHelper helper) {
        Block block = ModBlocks.EXPERIMENT_019.get();
        helper.assertTrue(
                block instanceof BushBlock,
                BLOCK_ID + " must extend BushBlock so it grows on soil and vanishes with it, got "
                        + block.getClass().getSimpleName());

        BlockState state = block.defaultBlockState();
        // instabreak：空手一下就掉，硬度就是 0
        helper.assertTrue(
                state.getDestroySpeed(null, null) == 0.0F,
                BLOCK_ID + " must break instantly, got destroy speed " + state.getDestroySpeed(null, null));
        helper.assertTrue(
                state.getSoundType() == SoundType.GRASS,
                BLOCK_ID + " must sound like grass, got " + state.getSoundType());
        helper.assertTrue(
                block.defaultMapColor() == MapColor.PLANT,
                BLOCK_ID + " map color must be PLANT, got " + block.defaultMapColor());
        // noCollission：站不进碰撞箱，活塞推过来是直接推掉
        helper.assertTrue(
                state.getCollisionShape(helper.getLevel(), BlockPos.ZERO).isEmpty(),
                BLOCK_ID + " must have no collision shape, got "
                        + state.getCollisionShape(helper.getLevel(), BlockPos.ZERO));
        helper.assertTrue(
                state.getPistonPushReaction() == PushReaction.DESTROY,
                BLOCK_ID + " must be destroyed by pistons, got " + state.getPistonPushReaction());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void survivesOnSoilAndDiesOnStone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockState plant = ModBlocks.EXPERIMENT_019.get().defaultBlockState();

        level.setBlockAndUpdate(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        helper.assertTrue(
                plant.canSurvive(level, pos),
                BLOCK_ID + " must survive on a grass block, exactly like a vanilla flower");

        level.setBlockAndUpdate(pos.below(), Blocks.FARMLAND.defaultBlockState());
        helper.assertTrue(plant.canSurvive(level, pos), BLOCK_ID + " must survive on farmland too");

        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        helper.assertFalse(plant.canSurvive(level, pos), BLOCK_ID + " must not survive on stone");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void dropsItself(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(pos.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(pos, ModBlocks.EXPERIMENT_019.get().defaultBlockState());

        List<ItemStack> drops = Block.getDrops(level.getBlockState(pos), level, pos, null);
        helper.assertTrue(drops.size() == 1, BLOCK_ID + " must drop exactly one stack, got " + drops);
        helper.assertTrue(
                drops.get(0).is(ModItems.EXPERIMENT_019.get()), BLOCK_ID + " must drop itself, got " + drops.get(0));
        helper.succeed();
    }

    /**
     * 交叉模型必须自己声明渲染层：方块模型的默认渲染层是 solid，贴图上的透明像素会被涂成黑底，
     * 一丛植物就变成一块黑方块。原版花是在代码里 {@code setRenderLayer} 干的，模组方块走模型的
     * {@code render_type} 字段——漏了这一行，服务端这边的测试一条都不会红，只有进游戏才看得出来
     *
     * <p>assets 下的模型服务端不加载，所以这里直接从 mod 的 classpath 读文件本身
     */
    @GameTest(template = "ddt_empty")
    public static void crossModelDeclaresCutoutRenderType(GameTestHelper helper) {
        JsonObject model = readModel(helper, "block/experiment_019");
        helper.assertTrue(
                model.has("parent") && model.get("parent").getAsString().equals("minecraft:block/cross"),
                "block/experiment_019 must stay a cross model, got " + model.get("parent"));
        helper.assertTrue(
                model.has("render_type"),
                "block/experiment_019 must declare render_type, otherwise its transparent pixels turn black");
        helper.assertTrue(
                model.get("render_type").getAsString().equals("minecraft:cutout"),
                "block/experiment_019 must render as cutout, got " + model.get("render_type"));
        helper.succeed();
    }

    /**
     * 能栽进花盆里
     *
     * <p>原版花盆只认 {@code FlowerPotBlock} 构造时登记进那张私有静态表的内容，所以「能不能栽」这件事的
     * 判据就是：一个 {@code potted_experiment_019} 方块存在，而且它里面栽的正是 019 那丛植物。
     * 敲掉它得掉「空花盆 + 那丛植物」，那是刷怪蛋之外唯一能把它拿回来的路（它自己没有物品形态）
     */
    @GameTest(template = "ddt_empty")
    public static void goesIntoAFlowerPot(GameTestHelper helper) {
        Block potted = ModBlocks.POTTED_EXPERIMENT_019.get();
        helper.assertTrue(
                potted instanceof FlowerPotBlock,
                "the potted variant must be a FlowerPotBlock, otherwise the flower pot cannot plant it, got "
                        + potted.getClass().getSimpleName());
        helper.assertTrue(
                ((FlowerPotBlock) potted).getPotted() == ModBlocks.EXPERIMENT_019.get(),
                "potted_experiment_019 must hold " + BLOCK_ID + ", it holds " + ((FlowerPotBlock) potted).getPotted());
        helper.assertTrue(
                potted.asItem() == Items.AIR,
                "a potted variant must not have an item form, vanilla has none either, got " + potted.asItem());

        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(5, 2, 5));
        level.setBlockAndUpdate(pos, potted.defaultBlockState());
        List<ItemStack> drops = Block.getDrops(level.getBlockState(pos), level, pos, null);
        helper.assertTrue(drops.size() == 2, "the potted plant must drop the pot and the plant, got " + drops);
        boolean pot = false;
        boolean plant = false;
        for (ItemStack drop : drops) {
            pot |= drop.is(Items.FLOWER_POT);
            plant |= drop.is(ModItems.EXPERIMENT_019.get());
        }
        helper.assertTrue(pot, "the potted plant must give the empty flower pot back, got " + drops);
        helper.assertTrue(plant, "the potted plant must give " + BLOCK_ID + " back, got " + drops);
        helper.succeed();
    }

    /** 从 mod 资源里读一个模型出来，路径相对 {@code assets/dawnduskterminal/models/} */
    private static JsonObject readModel(GameTestHelper helper, String path) {
        String resource = "/assets/dawnduskterminal/models/" + path + ".json";
        try (InputStream stream = Experiment019Tests.class.getResourceAsStream(resource)) {
            helper.assertTrue(stream != null, resource + " must ship inside the mod resources");
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            helper.assertTrue(false, "failed to read " + resource + ": " + e);
            return new JsonObject();
        }
    }

    private Experiment019Tests() {}
}
