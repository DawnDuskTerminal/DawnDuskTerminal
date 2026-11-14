package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.block.TowerdevBuilderType2Block;
import com.dawnduskterminal.block.TowerdevBuiltType2Block;
import com.dawnduskterminal.block.entity.TowerdevBuilderType2BlockEntity;
import com.dawnduskterminal.registry.ModBlockEntities;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModCreativeTabs;
import com.dawnduskterminal.registry.ModItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 砷铅铁建造器二型与它的产物构建方块二型的规格
 *
 * <p>建造器的四条：通着红石并且附近有人就一直造（不设格数上限，也没有 timeout）、
 * 造出来的是 {@code dawnduskterminal:towerdev_built_type2}、关机时不发光、方块状态只有 active 一条。
 *
 * <p>构建方块的四条：暗的那一档稳定存在、可以挖、右键把连着的同一档翻成绿的那一档，
 * 绿的那一档没有碰撞、能穿过去、能在里面往上爬、再右键翻回去；一次最多带 64 格。
 *
 * <p>建造那一段直接调方块实体的 tick，并且走那个「玩家已经递进来」的重载：
 * gametest 的 {@code makeMockPlayer} 造出来的玩家不在世界的玩家表里，{@code getNearestPlayer} 找不到它，
 * 走生产那个入口就只能造出「附近没人」这一种情形。递玩家进来的那一份与生产那一份共用同一段建造逻辑
 *
 * <p>每条用例退场时把自己摆的东西清干净：gametest 的世界跨轮次持久化，
 * 建造器一旦通着电就会一直沿一条线铺格子，不清的话隔壁用例的地面会被它铺满
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class TowerdevType2Tests {
    private static final String BUILDER = "dawnduskterminal:towerdev_builder_type2";

    private static final String BUILT = "dawnduskterminal:towerdev_built_type2";

    /** 格子摆在这一带 */
    private static final BlockPos CENTRE = new BlockPos(4, 3, 4);

    /** 建造那条线有多长：21 格，故意比暮色森林那一台的 16 格上限长 */
    private static final int LINE = 21;

    /** 连成一片那次要找多少格：70 格，故意比 64 格上限多 */
    private static final int CROWD = 70;

    @GameTest(template = "ddt_empty")
    public static void blockMatchesTheTwilightBuilder(GameTestHelper helper) {
        Item item = ModItems.TOWERDEV_BUILDER_TYPE2.get();
        helper.assertTrue(
                item instanceof BlockItem blockItem && blockItem.getBlock() == ModBlocks.TOWERDEV_BUILDER_TYPE2.get(),
                BUILDER + " must be the item form of its own block, got " + item);
        helper.assertTrue(
                item.getDescriptionId().equals("block.dawnduskterminal.towerdev_builder_type2"),
                "a block item must take its name from the block, got " + item.getDescriptionId());

        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CENTRE);
        BlockState state = ModBlocks.TOWERDEV_BUILDER_TYPE2.get().defaultBlockState();
        helper.assertTrue(
                state.getProperties().size() == 1 && state.hasProperty(TowerdevBuilderType2Block.ACTIVE),
                BUILDER + " carries exactly one state, the active property: no range, and no timeout variant either,"
                        + " got " + state.getProperties());
        helper.assertFalse(
                state.getValue(TowerdevBuilderType2Block.ACTIVE),
                BUILDER + " must come up idle, the redstone signal turns it on");
        helper.assertTrue(state.getLightEmission(level, pos) == 0, BUILDER + " must be dark while it is idle");
        helper.assertTrue(
                state.setValue(TowerdevBuilderType2Block.ACTIVE, true).getLightEmission(level, pos) == 4,
                BUILDER + " must emit the 4 light levels of the twilight builder while it is running, got "
                        + state.setValue(TowerdevBuilderType2Block.ACTIVE, true).getLightEmission(level, pos));
        helper.assertTrue(
                state.is(BlockTags.MINEABLE_WITH_PICKAXE),
                BUILDER + " must stay in #minecraft:mineable/pickaxe, otherwise a pickaxe cannot drop it");
        helper.assertTrue(
                BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(DawnDuskTerminal.id("towerdev_builder_type2")),
                BUILDER + " must be registered as a block entity type, the build progress lives there");

        helper.assertTrue(
                TowerdevBuilderType2BlockEntity.BUILD_INTERVAL == 10,
                "the builder must place one block every half second, got "
                        + TowerdevBuilderType2BlockEntity.BUILD_INTERVAL);
        helper.assertTrue(
                TowerdevBuilderType2BlockEntity.PLAYER_RADIUS == 16,
                "a player within 16 blocks counts as nearby, like the twilight builder, got "
                        + TowerdevBuilderType2BlockEntity.PLAYER_RADIUS);
        helper.assertTrue(
                TowerdevBuilderType2BlockEntity.SCAN_PER_TICK > 0,
                "the builder must be able to walk past the row it already built, got a scan limit of "
                        + TowerdevBuilderType2BlockEntity.SCAN_PER_TICK);

        Item product = ModItems.TOWERDEV_BUILT_TYPE2.get();
        helper.assertTrue(
                product instanceof BlockItem blockItem && blockItem.getBlock() == ModBlocks.TOWERDEV_BUILT_TYPE2.get(),
                BUILT + " must be the item form of its own block, got " + product);
        helper.assertTrue(
                product.getDescriptionId().equals("block.dawnduskterminal.towerdev_built_type2"),
                "a block item must take its name from the block, got " + product.getDescriptionId());
        BlockState built = ModBlocks.TOWERDEV_BUILT_TYPE2.get().defaultBlockState();
        helper.assertTrue(
                built.getProperties().size() == 1 && built.hasProperty(TowerdevBuiltType2Block.ON),
                BUILT + " carries exactly one state, the on property, got " + built.getProperties());
        helper.assertFalse(built.getValue(TowerdevBuiltType2Block.ON), BUILT + " must be built in the dark state");
        helper.assertTrue(
                TowerdevBuiltType2Block.MAX_CONNECTED == 64,
                "one right click brings at most 64 cells along, got " + TowerdevBuiltType2Block.MAX_CONNECTED);
        helper.assertFalse(
                built.is(BlockTags.MINEABLE_WITH_PICKAXE),
                BUILT + " is a 0.3 strength block, it stays out of #minecraft:mineable/pickaxe");
        helper.assertTrue(
                built.getLightEmission(level, pos) == 0
                        && built.setValue(TowerdevBuiltType2Block.ON, true).getLightEmission(level, pos) == 0,
                BUILT + " must not emit light at all: both states are drawn at full brightness by the model");
        helper.succeed();
    }

    /** 两个方块都在主栏目里，而且真的算得出来 */
    @GameTest(template = "ddt_empty")
    public static void bothBlocksAreListedInTheMainTab(GameTestHelper helper) {
        CreativeModeTab tab = ModCreativeTabs.MAIN.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                FeatureFlags.VANILLA_SET, true, helper.getLevel().registryAccess()));
        List<Item> listed =
                tab.getDisplayItems().stream().map(ItemStack::getItem).toList();
        helper.assertTrue(
                listed.contains(ModItems.TOWERDEV_BUILDER_TYPE2.get()),
                BUILDER + " must be listed in dawnduskterminal:main, the tab would hide it completely");
        helper.assertTrue(
                listed.contains(ModItems.TOWERDEV_BUILT_TYPE2.get()),
                BUILT + " must be listed in dawnduskterminal:main, the tab would hide it completely");
        helper.succeed();
    }

    /**
     * 通着电、有人在看就一直造：21 格照样造下去（暮色森林那一台在第 16 格就收工），
     * 人走了停手但机器不停摆（暮色森林那一台 60 tick 后会切到 timeout），人回来接着造
     */
    @GameTest(template = "ddt_large")
    public static void builderKeepsBuildingWhilePoweredAndWatched(GameTestHelper helper) {
        BlockPos machine = helper.absolutePos(CENTRE);
        ServerLevel level = helper.getLevel();
        BlockPos source = machine.east(1);
        try {
            running(level, machine, source);
            TowerdevBuilderType2BlockEntity builder = (TowerdevBuilderType2BlockEntity) level.getBlockEntity(machine);
            helper.assertTrue(builder != null, BUILDER + " must hand out its block entity, got none");

            // 假玩家站在原地看着正前方：yRot 为 0 就是 SOUTH
            Player watcher = helper.makeMockPlayer(GameType.SURVIVAL);
            helper.assertTrue(
                    watcher.getDirection() == Direction.SOUTH,
                    "the mock player must look south, got " + watcher.getDirection());

            tick(level, machine, builder, watcher, TowerdevBuilderType2BlockEntity.BUILD_INTERVAL * LINE);
            helper.assertTrue(
                    builtAlong(level, machine, Direction.SOUTH, LINE) == LINE,
                    "the builder must lay down " + LINE + " blocks in a row, one every "
                            + TowerdevBuilderType2BlockEntity.BUILD_INTERVAL + " ticks; the twilight builder stops at"
                            + " 16 blocks and times out, this one must not: got "
                            + builtAlong(level, machine, Direction.SOUTH, LINE));

            // 人走开：一格都不再往前，但机器不留任何「停摆」状态
            tick(level, machine, builder, null, TowerdevBuilderType2BlockEntity.BUILD_INTERVAL * 4);
            helper.assertTrue(
                    builtAlong(level, machine, Direction.SOUTH, LINE + 4) == LINE,
                    "with nobody nearby the builder must not place anything, got "
                            + builtAlong(level, machine, Direction.SOUTH, LINE + 4));
            helper.assertTrue(
                    level.getBlockState(machine).getValue(TowerdevBuilderType2Block.ACTIVE),
                    "being alone is not a reason to shut down, the machine stays active, got "
                            + level.getBlockState(machine));

            // 人回来：接着造，游标还记得走到哪一格
            tick(level, machine, builder, watcher, TowerdevBuilderType2BlockEntity.BUILD_INTERVAL);
            helper.assertTrue(
                    builtAlong(level, machine, Direction.SOUTH, LINE + 4) == LINE + 1,
                    "a player coming back must resume the row, got "
                            + builtAlong(level, machine, Direction.SOUTH, LINE + 4));
            helper.assertTrue(
                    level.getBlockState(machine.south(LINE + 1)).is(ModBlocks.TOWERDEV_BUILT_TYPE2.get()),
                    "the next block must land right behind the last one, got "
                            + level.getBlockState(machine.south(LINE + 1)));
            helper.succeed();
        } finally {
            clear(level, machine, Direction.SOUTH, LINE + 4);
        }
    }

    /** 往上看就竖着往上造，往下看就往下造，平着看按朝向造 */
    @GameTest(template = "ddt_empty")
    public static void facingFollowsThePlayersPitch(GameTestHelper helper) {
        helper.assertTrue(
                TowerdevBuilderType2BlockEntity.facing(null) == null, "no player, no direction: the builder must wait");
        helper.assertTrue(
                TowerdevBuilderType2BlockEntity.facing(watcher(helper, Direction.SOUTH, 0.0F)) == Direction.SOUTH,
                "looking level must follow the horizontal facing");
        helper.assertTrue(
                TowerdevBuilderType2BlockEntity.facing(watcher(helper, Direction.NORTH, 0.0F)) == Direction.NORTH,
                "looking level must follow the horizontal facing");
        helper.assertTrue(
                TowerdevBuilderType2BlockEntity.facing(watcher(helper, Direction.SOUTH, -90.0F)) == Direction.UP,
                "looking straight up must build upwards");
        helper.assertTrue(
                TowerdevBuilderType2BlockEntity.facing(watcher(helper, Direction.SOUTH, 90.0F)) == Direction.DOWN,
                "looking straight down must build downwards");
        helper.succeed();
    }

    /** 前路被占住就在原地等，那一格空出来接着往前造，不走回头路 */
    @GameTest(template = "ddt_empty")
    public static void obstructedCellMakesItWait(GameTestHelper helper) {
        BlockPos machine = helper.absolutePos(CENTRE);
        ServerLevel level = helper.getLevel();
        BlockPos source = machine.east(1);
        try {
            running(level, machine, source);
            TowerdevBuilderType2BlockEntity builder = (TowerdevBuilderType2BlockEntity) level.getBlockEntity(machine);
            Player watcher = helper.makeMockPlayer(GameType.SURVIVAL);

            BlockPos blocked = machine.south(1);
            level.setBlockAndUpdate(blocked, Blocks.STONE.defaultBlockState());
            tick(level, machine, builder, watcher, TowerdevBuilderType2BlockEntity.BUILD_INTERVAL * 2);
            helper.assertTrue(
                    level.getBlockState(blocked).is(Blocks.STONE),
                    "an occupied cell must be left alone, got " + level.getBlockState(blocked));
            helper.assertTrue(
                    level.getBlockState(machine.south(2)).isAir(),
                    "the row must not skip over the occupied cell, got " + level.getBlockState(machine.south(2)));

            level.setBlockAndUpdate(blocked, Blocks.AIR.defaultBlockState());
            tick(level, machine, builder, watcher, TowerdevBuilderType2BlockEntity.BUILD_INTERVAL);
            helper.assertTrue(
                    level.getBlockState(blocked).is(ModBlocks.TOWERDEV_BUILT_TYPE2.get()),
                    "once the cell is free the builder must fill it, got " + level.getBlockState(blocked));
            helper.succeed();
        } finally {
            clear(level, machine, Direction.SOUTH, 4);
        }
    }

    /**
     * 再次开机时，前方那一段上一次造出来的产物还在：跨过它，从末端接着造
     *
     * <p>产物不会消失，所以「游标从本方块重新起头」这件事必须能把已经造出来的那一段走掉，
     * 否则一断电再通电就永远卡在第一格上
     */
    @GameTest(template = "ddt_empty")
    public static void builderResumesAfterTheLineItAlreadyBuilt(GameTestHelper helper) {
        BlockPos machine = helper.absolutePos(CENTRE);
        ServerLevel level = helper.getLevel();
        BlockPos source = machine.east(1);
        int existing = 5;
        try {
            for (int offset = 1; offset <= existing; offset++) {
                level.setBlockAndUpdate(
                        machine.south(offset),
                        ModBlocks.TOWERDEV_BUILT_TYPE2.get().defaultBlockState());
            }
            running(level, machine, source);
            TowerdevBuilderType2BlockEntity builder = (TowerdevBuilderType2BlockEntity) level.getBlockEntity(machine);
            Player watcher = helper.makeMockPlayer(GameType.SURVIVAL);

            // 先断一次电：游标被清掉，就像刚摆下来那样从本方块起头
            level.setBlockAndUpdate(source, Blocks.AIR.defaultBlockState());
            tick(level, machine, builder, watcher, TowerdevBuilderType2BlockEntity.BUILD_INTERVAL);
            helper.assertFalse(
                    level.getBlockState(machine).getValue(TowerdevBuilderType2Block.ACTIVE),
                    "the machine must be off after the signal was cut, got " + level.getBlockState(machine));

            // 再通电：应当出现在那一段的末端，而不是卡在第一格
            level.setBlockAndUpdate(source, Blocks.REDSTONE_BLOCK.defaultBlockState());
            tick(level, machine, builder, watcher, TowerdevBuilderType2BlockEntity.BUILD_INTERVAL);
            helper.assertTrue(
                    level.getBlockState(machine.south(existing + 1)).is(ModBlocks.TOWERDEV_BUILT_TYPE2.get()),
                    "the builder must continue at the far end of the row it already built, expected "
                            + BUILT + " at the offset after " + existing + ", got "
                            + level.getBlockState(machine.south(existing + 1)));
            helper.assertTrue(
                    builtAlong(level, machine, Direction.SOUTH, existing) == existing,
                    "the old row must be left exactly as it was");
            helper.succeed();
        } finally {
            clear(level, machine, Direction.SOUTH, existing + 2);
        }
    }

    /** 并排的两格不画中间那一面；对着石头的面照旧由石头那边剔掉，朝着空气的面照画 */
    @GameTest(template = "ddt_empty")
    public static void neighboursDoNotDrawTheirSharedFace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(CENTRE);
        BlockPos east = here.east(1);
        BlockPos north = here.north(1);
        try {
            level.setBlockAndUpdate(here, ModBlocks.TOWERDEV_BUILT_TYPE2.get().defaultBlockState());
            level.setBlockAndUpdate(east, ModBlocks.TOWERDEV_BUILT_TYPE2.get().defaultBlockState());
            level.setBlockAndUpdate(north, Blocks.STONE.defaultBlockState());

            helper.assertFalse(
                    Block.shouldRenderFace(level.getBlockState(here), level, here, Direction.EAST, east),
                    "two cells of the lattice must not draw the face between them");
            helper.assertFalse(
                    Block.shouldRenderFace(
                            level.getBlockState(here).setValue(TowerdevBuiltType2Block.ON, true),
                            level,
                            here,
                            Direction.EAST,
                            east),
                    "the culling does not care about the state, a green cell next to a dark one culls too");
            helper.assertFalse(
                    Block.shouldRenderFace(level.getBlockState(here), level, here, Direction.NORTH, north),
                    "a face against a solid block is culled by that solid block, as in vanilla");
            helper.assertTrue(
                    Block.shouldRenderFace(level.getBlockState(here), level, here, Direction.UP, here.above()),
                    "a face towards the air must be drawn");
            helper.succeed();
        } finally {
            for (BlockPos pos : new BlockPos[] {here, east, north}) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /**
     * 手上拿着本方块自己的方块物品右键：真的摆下去，不换档
     *
     * <p>这里不能只钉返回值：上一版返回 {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}，那一条在
     * {@code ServerPlayerGameMode#useItemOn} 里是「接着调 {@code useWithoutItem}」——正好就是换档，
     * 点击被吃掉、方块永远摆不下去。所以用例按原版那个顺序走一遍：先 {@code useItemOn}，
     * 只有既没消费、又不是 PASS_TO_DEFAULT 时才轮到 {@code useWithoutItem}，最后才是 {@code ItemStack#useOn}，
     * 并且要以「那一格真的出现了方块」收尾
     */
    @GameTest(template = "ddt_empty")
    public static void holdingTheBlockItselfPlacesInsteadOfToggling(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(CENTRE);
        BlockPos above = here.above();
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockHitResult hit = hitAt(here);
        try {
            level.setBlockAndUpdate(here, ModBlocks.TOWERDEV_BUILT_TYPE2.get().defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.TOWERDEV_BUILT_TYPE2.get()));

            ItemInteractionResult holding = level.getBlockState(here)
                    .useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, hit);
            helper.assertFalse(
                    holding.consumesAction(),
                    "the lattice must not swallow the click while the player holds its own block item, got " + holding);
            helper.assertTrue(
                    holding == ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION,
                    "the block interaction must be skipped, PASS_TO_DEFAULT would run useWithoutItem (the toggle)"
                            + " before placement, got " + holding);

            // 原版那一段的顺序：跳过默认交互之后才是物品的 useOn
            if (holding == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                level.getBlockState(here).useWithoutItem(level, player, hit);
            }
            InteractionResult used =
                    player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            helper.assertTrue(used.consumesAction(), "the placement must actually happen, got " + used);
            helper.assertTrue(
                    level.getBlockState(above).is(ModBlocks.TOWERDEV_BUILT_TYPE2.get()),
                    "the block must land on the clicked face, got " + level.getBlockState(above));
            helper.assertFalse(
                    isOn(level, here), "the clicked cell must not have been flipped, got " + level.getBlockState(here));

            // 换成别的物品：照旧换档
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Blocks.DIRT.asItem()));
            ItemInteractionResult holdingSomethingElse = level.getBlockState(here)
                    .useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(
                    holdingSomethingElse.consumesAction(),
                    "any other item still toggles the cell, got " + holdingSomethingElse);
            helper.assertTrue(
                    isOn(level, here),
                    "the cell must be green after a click with something else in hand, got "
                            + level.getBlockState(here));
            helper.succeed();
        } finally {
            level.setBlockAndUpdate(above, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(here, Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * 红石开机、断信号关机、再来信号再开机
     *
     * <p>摆下那一刻按当时的信号定初值：贴着通着电的红石线摆，那条线不会因为这台机器落地而变化，
     * 也就不会给它发更新，只靠 {@code neighborChanged} 会漏掉这一种
     */
    @GameTest(template = "ddt_empty")
    public static void redstoneTurnsItOnAndOff(GameTestHelper helper) {
        BlockPos machine = helper.absolutePos(CENTRE);
        ServerLevel level = helper.getLevel();
        BlockPos source = machine.east(1);
        try {
            level.setBlockAndUpdate(source, Blocks.REDSTONE_BLOCK.defaultBlockState());
            BlockPlaceContext context = new BlockPlaceContext(
                    helper.makeMockPlayer(GameType.CREATIVE),
                    InteractionHand.MAIN_HAND,
                    new ItemStack(ModItems.TOWERDEV_BUILDER_TYPE2.get()),
                    hitAt(machine));
            level.setBlockAndUpdate(
                    machine, ModBlocks.TOWERDEV_BUILDER_TYPE2.get().getStateForPlacement(context));

            helper.assertTrue(
                    level.getBlockState(machine).getValue(TowerdevBuilderType2Block.ACTIVE),
                    "a builder placed against a live redstone source must start running, got "
                            + level.getBlockState(machine));
            helper.assertTrue(
                    level.getBlockState(machine).getLightEmission(level, machine) == 4,
                    "a running builder must light up");

            level.setBlockAndUpdate(source, Blocks.AIR.defaultBlockState());
            helper.assertTrue(
                    !level.getBlockState(machine).getValue(TowerdevBuilderType2Block.ACTIVE),
                    "cutting the signal must stop the builder in the same tick, got " + level.getBlockState(machine));
            helper.assertTrue(
                    level.getBlockState(machine).getLightEmission(level, machine) == 0,
                    "a stopped builder must go dark");

            level.setBlockAndUpdate(source, Blocks.REDSTONE_BLOCK.defaultBlockState());
            helper.assertTrue(
                    level.getBlockState(machine).getValue(TowerdevBuilderType2Block.ACTIVE),
                    "a fresh signal must start it again, got " + level.getBlockState(machine));
            helper.succeed();
        } finally {
            clear(level, machine, Direction.EAST, 2);
        }
    }

    /** 右键把连着的同一档整片翻过去，只有六个面挨着的才算相连 */
    @GameTest(template = "ddt_empty")
    public static void rightClickFlipsTheWholeConnectedCluster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(CENTRE);
        BlockPos second = first.east(1);
        BlockPos third = first.east(2);
        // 与这一排三个都只隔着一个斜角：六面判据下它自成一伙
        BlockPos sideways = first.east(1).south(2);
        try {
            for (BlockPos pos : new BlockPos[] {first, second, third, sideways}) {
                level.setBlockAndUpdate(
                        pos, ModBlocks.TOWERDEV_BUILT_TYPE2.get().defaultBlockState());
            }

            helper.assertTrue(
                    TowerdevBuiltType2Block.toggle(level, first) == 3,
                    "the three cells in a row are one cluster, the one a diagonal away is not");
            helper.assertTrue(
                    isOn(level, first) && isOn(level, second) && isOn(level, third),
                    "the whole cluster must turn on, got " + level.getBlockState(first) + " "
                            + level.getBlockState(second) + " " + level.getBlockState(third));
            helper.assertFalse(isOn(level, sideways), "only the six faces connect, a diagonal neighbour must stay off");

            // 绿的那一片再右键一次：整片翻回原样
            helper.assertTrue(
                    TowerdevBuiltType2Block.toggle(level, second) == 3,
                    "the green cluster must flip back as one piece");
            helper.assertTrue(
                    !isOn(level, first) && !isOn(level, second) && !isOn(level, third),
                    "the cluster must be back to the dark state");
            helper.succeed();
        } finally {
            for (BlockPos pos : new BlockPos[] {first, second, third, sideways}) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** 一次最多带 64 格：70 格连着摆，右键只翻得动 64 格 */
    @GameTest(template = "ddt_empty")
    public static void oneRightClickBringsAtMostSixtyFourCells(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(CENTRE);
        List<BlockPos> cells = new ArrayList<>();
        try {
            for (int offset = 0; offset < CROWD; offset++) {
                BlockPos pos = start.east(offset);
                cells.add(pos);
                level.setBlockAndUpdate(
                        pos, ModBlocks.TOWERDEV_BUILT_TYPE2.get().defaultBlockState());
            }

            helper.assertTrue(
                    TowerdevBuiltType2Block.connected(level, start, false).size()
                            == TowerdevBuiltType2Block.MAX_CONNECTED,
                    "the search must stop at " + TowerdevBuiltType2Block.MAX_CONNECTED + " cells, got "
                            + TowerdevBuiltType2Block.connected(level, start, false)
                                    .size());
            helper.assertTrue(
                    TowerdevBuiltType2Block.toggle(level, start) == TowerdevBuiltType2Block.MAX_CONNECTED,
                    "one right click must bring exactly " + TowerdevBuiltType2Block.MAX_CONNECTED + " cells along");

            int on = 0;
            for (BlockPos pos : cells) {
                if (isOn(level, pos)) {
                    on++;
                }
            }
            helper.assertTrue(
                    on == TowerdevBuiltType2Block.MAX_CONNECTED,
                    "exactly the first 64 cells must turn green, got " + on);
            helper.assertTrue(
                    isOn(level, start) && !isOn(level, start.east(CROWD - 1)),
                    "the search walks out from the clicked cell, so the far end stays dark");
            helper.succeed();
        } finally {
            for (BlockPos pos : cells) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** 绿的那一档没有碰撞、能爬，两档都是满亮画出来的（方块自己不发光的另一层意思见资源那条用例） */
    @GameTest(template = "ddt_empty")
    public static void greenStateIsPassableAndClimbable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CENTRE);
        Player entity = helper.makeMockPlayer(GameType.SURVIVAL);
        BlockState off = ModBlocks.TOWERDEV_BUILT_TYPE2.get().defaultBlockState();
        BlockState on = off.setValue(TowerdevBuiltType2Block.ON, true);

        helper.assertFalse(
                off.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty(),
                "the dark state is a solid block you cannot walk through");
        helper.assertTrue(
                on.getCollisionShape(level, pos, CollisionContext.empty()).isEmpty(),
                "the green state must have no collision at all");
        helper.assertFalse(off.getBlock().isLadder(off, level, pos, entity), "the dark state is not climbable");
        helper.assertTrue(
                on.getBlock().isLadder(on, level, pos, entity),
                "the green state must act like a ladder, that is how you get up through it");
        helper.assertFalse(
                on.getShape(level, pos, CollisionContext.empty()).isEmpty(),
                "the green state still needs an outline shape, otherwise a right click has nothing to hit");
        helper.succeed();
    }

    /**
     * 资源那一套：两个方块各两个状态变体、建造器两层模型（发光层按满亮画）、
     * 构建方块一份模型（六面都是满亮、贴的是 8x8 连接图集）、两个物品模型、四张图集加两张单图都在 jar 里、
     * 两个方块都有战利品表（挖掉掉自己）
     *
     * <p>assets 下的模型服务端不加载，构建也不会因为路径写错而失败，所以这里直接读 classpath 上的文件本身
     */
    @GameTest(template = "ddt_empty")
    public static void assetsMatchTheBlocks(GameTestHelper helper) {
        JsonObject builderStates = readJson(helper, "/assets/dawnduskterminal/blockstates/towerdev_builder_type2.json");
        JsonObject builderVariants = builderStates.getAsJsonObject("variants");
        helper.assertTrue(
                builderVariants.size() == 2,
                BUILDER + " has one state, its blockstates file must carry two variants, got "
                        + builderVariants.size());
        for (Map.Entry<String, JsonElement> variant : builderVariants.entrySet()) {
            String key = variant.getKey();
            String model = variant.getValue().getAsJsonObject().get("model").getAsString();
            String expected = key.startsWith("active=true")
                    ? "dawnduskterminal:block/towerdev_builder_type2_on"
                    : "dawnduskterminal:block/towerdev_builder_type2_off";
            helper.assertTrue(
                    model.equals(expected), "variant " + key + " must point at " + expected + ", got " + model);
        }

        for (String state : new String[] {"on", "off"}) {
            String name = "block/towerdev_builder_type2_" + state;
            JsonObject model =
                    readJson(helper, "/assets/dawnduskterminal/models/block/towerdev_builder_type2_" + state + ".json");
            JsonArray elements = model.getAsJsonArray("elements");
            helper.assertTrue(
                    elements != null && elements.size() == 2,
                    name + " draws a base layer plus a glow layer, got "
                            + (elements == null ? "no elements" : elements.size()));
            helper.assertTrue(
                    model.get("render_type").getAsString().equals("minecraft:cutout"),
                    name + " must be drawn as a cutout, got " + model.get("render_type"));
            JsonObject glow = elements.get(1)
                    .getAsJsonObject()
                    .getAsJsonObject("faces")
                    .getAsJsonObject("up")
                    .getAsJsonObject("neoforge_data");
            helper.assertTrue(
                    glow.get("block_light").getAsInt() == 15
                            && glow.get("sky_light").getAsInt() == 15,
                    name + " must draw its glow layer at full brightness, got " + glow);
            JsonObject textures = model.getAsJsonObject("textures");
            helper.assertTrue(
                    textures.get("all").getAsString().equals("dawnduskterminal:" + name)
                            && textures.get("all2").getAsString().equals("dawnduskterminal:" + name + "_glow"),
                    name + " must point its two layers at its own two textures, got " + textures);
            assertModelTexturesResolve(helper, model, name);
        }

        JsonObject builtStates = readJson(helper, "/assets/dawnduskterminal/blockstates/towerdev_built_type2.json");
        JsonObject builtVariants = builtStates.getAsJsonObject("variants");
        helper.assertTrue(
                builtVariants.size() == 2,
                BUILT + " has one state, its blockstates file must carry two variants, got " + builtVariants.size());
        for (Map.Entry<String, JsonElement> variant : builtVariants.entrySet()) {
            String key = variant.getKey();
            String model = variant.getValue().getAsJsonObject().get("model").getAsString();
            String expected = key.startsWith("on=true")
                    ? "dawnduskterminal:block/towerdev_built_type2_on"
                    : "dawnduskterminal:block/towerdev_built_type2_off";
            helper.assertTrue(
                    model.equals(expected), "variant " + key + " must point at " + expected + ", got " + model);
        }

        for (String state : new String[] {"on", "off"}) {
            String name = "block/towerdev_built_type2_" + state;
            JsonObject model =
                    readJson(helper, "/assets/dawnduskterminal/models/block/towerdev_built_type2_" + state + ".json");
            JsonArray elements = model.getAsJsonArray("elements");
            helper.assertTrue(
                    elements != null && elements.size() == 1,
                    name + " is a single lattice layer, got " + (elements == null ? "no elements" : elements.size()));
            JsonObject element = elements.get(0).getAsJsonObject();
            helper.assertFalse(
                    element.get("shade").getAsBoolean(),
                    name + " must not be shaded, the lattice is drawn flat at full brightness");
            for (Map.Entry<String, JsonElement> face :
                    element.getAsJsonObject("faces").entrySet()) {
                JsonObject faceJson = face.getValue().getAsJsonObject();
                JsonObject light = faceJson.getAsJsonObject("neoforge_data");
                helper.assertTrue(
                        light != null
                                && light.get("block_light").getAsInt() == 15
                                && light.get("sky_light").getAsInt() == 15,
                        name + " face " + face.getKey() + " must be drawn at full brightness, got " + faceJson);
            }
            JsonObject textures = model.getAsJsonObject("textures");
            helper.assertTrue(
                    textures.get("all").getAsString().equals("dawnduskterminal:" + name + "_connected"),
                    name + " must draw the 8x8 connected sheet, got " + textures.get("all"));
            helper.assertTrue(
                    textures.get("particle").getAsString().equals("dawnduskterminal:" + name),
                    name + " must take its particles from the 16x16 single image, got " + textures.get("particle"));
            assertModelTexturesResolve(helper, model, name);
        }

        JsonObject builderItem = readJson(helper, "/assets/dawnduskterminal/models/item/towerdev_builder_type2.json");
        helper.assertTrue(
                builderItem.get("parent").getAsString().equals("dawnduskterminal:block/towerdev_builder_type2_off"),
                "the held builder must look the way it comes up in the world: the off model, got "
                        + builderItem.get("parent"));
        JsonObject builtItem = readJson(helper, "/assets/dawnduskterminal/models/item/towerdev_built_type2.json");
        helper.assertTrue(
                builtItem.get("parent").getAsString().equals("minecraft:block/block"),
                "the built block item must be a block-shaped model, not a flat sprite, got " + builtItem.get("parent"));
        JsonArray builtItemElements = builtItem.getAsJsonArray("elements");
        helper.assertTrue(
                builtItemElements != null && builtItemElements.size() == 1,
                "the built block item is one cube, got "
                        + (builtItemElements == null ? "no elements" : builtItemElements.size()));
        JsonObject builtItemFaces = builtItemElements.get(0).getAsJsonObject().getAsJsonObject("faces");
        helper.assertTrue(
                builtItemFaces.size() == 6,
                "the built block item must cover all six faces, got " + builtItemFaces.size());
        for (Map.Entry<String, JsonElement> face : builtItemFaces.entrySet()) {
            JsonObject light = face.getValue().getAsJsonObject().getAsJsonObject("neoforge_data");
            helper.assertTrue(
                    light != null
                            && light.get("block_light").getAsInt() == 15
                            && light.get("sky_light").getAsInt() == 15,
                    "the held block must be as bright as the placed one, face " + face.getKey() + " got " + face);
        }
        helper.assertTrue(
                builtItem
                        .getAsJsonObject("textures")
                        .get("all")
                        .getAsString()
                        .equals("dawnduskterminal:block/towerdev_built_type2_off"),
                "the built block item must use the dark lattice sprite, not the 8x8 sheet");
        assertModelTexturesResolve(helper, builtItem, "item/towerdev_built_type2");

        for (String texture : new String[] {
            "towerdev_builder_type2_on",
            "towerdev_builder_type2_on_glow",
            "towerdev_builder_type2_off",
            "towerdev_builder_type2_off_glow",
            "towerdev_built_type2_off",
            "towerdev_built_type2_off_connected",
            "towerdev_built_type2_on",
            "towerdev_built_type2_on_connected"
        }) {
            String path = "/assets/dawnduskterminal/textures/block/" + texture + ".png";
            try (InputStream stream = TowerdevType2Tests.class.getResourceAsStream(path)) {
                helper.assertTrue(stream != null, path + " must ship inside the mod resources");
            } catch (IOException e) {
                helper.assertTrue(false, "failed to read " + path + ": " + e);
            }
        }

        for (String block : new String[] {"towerdev_builder_type2", "towerdev_built_type2"}) {
            String path = "/data/dawnduskterminal/loot_table/blocks/" + block + ".json";
            try (InputStream stream = TowerdevType2Tests.class.getResourceAsStream(path)) {
                helper.assertTrue(
                        stream != null, path + " must exist: both blocks drop themselves, unlike the twilight pair");
            } catch (IOException e) {
                helper.assertTrue(false, "failed to read " + path + ": " + e);
            }
        }

        // 服务端有 ticker（建造要走它），客户端一颗都不要
        BlockState running = ModBlocks.TOWERDEV_BUILDER_TYPE2
                .get()
                .defaultBlockState()
                .setValue(TowerdevBuilderType2Block.ACTIVE, true);
        helper.assertTrue(
                ModBlocks.TOWERDEV_BUILDER_TYPE2
                                .get()
                                .getTicker(helper.getLevel(), running, ModBlockEntities.TOWERDEV_BUILDER_TYPE2.get())
                        != null,
                "the server must tick the builder, that is where the building happens");
        helper.succeed();
    }

    /**
     * 模型里每一个贴图引用都要解析得到
     *
     * <p>面上写的是 {@code #键}，那个键必须在同一个模型的 {@code textures} 里存在；
     * {@code textures} 里指向 {@code #别的键} 的也要一路查得到。少一个键构建不会失败，
     * 游戏里那一面画成紫黑格子，所以这里逐面查一遍
     */
    private static void assertModelTexturesResolve(GameTestHelper helper, JsonObject model, String name) {
        JsonObject textures = model.getAsJsonObject("textures");
        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            String value = entry.getValue().getAsString();
            helper.assertTrue(
                    !value.startsWith("#") || textures.has(value.substring(1)),
                    name + " texture '" + entry.getKey() + "' points at the missing key " + value);
        }
        JsonArray elements = model.getAsJsonArray("elements");
        if (elements == null) {
            return;
        }
        for (int index = 0; index < elements.size(); index++) {
            JsonObject faces = elements.get(index).getAsJsonObject().getAsJsonObject("faces");
            for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                String texture =
                        face.getValue().getAsJsonObject().get("texture").getAsString();
                helper.assertTrue(
                        texture.startsWith("#") && textures.has(texture.substring(1)),
                        name + " element " + index + " face " + face.getKey() + " points at the missing key "
                                + texture);
            }
        }
    }

    /** 跑 {@code ticks} 个 tick，玩家递 {@code player}（{@code null} 就是附近没人） */
    private static void tick(
            ServerLevel level, BlockPos machine, TowerdevBuilderType2BlockEntity builder, Player player, int ticks) {
        for (int i = 0; i < ticks; i++) {
            TowerdevBuilderType2BlockEntity.tick(level, machine, level.getBlockState(machine), builder, player);
        }
    }

    /** 从 {@code machine} 沿 {@code direction} 数一共有几格构建方块 */
    private static int builtAlong(ServerLevel level, BlockPos machine, Direction direction, int cells) {
        int count = 0;
        for (int i = 1; i <= cells; i++) {
            if (level.getBlockState(machine.relative(direction, i)).is(ModBlocks.TOWERDEV_BUILT_TYPE2.get())) {
                count++;
            }
        }
        return count;
    }

    /** 一个朝向给定、俯仰角给定的假玩家 */
    private static Player watcher(GameTestHelper helper, Direction facing, float pitch) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setYRot(facing.toYRot());
        player.setXRot(pitch);
        return player;
    }

    /**
     * 把 {@code source} 那一格铺成红石块，再在 {@code machine} 上摆一台通着电的建造器
     *
     * <p>红石块不能省：{@code active} 是照着红石信号算出来的，光把状态写成真、旁边却没信号，
     * 下一次邻居更新就会把它改回假——那一台机器在测试里活不过第一格
     */
    private static void running(ServerLevel level, BlockPos machine, BlockPos source) {
        level.setBlockAndUpdate(source, Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(
                machine,
                ModBlocks.TOWERDEV_BUILDER_TYPE2
                        .get()
                        .defaultBlockState()
                        .setValue(TowerdevBuilderType2Block.ACTIVE, true));
    }

    private static boolean isOn(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BooleanProperty on = TowerdevBuiltType2Block.ON;
        return state.is(ModBlocks.TOWERDEV_BUILT_TYPE2.get()) && state.getValue(on);
    }

    private static void set(ServerLevel level, BlockPos pos, Block block) {
        level.setBlockAndUpdate(pos, block.defaultBlockState());
    }

    /** 清掉机器、它东边那一格红石源，以及它那一整条线上的东西 */
    private static void clear(ServerLevel level, BlockPos machine, Direction direction, int cells) {
        for (int i = 0; i <= cells; i++) {
            BlockPos pos = machine.relative(direction, i);
            BlockState state = level.getBlockState(pos);
            if (state.is(ModBlocks.TOWERDEV_BUILDER_TYPE2.get())
                    || state.is(ModBlocks.TOWERDEV_BUILT_TYPE2.get())
                    || state.is(Blocks.REDSTONE_BLOCK)
                    || state.is(Blocks.STONE)) {
                set(level, pos, Blocks.AIR);
            }
        }
        BlockPos source = machine.east(1);
        if (level.getBlockState(source).is(Blocks.REDSTONE_BLOCK)) {
            set(level, source, Blocks.AIR);
        }
    }

    private static BlockHitResult hitAt(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    /** 读一个资源（路径以 / 开头），失败时直接把断言打红 */
    private static JsonObject readJson(GameTestHelper helper, String resource) {
        try (InputStream stream = TowerdevType2Tests.class.getResourceAsStream(resource)) {
            helper.assertTrue(stream != null, resource + " must ship inside the mod resources");
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            helper.assertTrue(false, "failed to read " + resource + ": " + e);
            return new JsonObject();
        }
    }

    private TowerdevType2Tests() {}
}
