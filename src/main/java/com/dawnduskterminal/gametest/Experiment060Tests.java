package com.dawnduskterminal.gametest;

import com.dawnduskterminal.item.Experiment060Item;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 试验物品 060 金探针的规格：256 点耐久，以及 {@link Experiment060Item#probe} 那条扫描
 *
 * <p>几条用例分别是：先无视石头以上的东西找到第一块石头类方块、再跳过一路的石头与沙土停在第一块别的东西上、
 * 一路只有石头与沙土时扫到世界底部也不报、基岩算地形所以越过它照常往上报。空气算结果这件事单列一条，
 * 因为它既是「跳过」的反例，也是洞穴里最常撞见的那一格
 *
 * <p>本模组那套地形方块单列一条：石头与沙土是按原版标签分的，本模组的六种岩石与霞土、路之沙都挂在
 * 数据包扩的那几条标签上，所以这条探针在本维度里认得出它们。这一条要是红了，说明数据包里的标签没了
 *
 * <p>扫描写成静态方法就是为了这些用例能直接摆一列方块来问它，不必真的让一个玩家去右键——
 * 右键那条路只多一句 actionbar 与一次扣耐久
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class Experiment060Tests {
    private static final String SUBJECT = "dawnduskterminal:experiment_060";

    /** 256 点耐久，且确实挂的是 {@link Experiment060Item}，不是那批无行为的普通物品 */
    @GameTest(template = "ddt_empty")
    public static void goldProbeHas256Uses(GameTestHelper helper) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(SUBJECT));
        helper.assertTrue(
                item instanceof Experiment060Item,
                SUBJECT + " must be an Experiment060Item, got "
                        + item.getClass().getSimpleName());
        helper.assertTrue(Experiment060Item.DURABILITY == 256, "sanity: the probe is specified at 256 uses");
        helper.assertTrue(
                new ItemStack(item).getMaxDamage() == 256,
                SUBJECT + " must have 256 uses, got " + new ItemStack(item).getMaxDamage());
        helper.succeed();
    }

    /**
     * 那一列从上往下是 土（石头之前的都不算数）→ 石头 → 深板岩 → 土 → 沙 → 铁矿，结果应当是铁矿那一格
     *
     * <p>深板岩、土、沙三格分别是三种「跳过」：第一种是石头类，后两种是沙土类；
     * 顶上那格土用来钉住「石头类之前的方块一律不算数」——起点是使用者正下方那一格，不是路上随手哪一格
     */
    @GameTest(template = "ddt_empty")
    public static void probeSkipsStoneAndSoil(GameTestHelper helper) {
        BlockPos origin = new BlockPos(2, 6, 2);
        helper.setBlock(2, 5, 2, Blocks.DIRT);
        helper.setBlock(2, 4, 2, Blocks.STONE);
        helper.setBlock(2, 3, 2, Blocks.DEEPSLATE);
        helper.setBlock(2, 2, 2, Blocks.DIRT);
        helper.setBlock(2, 1, 2, Blocks.SAND);
        helper.setBlock(2, 0, 2, Blocks.IRON_ORE);

        Optional<BlockPos> found = Experiment060Item.probe(helper.getLevel(), helper.absolutePos(origin));
        helper.assertTrue(
                found.isPresent(),
                SUBJECT + " must find the iron ore under stone, deepslate, dirt and sand, got nothing");
        helper.assertTrue(
                found.get().equals(helper.absolutePos(new BlockPos(2, 0, 2))),
                SUBJECT + " must report the iron ore at (2,0,2), got " + found.get());
        helper.succeed();
    }

    /** 石头下面那一格是空气时，报的就是空气：洞穴、水底这种「下面确实有东西」的情形一样算结果 */
    @GameTest(template = "ddt_empty")
    public static void airCountsAsAResult(GameTestHelper helper) {
        BlockPos origin = new BlockPos(5, 6, 5);
        helper.setBlock(5, 5, 5, Blocks.STONE);
        helper.setBlock(5, 4, 5, Blocks.AIR);

        Optional<BlockPos> found = Experiment060Item.probe(helper.getLevel(), helper.absolutePos(origin));
        helper.assertTrue(found.isPresent(), SUBJECT + " must report the air under the stone, got nothing");
        helper.assertTrue(
                found.get().equals(helper.absolutePos(new BlockPos(5, 4, 5))),
                SUBJECT + " must report the air at (5,4,5), got " + found.get());
        helper.assertTrue(
                helper.getLevel().getBlockState(found.get()).isAir(),
                SUBJECT + " must report air as a result, got "
                        + helper.getLevel().getBlockState(found.get()));
        helper.succeed();
    }

    /**
     * 一路只有石头与沙土、直到世界底部都没有别的东西时，什么都不报：扫到 {@code getMinBuildHeight()} 就收手
     *
     * <p>平坦测试世界只有基岩、泥土与草方块，结构底下那几格不一定是石头，所以这里自己往下补一列石头，
     * 一路铺到世界底部。补之前记下原样、断言完放回去，免得别的用例看见一列不该有的石头
     */
    @GameTest(template = "ddt_empty")
    public static void stoneAndSoilAllTheWayDownReportsNothing(GameTestHelper helper) {
        BlockPos origin = new BlockPos(8, 6, 8);
        for (int y = 0; y <= 5; y++) {
            helper.setBlock(8, y, 8, y % 2 == 0 ? Blocks.STONE : Blocks.DIRT);
        }

        // 结构底面往下到世界底部：直接按绝对坐标铺，helper#setBlock 收的是相对坐标
        Level level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(8, 0, 8));
        int depth = base.getY() - level.getMinBuildHeight();
        BlockState[] original = new BlockState[depth];
        for (int i = 0; i < depth; i++) {
            BlockPos pos = base.below(i + 1);
            original[i] = level.getBlockState(pos);
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        }

        Optional<BlockPos> found = Experiment060Item.probe(level, helper.absolutePos(origin));
        for (int i = 0; i < depth; i++) {
            level.setBlock(base.below(i + 1), original[i], 3);
        }
        helper.assertTrue(
                found.isEmpty(), SUBJECT + " must report nothing when stone and soil reach the bottom, got " + found);
        helper.succeed();
    }

    /**
     * 基岩算石头类，所以永远不是结果
     *
     * <p>本维度底下有一整层基岩（见 {@code noise_settings/chrono.json} 的 surface_rule），
     * 要是把它当成「石头与沙土之外的东西」，往下探就只有这一个答案了。
     *
     * <p>所以把基岩夹在中间、下面垫一块矿石：探针得越过基岩报出矿石才算对。
     * 不去动平台下方的世界——那里是不是实心岩层由测试世界决定，依赖它就是另一条用例
     * （{@link #stoneAndSoilAllTheWayDownReportsNothing}）里显式补实再还原的做法。
     */
    @GameTest(template = "ddt_empty")
    public static void bedrockCountsAsStone(GameTestHelper helper) {
        BlockPos origin = new BlockPos(11, 6, 11);
        helper.setBlock(11, 5, 11, Blocks.STONE);
        helper.setBlock(11, 4, 11, Blocks.BEDROCK);
        helper.setBlock(11, 3, 11, Blocks.STONE);
        helper.setBlock(11, 2, 11, Blocks.IRON_ORE);

        Optional<BlockPos> found = Experiment060Item.probe(helper.getLevel(), helper.absolutePos(origin));
        helper.assertTrue(
                found.isPresent(), SUBJECT + " must look past the bedrock and report the ore below it, got nothing");
        helper.assertTrue(
                found.get().equals(helper.absolutePos(new BlockPos(11, 2, 11))),
                SUBJECT + " must report the iron ore under the bedrock at (11,2,11), got " + found.get());
        helper.succeed();
    }

    /**
     * 本模组那套地形方块照原版那几条标签分：六种岩石与粉砂岩算石头、霞土霞草算土、路之沙不醒沙算沙，
     * 一路跳过去，落在这条探针真正想报的东西上——本模组的矿石
     *
     * <p>数据包把 {@code #minecraft:base_stone_overworld}、{@code #minecraft:dirt}、{@code #minecraft:sand}
     * 三条都扩过了，粉砂岩不在这几条标签里（见 {@link Experiment060Item} 的说明），所以这里问的就是
     * 「本维度里往下探能不能正常开工」，而不是原版那几种方块
     */
    @GameTest(template = "ddt_empty")
    public static void modTerrainBlocksAreSkipped(GameTestHelper helper) {
        BlockPos origin = new BlockPos(13, 9, 13);
        Block[] column = {
            ModTerrainBlocks.CANOPY_STONE.get(),
            ModTerrainBlocks.CHARNOCKITE.get(),
            ModTerrainBlocks.SILTSTONE.get(),
            ModTerrainBlocks.ROSY_GRASS.get(),
            ModTerrainBlocks.ROSY_DIRT.get(),
            ModTerrainBlocks.ROAD_SAND.get(),
            ModTerrainBlocks.UNCONSCIOUS_SAND.get(),
            ModTerrainBlocks.SCARLET_MOLYBDENUM_ORE.get()
        };
        for (int i = 0; i < column.length; i++) {
            helper.setBlock(13, 8 - i, 13, column[i]);
        }

        Optional<BlockPos> found = Experiment060Item.probe(helper.getLevel(), helper.absolutePos(origin));
        BlockPos expected = helper.absolutePos(new BlockPos(13, 1, 13));
        helper.assertTrue(found.isPresent(), SUBJECT + " must find the mod ore under the mod terrain, got nothing");
        helper.assertTrue(
                found.get().equals(expected), SUBJECT + " must report the mod ore at (13,1,13), got " + found.get());
        helper.assertTrue(
                helper.getLevel().getBlockState(found.get()).is(ModTerrainBlocks.SCARLET_MOLYBDENUM_ORE.get()),
                SUBJECT + " must report the scarlet molybdenum ore itself, got "
                        + helper.getLevel().getBlockState(found.get()));
        helper.succeed();
    }

    /** 两条 actionbar 的翻译键在两种语言里都在，缺一条玩家看到的就是裸键 */
    @GameTest(template = "ddt_empty")
    public static void actionbarLinesAreTranslated(GameTestHelper helper) {
        for (String lang : new String[] {"en_us", "zh_cn"}) {
            JsonObject words = readJson(helper, "/assets/dawnduskterminal/lang/" + lang + ".json");
            for (String key : new String[] {Experiment060Item.FOUND_KEY, Experiment060Item.NOTHING_KEY}) {
                helper.assertTrue(words.has(key), lang + " must translate " + key);
            }
        }
        helper.succeed();
    }

    /** 从 mod 资源里读一份 JSON：路径以 {@code /} 开头，相对于 jar 根 */
    private static JsonObject readJson(GameTestHelper helper, String resource) {
        try (InputStream stream = Experiment060Tests.class.getResourceAsStream(resource)) {
            helper.assertTrue(stream != null, resource + " must ship inside the mod resources");
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException problem) {
            throw new AssertionError("reading " + resource + " failed", problem);
        }
    }

    private Experiment060Tests() {}
}
