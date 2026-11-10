package com.dawnduskterminal.gametest;

import com.dawnduskterminal.block.FairyDianthusBlock;
import com.dawnduskterminal.block.FairyDianthusSmallBlock;
import com.dawnduskterminal.registry.ModFeatures;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.FairyDianthusFeature;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 仙灵石竹的硬约束：不生长、发光、连接只由「点侧面」产生、高的那一种只长在矮的那片里
 *
 * <p>两种植物的几何是内容约定（1~3 格 / 4~12 格 + 0~2 根相邻分支），代码里改一个数就会错位，
 * 所以这里对生成器摆出来的结果直接量尺寸。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class FairyDianthusTests {
    /** 测试平台抬到离测试原点 40 格，躲开模板本身的方块 */
    private static final int LIFT = 40;

    private static final List<Direction> HORIZONTAL =
            List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    /** 两种植物都发光，而且都不会生长：没有覆写 {@code randomTick}，也不接受骨粉 */
    @GameTest(template = "ddt_empty")
    public static void plantsGlowAndNeverGrow(GameTestHelper helper) {
        for (Block block :
                List.of(ModTerrainBlocks.FAIRY_DIANTHUS.get(), ModTerrainBlocks.FAIRY_DIANTHUS_SMALL.get())) {
            helper.assertTrue(
                    block.defaultBlockState().getLightEmission() == 10,
                    block.getName().getString() + " must glow at light 10, got "
                            + block.defaultBlockState().getLightEmission());
            helper.assertFalse(
                    block instanceof BonemealableBlock, block.getName().getString() + " must not accept bone meal");
            helper.assertFalse(
                    declaresRandomTick(block.getClass()),
                    block.getName().getString() + " must not override randomTick, otherwise it would grow");
        }
        // 只有高的那一种有碰撞箱
        BlockPos probe = helper.absolutePos(BlockPos.ZERO).above(LIFT);
        helper.assertFalse(
                ModTerrainBlocks.FAIRY_DIANTHUS
                        .get()
                        .defaultBlockState()
                        .getCollisionShape(helper.getLevel(), probe)
                        .isEmpty(),
                "the tall fairy dianthus must have a collision box");
        helper.assertTrue(
                ModTerrainBlocks.FAIRY_DIANTHUS_SMALL
                        .get()
                        .defaultBlockState()
                        .getCollisionShape(helper.getLevel(), probe)
                        .isEmpty(),
                "the small fairy dianthus must stay walk-through");
        helper.succeed();
    }

    /** 只长在沙类 / 土类上，或者另一根仙灵石竹上；脚下被挖掉时整根断开 */
    @GameTest(template = "ddt_empty")
    public static void plantsNeedSoilAndFollowTheirStem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(BlockPos.ZERO).above(LIFT);
        BlockState small = ModTerrainBlocks.FAIRY_DIANTHUS_SMALL.get().defaultBlockState();
        BlockState tall = ModTerrainBlocks.FAIRY_DIANTHUS.get().defaultBlockState();

        level.setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
        helper.assertFalse(small.canSurvive(level, ground.above()), "stone must not hold a fairy dianthus");

        level.setBlockAndUpdate(ground, ModTerrainBlocks.ROAD_SAND.get().defaultBlockState());
        helper.assertTrue(small.canSurvive(level, ground.above()), "road sand must hold a fairy dianthus");

        level.setBlockAndUpdate(ground.above(), small);
        helper.assertTrue(small.canSurvive(level, ground.above(2)), "a small one must stack on another small one");
        // 两种植物互不相认：矮的立在高的上、高的立在矮的上都不行
        BlockPos tallGround = ground.offset(3, 0, 0);
        level.setBlockAndUpdate(tallGround, ModTerrainBlocks.ROAD_SAND.get().defaultBlockState());
        level.setBlockAndUpdate(tallGround.above(), tall);
        helper.assertTrue(tall.canSurvive(level, tallGround.above(2)), "a tall one must stack on another tall one");
        helper.assertFalse(
                small.canSurvive(level, tallGround.above(2)), "a small fairy dianthus must not grow on a tall one");
        helper.assertFalse(
                tall.canSurvive(level, ground.above(2)), "a tall fairy dianthus must not grow on a small one");

        level.setBlockAndUpdate(ground, Blocks.AIR.defaultBlockState());
        helper.assertTrue(
                level.getBlockState(ground.above()).isAir()
                        && level.getBlockState(ground.above(2)).isAir(),
                "cutting the stem must break the plant above it");
        helper.succeed();
    }

    /** 普通放置不连接：往顶面放不会在任何一格上留下连接 */
    @GameTest(template = "ddt_empty")
    public static void placingNeverConnectsOnItsOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(BlockPos.ZERO).above(LIFT);
        level.setBlockAndUpdate(ground, ModTerrainBlocks.ROAD_SAND.get().defaultBlockState());
        BlockPos base = ground.above();
        level.setBlockAndUpdate(base, ModTerrainBlocks.FAIRY_DIANTHUS.get().defaultBlockState());

        InteractionResult result = useItemOn(helper, base, Direction.UP);
        helper.assertTrue(result.consumesAction(), "placing onto the top must succeed, got " + result);
        BlockState placedState = level.getBlockState(base.above());
        helper.assertFalse(
                placedState.getValue(FairyDianthusBlock.PART) == FairyDianthusBlock.Part.TURN,
                "a normally placed stem must be the base state, not a turn piece");
        helper.assertTrue(
                level.getBlockState(base.above()).is(ModTerrainBlocks.FAIRY_DIANTHUS.get()),
                "the clicked block must have a stem placed on top, got " + level.getBlockState(base.above()));
        for (BlockPos pos : List.of(base, base.above())) {
            BlockState state = level.getBlockState(pos);
            for (Direction direction : HORIZONTAL) {
                helper.assertFalse(
                        state.getValue(FairyDianthusBlock.stub(direction)),
                        "placing must not connect " + direction + " at " + pos + ", got " + state);
            }
        }
        helper.succeed();
    }

    /** 点在侧面才接分支：被点的那一格连出一根，相邻那一格放朝回主柱的转向件，分支只占 1 格水平距离 */
    @GameTest(template = "ddt_empty")
    public static void clickingASideFaceGrowsOneBranch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(BlockPos.ZERO).above(LIFT);
        level.setBlockAndUpdate(ground, ModTerrainBlocks.ROAD_SAND.get().defaultBlockState());
        BlockPos main = ground.above();
        level.setBlockAndUpdate(main, ModTerrainBlocks.FAIRY_DIANTHUS.get().defaultBlockState());

        InteractionResult result = useItemOn(helper, main, Direction.NORTH);
        helper.assertTrue(result.consumesAction(), "clicking a side face must connect a branch, got " + result);

        BlockState clicked = level.getBlockState(main);
        helper.assertTrue(
                clicked.getValue(FairyDianthusBlock.stub(Direction.NORTH)),
                "the clicked block must grow a stub to the north, got " + clicked);
        BlockState branch = level.getBlockState(main.north());
        helper.assertTrue(
                branch.is(ModTerrainBlocks.FAIRY_DIANTHUS.get()),
                "the branch base must be placed in the adjacent cell, got " + branch);
        helper.assertTrue(
                branch.getValue(FairyDianthusBlock.stub(Direction.SOUTH)),
                "the branch base must point back at the main column, got " + branch);
        helper.assertTrue(
                branch.getValue(FairyDianthusBlock.PART) == FairyDianthusBlock.Part.TURN,
                "the branch base must be a turn piece (the stem starts one quarter up), got " + branch);
        // 连接柱也要有碰撞箱：带连接的那一格比光秃秃的柱子更宽
        helper.assertTrue(
                branch.getCollisionShape(level, main.north()).bounds().maxZ > 0.7D
                        && clicked.getCollisionShape(level, main).bounds().minZ < 0.3D,
                "the connectors must take part in the collision box");
        helper.assertTrue(
                branch.getShape(level, main.north()).bounds().maxZ > 0.7D
                        && clicked.getShape(level, main).bounds().minZ < 0.3D,
                "the connectors must take part in the selection outline too");
        // 转向件的柱子从 4 像素开始，瞄准框不该从格底算起
        helper.assertTrue(
                branch.getShape(level, main.north()).bounds().minY >= 0.24D,
                "a turn piece must aim at the raised stem, got "
                        + branch.getShape(level, main.north()).bounds());
        // 转向件靠侧面撑着：主柱那一格被挖掉，分支柱跟着断
        level.setBlockAndUpdate(main, Blocks.AIR.defaultBlockState());
        helper.assertTrue(
                level.getBlockState(main.north()).isAir(), "a turn piece must break when its main column goes away");
        // 分支不额外水平延伸：再往外一格仍然是空的
        helper.assertTrue(
                level.getBlockState(main.north(2)).isAir(),
                "a branch must not extend further than one block, got " + level.getBlockState(main.north(2)));
        // 另外三面没有被连上
        for (Direction direction : HORIZONTAL) {
            if (direction == Direction.NORTH) {
                continue;
            }
            helper.assertFalse(
                    clicked.getValue(FairyDianthusBlock.stub(direction)),
                    "only the clicked face may connect, but " + direction + " is connected too");
        }
        helper.succeed();
    }

    /**
     * 生成器：矮的那片与高的那几根落在同一片里，主柱 4~12 格，分支与主柱相邻且顶端更低
     */
    @GameTest(template = "ddt_empty")
    public static void theFeatureGrowsTallOnesInsideTheSmallPatch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO).above(LIFT);
        int radius = 4;
        int span = radius + 1;
        // 铺一层路之沙并把上面的空间清干净；高度图读到的是这层沙的顶面
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                level.setBlockAndUpdate(
                        origin.offset(dx, -1, dz),
                        ModTerrainBlocks.ROAD_SAND.get().defaultBlockState());
                for (int dy = 0; dy <= 14; dy++) {
                    level.setBlockAndUpdate(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }

        FairyDianthusFeature feature = ModFeatures.FAIRY_DIANTHUS.get();
        FairyDianthusFeature.Configuration config = new FairyDianthusFeature.Configuration(radius, 16, 3);
        boolean placed = feature.place(new FeaturePlaceContext<>(
                Optional.empty(),
                level,
                level.getChunkSource().getGenerator(),
                RandomSource.create(0x5EED5EEDL),
                origin,
                config));
        helper.assertTrue(placed, "the feature must place something on road sand");

        List<BlockPos> smalls = new ArrayList<>();
        List<BlockPos> talls = new ArrayList<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dz = -span; dz <= span; dz++) {
                for (int dy = -1; dy <= 14; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.is(ModTerrainBlocks.FAIRY_DIANTHUS_SMALL.get())) {
                        smalls.add(pos);
                    } else if (state.is(ModTerrainBlocks.FAIRY_DIANTHUS.get())) {
                        talls.add(pos);
                    }
                }
            }
        }
        helper.assertFalse(smalls.isEmpty(), "the patch must contain the small fairy dianthus");
        // 一根柱子从头到尾共用一个偏移，否则一列上看过去每格都在晃
        for (BlockPos pos : smalls) {
            if (!level.getBlockState(pos.below()).is(ModTerrainBlocks.FAIRY_DIANTHUS_SMALL.get())) {
                continue;
            }
            helper.assertTrue(
                    level.getBlockState(pos).getValue(FairyDianthusSmallBlock.OFFSET)
                            == level.getBlockState(pos.below()).getValue(FairyDianthusSmallBlock.OFFSET),
                    "a small column must keep one offset all the way up, " + pos + " differs from the block below");
        }
        helper.assertFalse(talls.isEmpty(), "the patch must contain the tall fairy dianthus");

        // 两种植物在同一片里：矮的每一格、高的每一根主柱的底，都落在 origin ± radius 的方框之内。
        // 分支柱可以探出一格——它属于框里那根主柱，几何那一条会单独查
        for (BlockPos pos : smalls) {
            helper.assertTrue(inPatch(pos, origin, radius), "a small one must stay inside the patch, found " + pos);
        }
        List<BlockPos> mainBases = new ArrayList<>();
        for (BlockPos pos : talls) {
            // 测试平台是平的，所以主柱的底恒在地表那一层；分支柱的底在地表之上，不算主柱
            if (pos.getY() == origin.getY() && level.getBlockState(pos.below()).is(ModTerrainBlocks.ROAD_SAND.get())) {
                mainBases.add(pos);
            }
        }
        helper.assertFalse(mainBases.isEmpty(), "the patch must contain at least one main column");
        // 偏移是坐标的纯函数：同一格两次调用结果相同，不会因为再放一次而变
        for (BlockPos pos : smalls) {
            helper.assertTrue(
                    FairyDianthusSmallBlock.offsetFor(pos) == FairyDianthusSmallBlock.offsetFor(pos),
                    "the small offset must be a pure function of the position");
            helper.assertTrue(
                    level.getBlockState(pos).getValue(FairyDianthusSmallBlock.OFFSET)
                            == FairyDianthusSmallBlock.offsetFor(pos),
                    "the placed block must carry exactly the offset its position implies, " + pos);
        }
        for (BlockPos base : mainBases) {
            helper.assertTrue(
                    inPatch(base, origin, radius),
                    "the tall ones must cluster inside the small patch, found a main column base at " + base
                            + " outside radius " + radius);
        }

        for (BlockPos base : mainBases) {
            int mainHeight = columnHeight(level, base);
            helper.assertTrue(
                    mainHeight >= 4 && mainHeight <= 12,
                    "a main column must be 4 to 12 blocks tall, got " + mainHeight + " at " + base);
            int mainTop = base.getY() + mainHeight - 1;
            for (int y = 0; y < mainHeight; y++) {
                BlockPos stem = base.above(y);
                BlockState state = level.getBlockState(stem);
                for (Direction direction : HORIZONTAL) {
                    BooleanProperty stub = FairyDianthusBlock.stub(direction);
                    if (!state.getValue(stub)) {
                        continue;
                    }
                    BlockPos branchBase = stem.relative(direction);
                    // 带连接的柱子要用带分支的那份模型
                    helper.assertTrue(
                            state.getValue(FairyDianthusBlock.PART) == FairyDianthusBlock.Part.BRANCH,
                            "a stem with a connector must switch to Part.BRANCH, got "
                                    + state.getValue(FairyDianthusBlock.PART));
                    // 分支只从主柱下半段出来
                    helper.assertTrue(
                            y <= mainHeight / 2,
                            "a branch must leave the lower half of the main column, got y=" + y + " of " + mainHeight);
                    helper.assertTrue(
                            level.getBlockState(branchBase).is(ModTerrainBlocks.FAIRY_DIANTHUS.get()),
                            "a branch must be adjacent to the main column, " + branchBase + " is not");
                    // 两头的连接必须都在：主柱这一格朝外，分支柱那一格朝回主柱
                    helper.assertTrue(
                            level.getBlockState(branchBase).getValue(FairyDianthusBlock.stub(direction.getOpposite())),
                            "the branch base at " + branchBase + " must point back at the main column");
                    int branchTop = branchBase.getY() + columnHeight(level, branchBase) - 1;
                    helper.assertTrue(
                            branchTop < mainTop,
                            "a branch top must stay below the main top: branch " + branchTop + " vs main " + mainTop);
                }
            }
        }
        helper.succeed();
    }

    private static boolean inPatch(BlockPos pos, BlockPos origin, int radius) {
        return Math.abs(pos.getX() - origin.getX()) <= radius && Math.abs(pos.getZ() - origin.getZ()) <= radius;
    }

    /** 从 base 往上数这一列有多少格仙灵石竹 */
    private static int columnHeight(ServerLevel level, BlockPos base) {
        int height = 0;
        while (height < 32 && level.getBlockState(base.above(height)).is(ModTerrainBlocks.FAIRY_DIANTHUS.get())) {
            height++;
        }
        return height;
    }

    private static InteractionResult useItemOn(GameTestHelper helper, BlockPos clicked, Direction face) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModTerrainBlocks.FAIRY_DIANTHUS_ITEM.get()));
        UseOnContext context = new UseOnContext(
                player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(clicked), face, clicked, false));
        return player.getItemInHand(InteractionHand.MAIN_HAND).useOn(context);
    }

    /** 这个类（或它的父类，直到 {@code Block} 为止）有没有覆写 {@code randomTick} */
    private static boolean declaresRandomTick(Class<?> type) {
        for (Class<?> current = type; current != null && current != Block.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals("randomTick")) {
                    return true;
                }
            }
        }
        return false;
    }
}
