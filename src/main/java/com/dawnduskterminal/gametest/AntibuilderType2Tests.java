package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.block.AntibuilderType2Block;
import com.dawnduskterminal.registry.ModBlockEntities;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModCreativeTabs;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.world.AntibuilderType2Field;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFBlocks;

/**
 * 反建器二型的规格：范围 1~8（3×3×3 到 17×17×17）、只对破坏与放置两种变化当场反应、被破坏的方块换成
 * {@code dawnduskterminal:antibuildblock_type2}、反建器家族破坏时复原且放置时不动、
 * 反建器二型挨着另一台反建器二型摆不下来（切比雪夫距离 ≤ 1）、
 * 一个场不管自己的场心那一格（否则单台机器放下就拆不掉）、创造模式右键调范围；
 * 资源那一套（两个方块状态、两层模型、物品模型、贴图、没有战利品表）也在这里钉住
 *
 * <p>用例全部直接写方块：场就挂在 {@code Level#setBlock} 上，测试里 {@code setBlockAndUpdate} 走的就是
 * 玩家挖方块与放方块最后落到的那条路，不必真的去点鼠标。每条用例由 {@link #withField} 收场：
 * gametest 的世界跨轮次持久化，场不清就会一直留着，隔壁用例的方块会被这台机器静默改写
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class AntibuilderType2Tests {
    private static final String SUBJECT = "dawnduskterminal:antibuilder_type2";

    private static final String PRODUCT = "dawnduskterminal:antibuildblock_type2";

    /** 场心那一格 */
    private static final BlockPos CENTRE = new BlockPos(4, 3, 4);

    /** 场里、半径缩到最小以后仍在内的一格：中心往东一格 */
    private static final int NEAR = 1;

    /** 场里的一格：中心往东两格 */
    private static final int INSIDE = 2;

    /** 半径缩到最小以后在场外的一格：中心往东三格，3×3×3 只到 ±1 */
    private static final int BEYOND_MIN = 3;

    /** 默认半径 8 之外的一格；清扫范围也照它算，有一条用例会把机器摆到区块边界上 */
    private static final int OUTSIDE = 16;

    @GameTest(template = "ddt_empty")
    public static void blockMatchesTheTwilightAntibuilder(GameTestHelper helper) {
        Item item = ModItems.ANTIBUILDER_TYPE2.get();
        helper.assertTrue(
                item instanceof BlockItem blockItem && blockItem.getBlock() == ModBlocks.ANTIBUILDER_TYPE2.get(),
                SUBJECT + " must be the item form of its own block, got " + item);
        helper.assertTrue(
                item.getDescriptionId().equals("block.dawnduskterminal.antibuilder_type2"),
                "a block item must take its name from the block, got " + item.getDescriptionId());

        BlockState state = ModBlocks.ANTIBUILDER_TYPE2.get().defaultBlockState();
        helper.assertTrue(
                state.hasProperty(AntibuilderType2Block.RANGE),
                SUBJECT + " must carry the range property, the right click adjusts it");
        helper.assertTrue(
                state.hasProperty(AntibuilderType2Block.POWERED) && !state.getValue(AntibuilderType2Block.POWERED),
                SUBJECT + " must carry the powered property and start with the field on");
        helper.assertTrue(
                state.getLightEmission(helper.getLevel(), helper.absolutePos(CENTRE)) == 10,
                SUBJECT + " must emit the 10 light levels of the twilight antibuilder while it is on, got "
                        + state.getLightEmission(helper.getLevel(), helper.absolutePos(CENTRE)));
        helper.assertTrue(
                state.setValue(AntibuilderType2Block.POWERED, true)
                                .getLightEmission(helper.getLevel(), helper.absolutePos(CENTRE))
                        == 0,
                SUBJECT + " must go dark when a redstone signal shuts it down");
        helper.assertTrue(
                AntibuilderType2Block.MIN_RADIUS == 1
                        && AntibuilderType2Block.MAX_RADIUS == 8
                        && AntibuilderType2Block.diameter(AntibuilderType2Block.MIN_RADIUS) == 3
                        && AntibuilderType2Block.diameter(AntibuilderType2Block.MAX_RADIUS) == 17,
                "the range must span 3x3x3 to 17x17x17");
        helper.assertTrue(
                AntibuilderType2Block.radiusOf(state) == AntibuilderType2Block.MAX_RADIUS,
                SUBJECT + " must start at its widest, got " + AntibuilderType2Block.radiusOf(state));
        helper.assertTrue(
                AntibuilderType2Block.nextRadius(8, false) == 8
                        && AntibuilderType2Block.nextRadius(1, true) == 1
                        && AntibuilderType2Block.nextRadius(5, false) == 6
                        && AntibuilderType2Block.nextRadius(5, true) == 4,
                "a right click must widen by one and a sneak right click must narrow by one, both clamped");
        helper.assertTrue(
                AntibuilderType2Field.ADJACENT_DISTANCE == 1,
                "two machines must stay one cell apart at least, an adjacent placement is destroyed, got "
                        + AntibuilderType2Field.ADJACENT_DISTANCE);
        helper.assertTrue(
                state.is(BlockTags.MINEABLE_WITH_PICKAXE),
                SUBJECT + " must stay in #minecraft:mineable/pickaxe, otherwise a pickaxe cannot drop it");
        helper.assertTrue(
                BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(DawnDuskTerminal.id("antibuilder_type2")),
                "dawnduskterminal:antibuilder_type2 must be registered as a block entity type, the field needs one");

        // 反建方块二型：有物品形态（暮色森林那一块没有，这一块为了拿得到而给了），
        // 硬度与那一块同档，不进镐子标签
        Item product = ModItems.ANTIBUILD_BLOCK_TYPE2.get();
        helper.assertTrue(
                product instanceof BlockItem blockItem && blockItem.getBlock() == ModBlocks.ANTIBUILD_BLOCK_TYPE2.get(),
                PRODUCT + " must be the item form of its own block, got " + product);
        helper.assertTrue(
                product.getDescriptionId().equals("block.dawnduskterminal.antibuildblock_type2"),
                "a block item must take its name from the block, got " + product.getDescriptionId());
        helper.assertFalse(
                ModBlocks.ANTIBUILD_BLOCK_TYPE2.get().defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE),
                PRODUCT + " is a 0.3 strength block, it stays out of #minecraft:mineable/pickaxe");
        helper.succeed();
    }

    /**
     * 两个方块都在主栏目里，而且真的算得出来
     *
     * <p>内容靠 {@code buildContents} 真的算一遍，不是读字段：客户端那一栏取的就是这份结果。
     * 上一版只登记了物品、没进栏目，界面上就完全找不到这块，所以这里连栏目一起钉住
     */
    @GameTest(template = "ddt_empty")
    public static void bothBlocksAreListedInTheMainTab(GameTestHelper helper) {
        CreativeModeTab tab = ModCreativeTabs.MAIN.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                FeatureFlags.VANILLA_SET, true, helper.getLevel().registryAccess()));
        List<Item> listed =
                tab.getDisplayItems().stream().map(ItemStack::getItem).toList();
        helper.assertTrue(
                listed.contains(ModItems.ANTIBUILDER_TYPE2.get()),
                SUBJECT + " must be listed in dawnduskterminal:main, the tab would hide it completely");
        helper.assertTrue(
                listed.contains(ModItems.ANTIBUILD_BLOCK_TYPE2.get()),
                PRODUCT + " must be listed in dawnduskterminal:main, the tab would hide it completely");
        helper.succeed();
    }

    /** 场里被破坏的方块换成反建方块二型，半径缩到最小以后在场的就不动了 */
    @GameTest(template = "ddt_empty")
    public static void breakingInsideReplacesWithTheAntibuildBlock(GameTestHelper helper) {
        withField(helper, centre -> {
            ServerLevel level = helper.getLevel();
            // 场里本来就放着那两块石头：场一开，在场里就放不进东西了，所以要先摆石头后架机器
            BlockPos inside = centre.east(INSIDE);
            BlockPos adjacent = centre.east(NEAR);
            BlockPos beyond = centre.east(BEYOND_MIN);
            set(level, inside, Blocks.STONE);
            set(level, adjacent, Blocks.STONE);
            set(level, beyond, Blocks.STONE);
            placeMachine(level, centre, 8);

            breakBlock(level, inside);
            helper.assertTrue(
                    level.getBlockState(inside).is(ModBlocks.ANTIBUILD_BLOCK_TYPE2.get()),
                    "a block broken inside the field must leave " + PRODUCT + " behind, got "
                            + level.getBlockState(inside));

            // 半径缩到最小：3×3×3 只到中心那一格往每个方向各一格
            setMachineRange(level, centre, 1);
            breakBlock(level, adjacent);
            helper.assertTrue(
                    level.getBlockState(adjacent).is(ModBlocks.ANTIBUILD_BLOCK_TYPE2.get()),
                    "the smallest field is 3x3x3, one block out from the centre is still inside it, got "
                            + level.getBlockState(adjacent));

            breakBlock(level, beyond);
            helper.assertTrue(
                    level.getBlockState(beyond).isAir(),
                    "the smallest field is 3x3x3, two blocks out is already outside it, got "
                            + level.getBlockState(beyond));
            helper.succeed();
        });
    }

    /**
     * 场里被放置的方块当场退回去，但反建器家族两样都放着不管
     *
     * <p>反建器家族只有一条例外：反建器二型不许挨着另一台反建器二型摆（切比雪夫距离 ≤ 1，斜角也算），
     * 那一台照普通方块处理，当场破坏；距离 2 格以上照旧放着不管
     */
    @GameTest(template = "ddt_empty")
    public static void placingInsideIsRevertedExceptAntibuilders(GameTestHelper helper) {
        withField(helper, centre -> {
            ServerLevel level = helper.getLevel();
            // 那丛草要先摆好：场一开，场里就放不进东西了
            BlockPos grass = centre.west(2);
            set(level, grass.below(), Blocks.DIRT);
            set(level, grass, Blocks.SHORT_GRASS);
            placeMachine(level, centre, 8);

            BlockPos inside = centre.east(INSIDE);
            set(level, inside, Blocks.STONE);
            helper.assertTrue(
                    level.getBlockState(inside).isAir(),
                    "a block placed inside the field must be thrown back out, got " + level.getBlockState(inside));

            level.setBlockAndUpdate(inside, TFBlocks.ANTIBUILDER.get().defaultBlockState());
            helper.assertTrue(
                    level.getBlockState(inside).is(TFBlocks.ANTIBUILDER.get()),
                    "a twilight antibuilder placed inside the field must be left alone, got "
                            + level.getBlockState(inside));

            // 相邻：切比雪夫距离 1，正着挨、斜着挨都算，摆不下来
            BlockPos sideBySide = centre.east(1);
            level.setBlockAndUpdate(
                    sideBySide, ModBlocks.ANTIBUILDER_TYPE2.get().defaultBlockState());
            helper.assertTrue(
                    level.getBlockState(sideBySide).isAir(),
                    "an antibuilder type II placed right next to another one must be destroyed, got "
                            + level.getBlockState(sideBySide));
            BlockPos diagonal = centre.offset(-1, 1, -1);
            level.setBlockAndUpdate(diagonal, ModBlocks.ANTIBUILDER_TYPE2.get().defaultBlockState());
            helper.assertTrue(
                    level.getBlockState(diagonal).isAir(),
                    "the adjacency is Chebyshev, so a diagonal neighbour must be destroyed too, got "
                            + level.getBlockState(diagonal));
            // 暮色森林那一台不受这条管，挨着也能摆
            BlockPos twilightSide = centre.south(1);
            level.setBlockAndUpdate(twilightSide, TFBlocks.ANTIBUILDER.get().defaultBlockState());
            helper.assertTrue(
                    level.getBlockState(twilightSide).is(TFBlocks.ANTIBUILDER.get()),
                    "the adjacency rule only covers this mod's machine, a twilight antibuilder still goes down, got "
                            + level.getBlockState(twilightSide));

            // 隔开两格以上：放着不管
            BlockPos second = centre.east(4);
            level.setBlockAndUpdate(second, ModBlocks.ANTIBUILDER_TYPE2.get().defaultBlockState());
            helper.assertTrue(
                    level.getBlockState(second).is(ModBlocks.ANTIBUILDER_TYPE2.get()),
                    "another antibuilder type II placed inside the field must be left alone, got "
                            + level.getBlockState(second));

            // 反建方块二型不是反建器家族，摆进空位照样退回去
            BlockPos empty = centre.east(3);
            level.setBlockAndUpdate(empty, ModBlocks.ANTIBUILD_BLOCK_TYPE2.get().defaultBlockState());
            helper.assertTrue(
                    level.getBlockState(empty).isAir(),
                    "the antibuild block itself is not an antibuilder, it must be thrown back out, got "
                            + level.getBlockState(empty));

            // 摆进草里也算一次放置：草是可被顶掉的方块，退回去要退回草
            level.setBlockAndUpdate(grass, Blocks.STONE.defaultBlockState());
            helper.assertTrue(
                    level.getBlockState(grass).is(Blocks.SHORT_GRASS),
                    "placing a block over replaceable grass is a placement, it must be reverted to the grass, got "
                            + level.getBlockState(grass));
            helper.succeed();
        });
    }

    /** 被破坏的反建器复原；机器自己那一格不受自己的场管，所以永远拆得掉 */
    @GameTest(template = "ddt_empty")
    public static void brokenAntibuildersAreRestoredButTheMachineItselfIsNot(GameTestHelper helper) {
        withField(helper, centre -> {
            ServerLevel level = helper.getLevel();
            placeMachine(level, centre, 8);

            BlockPos twilight = centre.east(INSIDE);
            level.setBlockAndUpdate(twilight, TFBlocks.ANTIBUILDER.get().defaultBlockState());
            breakBlock(level, twilight);
            helper.assertTrue(
                    level.getBlockState(twilight).is(TFBlocks.ANTIBUILDER.get()),
                    "a broken twilight antibuilder must be put back as it was, got " + level.getBlockState(twilight));

            // 场心自己那一格不进场：拆掉它，场跟着停
            breakBlock(level, centre);
            helper.assertTrue(
                    level.getBlockState(centre).isAir(),
                    "the centre cell must not be covered by its own field, otherwise the machine can never be removed, got "
                            + level.getBlockState(centre));
            BlockPos afterStop = centre.east(NEAR);
            set(level, afterStop, Blocks.STONE);
            breakBlock(level, afterStop);
            helper.assertTrue(
                    level.getBlockState(afterStop).isAir(),
                    "breaking the machine must stop the field in the same tick, got " + level.getBlockState(afterStop));
            helper.succeed();
        });
    }

    /**
     * 范围内的另一台反建器二型被破坏时同样复原：豁免的只是「这个场自己的场心那一格」，
     * 不是「所有机器的场心」
     */
    @GameTest(template = "ddt_empty")
    public static void anotherAntibuilderType2InsideIsRestored(GameTestHelper helper) {
        withField(helper, centre -> {
            ServerLevel level = helper.getLevel();
            placeMachine(level, centre, 8);

            BlockPos other = centre.east(4);
            level.setBlockAndUpdate(other, ModBlocks.ANTIBUILDER_TYPE2.get().defaultBlockState());
            helper.assertTrue(
                    level.getBlockState(other).is(ModBlocks.ANTIBUILDER_TYPE2.get()),
                    "an antibuilder placed inside the field must be left alone, got " + level.getBlockState(other));

            breakBlock(level, other);
            helper.assertTrue(
                    level.getBlockState(other).is(ModBlocks.ANTIBUILDER_TYPE2.get()),
                    "an antibuilder type II broken inside another one's field must be put back as it was, got "
                            + level.getBlockState(other));
            helper.succeed();
        });
    }

    /** 只有「实心 ↔ 空气」这两种变化算数：水的流动与方块自己的状态变化都不碰 */
    @GameTest(template = "ddt_empty")
    public static void liquidsAndStateChangesPassThrough(GameTestHelper helper) {
        withField(helper, centre -> {
            ServerLevel level = helper.getLevel();
            // 先摆一台不会挡路的路灯，再在它旁边架场：这样场里的那一格上本来就有一块方块
            BlockPos lamp = centre.east(4);
            set(level, lamp, Blocks.REDSTONE_LAMP);
            placeMachine(level, centre, 8);

            BlockState lit = level.getBlockState(lamp).setValue(BlockStateProperties.LIT, true);
            level.setBlockAndUpdate(lamp, lit);
            helper.assertTrue(
                    level.getBlockState(lamp).is(Blocks.REDSTONE_LAMP)
                            && level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                    "a state change of the same block is neither a break nor a placement, got "
                            + level.getBlockState(lamp));

            BlockPos water = centre.east(INSIDE);
            set(level, water, Blocks.WATER);
            helper.assertTrue(
                    level.getBlockState(water).is(Blocks.WATER),
                    "water is not a placement, the field must let it flow in, got " + level.getBlockState(water));

            // 场外那一格照常放置
            BlockPos outside = centre.east(OUTSIDE);
            set(level, outside, Blocks.STONE);
            helper.assertTrue(
                    level.getBlockState(outside).is(Blocks.STONE),
                    "outside the 17x17x17 field nothing must be touched, got " + level.getBlockState(outside));
            helper.succeed();
        });
    }

    /** 创造模式右键调一格，其它模式右键不算这个方块的交互 */
    @GameTest(template = "ddt_empty")
    public static void rightClickAdjustsTheRangeInCreativeOnly(GameTestHelper helper) {
        withField(helper, centre -> {
            ServerLevel level = helper.getLevel();
            placeMachine(level, centre, 3);

            Player survival = helper.makeMockPlayer(GameType.SURVIVAL);
            InteractionResult survivalResult = stateAt(level, centre).useWithoutItem(level, survival, hitAt(centre));
            helper.assertTrue(
                    survivalResult == InteractionResult.PASS,
                    "a survival player must not be able to resize the field, got " + survivalResult);
            helper.assertTrue(
                    AntibuilderType2Block.radiusOf(stateAt(level, centre)) == 3,
                    "the range must stay where it was, got " + AntibuilderType2Block.radiusOf(stateAt(level, centre)));

            Player creative = helper.makeMockPlayer(GameType.CREATIVE);
            InteractionResult creativeResult = stateAt(level, centre).useWithoutItem(level, creative, hitAt(centre));
            helper.assertTrue(
                    creativeResult.consumesAction(),
                    "a creative player must be able to resize the field, got " + creativeResult);
            helper.assertTrue(
                    AntibuilderType2Block.radiusOf(stateAt(level, centre)) == 4,
                    "a right click must widen the field by one, got "
                            + AntibuilderType2Block.radiusOf(stateAt(level, centre)));
            helper.succeed();
        });
    }

    /**
     * 收到红石信号就关机：场不反应、方块不发光；信号一断当场恢复
     *
     * <p>红石源必须先摆好：机器一开，场里就放不进东西了，连红石块也一样。
     * 于是这里先在旁边铺一块红石块，再把机器摆上去——摆下那一刻就该知道自己该关机
     */
    @GameTest(template = "ddt_empty")
    public static void redstoneSignalShutsTheFieldDown(GameTestHelper helper) {
        withField(helper, centre -> {
            ServerLevel level = helper.getLevel();
            BlockPos source = centre.east(1);
            set(level, source, Blocks.REDSTONE_BLOCK);
            // 走真正的放置入口：状态由方块自己的 getStateForPlacement 定，和玩家拿物品摆下来一模一样
            BlockPlaceContext context = new BlockPlaceContext(
                    helper.makeMockPlayer(GameType.CREATIVE),
                    InteractionHand.MAIN_HAND,
                    new ItemStack(ModItems.ANTIBUILDER_TYPE2.get()),
                    hitAt(centre));
            level.setBlockAndUpdate(
                    centre,
                    ModBlocks.ANTIBUILDER_TYPE2
                            .get()
                            .getStateForPlacement(context)
                            .setValue(AntibuilderType2Block.RANGE, 8));

            helper.assertTrue(
                    level.getBlockState(centre).getValue(AntibuilderType2Block.POWERED),
                    "a machine placed against a live redstone source must come up powered off, got "
                            + level.getBlockState(centre));
            helper.assertTrue(
                    level.getBlockState(centre).getLightEmission(level, centre) == 0,
                    "a machine that is off must go dark");

            BlockPos inside = centre.east(2);
            set(level, inside, Blocks.STONE);
            helper.assertTrue(
                    level.getBlockState(inside).is(Blocks.STONE),
                    "a powered off field must not throw placements back out, got " + level.getBlockState(inside));
            breakBlock(level, inside);
            helper.assertTrue(
                    level.getBlockState(inside).isAir(),
                    "a powered off field must not turn broken blocks into " + PRODUCT + ", got "
                            + level.getBlockState(inside));

            // 掐掉信号：场当场恢复，不用等区块重新载入
            set(level, source, Blocks.AIR);
            helper.assertTrue(
                    !level.getBlockState(centre).getValue(AntibuilderType2Block.POWERED),
                    "cutting the redstone signal must bring the field back, got " + level.getBlockState(centre));
            set(level, inside, Blocks.STONE);
            helper.assertTrue(
                    level.getBlockState(inside).isAir(),
                    "the field must be back in service in the same tick the signal went away, got "
                            + level.getBlockState(inside));
            helper.succeed();
        });
    }

    /**
     * 机器贴着区块边界摆时，它管到隔壁区块里的那一格
     *
     * <p>场心是按区块分桶查的：能盖住某一格的机器一定在同一个或紧邻的区块里，所以查询只看 3×3 个桶。
     * 这条用例把机器摆在自己那个区块最东边一列，它东边那一格就落在下一个区块里；
     * 分桶只查一个区块，或者桶的键算错，这里就会失败
     */
    @GameTest(template = "ddt_empty")
    public static void fieldReachesAcrossTheChunkBorder(GameTestHelper helper) {
        withField(helper, centre -> {
            ServerLevel level = helper.getLevel();
            BlockPos machine = centre.east(15 - Math.floorMod(centre.getX(), 16));
            BlockPos across = machine.east(1);
            set(level, across, Blocks.STONE);
            placeMachine(level, machine, 8);
            breakBlock(level, across);
            helper.assertTrue(
                    level.getBlockState(across).is(ModBlocks.ANTIBUILD_BLOCK_TYPE2.get()),
                    "the field must reach into the neighbouring chunk, got " + level.getBlockState(across));
            helper.succeed();
        });
    }

    /**
     * 资源那一套：方块状态十六个变体（八档范围 × 开关机两种）、两层模型（第二层按满亮画）、
     * 关机那份只有底座、物品模型、贴图在 jar 里，两个方块都没有战利品表（与暮色森林那一对一致）
     *
     * <p>assets 下的模型服务端不加载，构建也不会因为路径写错而失败，所以这里直接读 classpath 上的文件本身
     */
    @GameTest(template = "ddt_empty")
    public static void assetsMatchTheBlocks(GameTestHelper helper) {
        JsonObject states = readJson(helper, "/assets/dawnduskterminal/blockstates/antibuilder_type2.json");
        JsonObject variants = states.getAsJsonObject("variants");
        helper.assertTrue(
                variants.size() == 16,
                SUBJECT + " has eight range values and two powered states, its blockstates file must carry sixteen"
                        + " variants, got " + variants.size());
        for (Map.Entry<String, JsonElement> variant : variants.entrySet()) {
            String key = variant.getKey();
            String model = variant.getValue().getAsJsonObject().get("model").getAsString();
            String expected = key.startsWith("powered=true")
                    ? "dawnduskterminal:block/antibuilder_type2_off"
                    : "dawnduskterminal:block/antibuilder_type2";
            helper.assertTrue(
                    model.equals(expected), "variant " + key + " must point at " + expected + ", got " + model);
        }

        JsonObject model = readJson(helper, "/assets/dawnduskterminal/models/block/antibuilder_type2.json");
        JsonArray elements = model.getAsJsonArray("elements");
        helper.assertTrue(
                elements != null && elements.size() == 2,
                "the machine draws a base layer plus a glow layer, got "
                        + (elements == null ? "no elements" : elements.size()));
        helper.assertTrue(
                model.get("render_type").getAsString().equals("minecraft:cutout"),
                "the glow layer is a cutout overlay, got " + model.get("render_type"));
        JsonObject glowFace =
                elements.get(1).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("up");
        helper.assertTrue(
                glowFace.getAsJsonObject("neoforge_data").get("block_light").getAsInt() == 15,
                "the glow layer must be drawn at full brightness, got " + glowFace);
        JsonObject textures = model.getAsJsonObject("textures");
        helper.assertTrue(
                textures.get("all").getAsString().equals("dawnduskterminal:block/antibuilder_type2")
                        && textures.get("all2").getAsString().equals("dawnduskterminal:block/antibuilder_type2_glow"),
                "the two layers must point at the two machine textures, got " + textures);

        JsonObject item = readJson(helper, "/assets/dawnduskterminal/models/item/antibuilder_type2.json");
        helper.assertTrue(
                item.get("parent").getAsString().equals("dawnduskterminal:block/antibuilder_type2"),
                "the item model must point at the block model, got " + item.get("parent"));
        JsonObject productItem = readJson(helper, "/assets/dawnduskterminal/models/item/antibuildblock_type2.json");
        helper.assertTrue(
                productItem.get("parent").getAsString().equals("dawnduskterminal:block/antibuildblock_type2"),
                "the antibuild block needs an item model too, got " + productItem.get("parent"));

        // 每一个面引用的贴图键都必须在这个模型的 textures 里存在，
        // 少一个键的后果是那一面画成紫黑格子（上一版的 #bottom / #top 就是这么漏的）
        assertModelTexturesResolve(helper, model, "block/antibuilder_type2");

        // 关机那一份只有底座，没有发光层
        JsonObject offModel = readJson(helper, "/assets/dawnduskterminal/models/block/antibuilder_type2_off.json");
        JsonArray offElements = offModel.getAsJsonArray("elements");
        helper.assertTrue(
                offElements != null && offElements.size() == 1,
                "the powered off model must drop the glow layer, got "
                        + (offElements == null ? "no elements" : offElements.size()));
        assertModelTexturesResolve(helper, offModel, "block/antibuilder_type2_off");

        JsonObject productStates = readJson(helper, "/assets/dawnduskterminal/blockstates/antibuildblock_type2.json");
        helper.assertTrue(
                productStates.getAsJsonObject("variants").size() == 1,
                PRODUCT + " has no state, its blockstates file must carry exactly one variant");
        JsonObject productModel = readJson(helper, "/assets/dawnduskterminal/models/block/antibuildblock_type2.json");
        assertModelTexturesResolve(helper, productModel, "block/antibuildblock_type2");
        JsonObject productGlow = productModel
                .getAsJsonArray("elements")
                .get(1)
                .getAsJsonObject()
                .getAsJsonObject("faces")
                .getAsJsonObject("up")
                .getAsJsonObject("neoforge_data");
        helper.assertTrue(
                productGlow.get("block_light").getAsInt() == 10,
                "the antibuild block glow layer is drawn at 10 light levels, like the twilight one, got "
                        + productGlow);

        for (String texture : new String[] {
            "antibuilder_type2", "antibuilder_type2_glow", "antibuildblock_type2", "antibuildblock_type2_glow"
        }) {
            String path = "/assets/dawnduskterminal/textures/block/" + texture + ".png";
            try (InputStream stream = AntibuilderType2Tests.class.getResourceAsStream(path)) {
                helper.assertTrue(stream != null, path + " must ship inside the mod resources");
            } catch (IOException e) {
                helper.assertTrue(false, "failed to read " + path + ": " + e);
            }
        }

        for (String block : new String[] {"antibuilder_type2", "antibuildblock_type2"}) {
            String path = "/data/dawnduskterminal/loot_table/blocks/" + block + ".json";
            helper.assertTrue(
                    AntibuilderType2Tests.class.getResource(path) == null,
                    path + " must not exist: both blocks are noLootTable, like the twilight pair");
        }

        // 场的范围由立方框粒子画：服务端一颗都不发，所以服务端那颗 ticker 必须是空的
        BlockState state = ModBlocks.ANTIBUILDER_TYPE2.get().defaultBlockState();
        helper.assertTrue(
                ModBlocks.ANTIBUILDER_TYPE2
                                .get()
                                .getTicker(helper.getLevel(), state, ModBlockEntities.ANTIBUILDER_TYPE2.get())
                        == null,
                "the field frame is drawn on the client only, the server must get no ticker at all");
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

    /** 摆一台反建器二型，半径给 {@code radius} */
    private static void placeMachine(ServerLevel level, BlockPos pos, int radius) {
        level.setBlockAndUpdate(
                pos,
                ModBlocks.ANTIBUILDER_TYPE2.get().defaultBlockState().setValue(AntibuilderType2Block.RANGE, radius));
    }

    /** 改一台已经摆下的机器的半径 */
    private static void setMachineRange(ServerLevel level, BlockPos pos, int radius) {
        level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(AntibuilderType2Block.RANGE, radius));
    }

    private static void set(ServerLevel level, BlockPos pos, Block block) {
        level.setBlockAndUpdate(pos, block.defaultBlockState());
    }

    /** 拆掉一格：走的就是玩家挖方块最后落到的那条 {@code setBlock} 上 */
    private static void breakBlock(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    private static BlockState stateAt(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos);
    }

    private static BlockHitResult hitAt(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    /**
     * 跑一个用例，无论断言成功还是抛错，退场时都把这一片场拆掉
     *
     * <p>场是 17×17×17，而模板只有 16×16×16：场一定会探出自己这一格，压到隔壁用例的头上。
     * gametest 的世界跨轮次持久化，方块不清就会一直留着，隔壁用例的方块会被这台机器的场静默改写
     */
    private static void withField(GameTestHelper helper, FieldBody body) {
        try {
            body.run(helper.absolutePos(CENTRE));
        } finally {
            clearFields(helper.getLevel(), helper.absolutePos(CENTRE));
        }
    }

    /**
     * 拆掉这一片：先把所有场心撤登记再拆方块
     *
     * <p>顺序不能反：两台机器摆进彼此的场里时会互相保护，先拆方块的话谁也拆不掉。
     * 撤登记之后场就不存在了，方块照普通方块拆
     *
     * <p>清扫范围是「东侧一整条」加「场心周围 3×3×3」：相邻用例会把方块摆到斜角与四邻上
     */
    private static void clearFields(ServerLevel level, BlockPos centre) {
        List<BlockPos> machines = new ArrayList<>();
        for (int offset = -OUTSIDE; offset <= OUTSIDE; offset++) {
            BlockPos pos = centre.east(offset);
            if (level.getBlockState(pos).is(ModBlocks.ANTIBUILDER_TYPE2.get())) {
                machines.add(pos);
            }
        }
        AntibuilderType2Field.untrack(level, centre);
        for (BlockPos machine : machines) {
            AntibuilderType2Field.untrack(level, machine);
        }
        for (int offset = -OUTSIDE; offset <= OUTSIDE; offset++) {
            clearAt(level, centre.east(offset));
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    clearAt(level, centre.offset(dx, dy, dz));
                }
            }
        }
        // 那丛草底下的土是自己铺的，模板地面上原本没有
        BlockPos grassSoil = centre.west(2).below();
        if (level.getBlockState(grassSoil).is(Blocks.DIRT)) {
            level.setBlockAndUpdate(grassSoil, Blocks.AIR.defaultBlockState());
        }
        AntibuilderType2Field.untrack(level, centre);
    }

    /** 这一格是测试自己摆的东西就换回空气，并把场心表里那一项也删掉 */
    private static void clearAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(ModBlocks.ANTIBUILDER_TYPE2.get())
                || state.is(ModBlocks.ANTIBUILD_BLOCK_TYPE2.get())
                || state.is(TFBlocks.ANTIBUILDER.get())
                || state.is(Blocks.REDSTONE_LAMP)
                || state.is(Blocks.REDSTONE_BLOCK)
                || state.is(Blocks.WATER)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.STONE)) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        AntibuilderType2Field.untrack(level, pos);
    }

    /** 读一个资源（路径以 / 开头），失败时直接把断言打红 */
    private static JsonObject readJson(GameTestHelper helper, String resource) {
        try (InputStream stream = AntibuilderType2Tests.class.getResourceAsStream(resource)) {
            helper.assertTrue(stream != null, resource + " must ship inside the mod resources");
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            helper.assertTrue(false, "failed to read " + resource + ": " + e);
            return new JsonObject();
        }
    }

    @FunctionalInterface
    private interface FieldBody {
        void run(BlockPos centre);
    }

    private AntibuilderType2Tests() {}
}
