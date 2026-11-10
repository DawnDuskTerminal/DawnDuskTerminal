package com.dawnduskterminal.gametest;

import com.dawnduskterminal.block.FairyDianthusBlock;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.FairyDianthusFeature;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 仙灵石竹向下的分支：只抽到一根分支时 5% 概率在分支点的对向挂一根
 *
 * <p>向下的一套是「拐角 + 下垂柱」：拐角是转向件的竖直镜像（柱子占 0~12 像素），
 * 下垂柱吊在上面那一格上。挖任意一格都要整列一起断，并且只掉一个仙灵埴轮。
 * 这几条都是几何与支撑关系，直接对着世界里摆出来的方块量
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class FairyDianthusDownBranchTests {
    /** 测试平台抬到离测试原点 40 格，躲开模板本身的方块 */
    private static final int LIFT = 40;

    /** 主柱立几格 */
    private static final int MAIN_HEIGHT = 7;

    /** 分支点相对主柱底格的高度：下半段里随便一格 */
    private static final int JOINT_Y = 2;

    /** 已有那根向上分支的朝向；向下的这根挂在它的对向 */
    private static final Direction BRANCH = Direction.EAST;

    /**
     * 向下分支的几何：拐角在主柱的对侧，朝回主柱接一根，下面吊 1~3 根下垂柱
     *
     * <p>同时钉住「向下的这根不长在别的格上」：拐角与主柱始终相邻（水平距离正好 1 格）
     */
    @GameTest(template = "ddt_empty")
    public static void downwardBranchHangsOnTheOppositeSide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = buildStemWithOneBranch(level, helper);
        helper.assertTrue(
                FairyDianthusFeature.placeDownward(level, RandomSource.create(7L), base, JOINT_Y, BRANCH),
                "a downward branch must fit on the opposite side of the branch point");

        BlockPos joint = base.above(JOINT_Y);
        BlockPos dropPos = joint.relative(BRANCH.getOpposite());
        BlockState jointState = level.getBlockState(joint);
        helper.assertTrue(
                jointState.getValue(FairyDianthusBlock.stub(BRANCH)), "the joint must keep the upward branch stub");
        helper.assertTrue(
                jointState.getValue(FairyDianthusBlock.stub(BRANCH.getOpposite())),
                "the joint must also gain a stub towards the downward corner");
        helper.assertTrue(
                jointState.getValue(FairyDianthusBlock.PART) == FairyDianthusBlock.Part.BRANCH,
                "a joint with two connectors stays Part.BRANCH, got " + jointState.getValue(FairyDianthusBlock.PART));

        BlockState corner = level.getBlockState(dropPos);
        helper.assertTrue(
                corner.is(ModTerrainBlocks.FAIRY_DIANTHUS.get()) && FairyDianthusBlock.isDrop(corner),
                "the opposite cell must be the downward corner, got " + corner);
        helper.assertTrue(
                corner.getValue(FairyDianthusBlock.stub(BRANCH)),
                "the downward corner must point back at the main column");

        int hanging = 0;
        while (FairyDianthusBlock.isHang(level.getBlockState(dropPos.below(hanging + 1)))) {
            hanging++;
        }
        helper.assertTrue(hanging >= 1 && hanging <= 3, "a downward branch must hang 1 to 3 blocks, got " + hanging);
        helper.assertTrue(
                level.getBlockState(dropPos.below(hanging + 1)).isAir(),
                "the hanging column must end after the blocks that fit");
        // 下垂柱只靠上面那一格
        for (int i = 1; i <= hanging; i++) {
            helper.assertTrue(
                    level.getBlockState(dropPos.below(i)).canSurvive(level, dropPos.below(i)),
                    "hanging block " + i + " must be held by the block above it");
        }
        helper.succeed();
    }

    /**
     * 下面没地方就整根都不挂：不留孤零零的拐角
     *
     * <p>「尝试生成」在这里有一个明确的落点——拐角下面紧贴着实体方块时，连拐角也不摆
     */
    @GameTest(template = "ddt_empty")
    public static void downwardBranchNeedsRoomBelow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = buildStemWithOneBranch(level, helper);
        BlockPos dropPos = base.above(JOINT_Y).relative(BRANCH.getOpposite());
        level.setBlockAndUpdate(dropPos.below(), Blocks.STONE.defaultBlockState());

        helper.assertFalse(
                FairyDianthusFeature.placeDownward(level, RandomSource.create(1L), base, JOINT_Y, BRANCH),
                "a downward branch must not fit when the cell below the corner is solid");
        helper.assertTrue(
                level.getBlockState(dropPos).isAir(), "a corner without a column must not be placed on its own");
        helper.assertFalse(
                level.getBlockState(base.above(JOINT_Y)).getValue(FairyDianthusBlock.stub(BRANCH.getOpposite())),
                "a failed downward branch must not leave a stub on the main column");
        helper.succeed();
    }

    /** 拐角没了，下面一整列跟着断 */
    @GameTest(template = "ddt_empty")
    public static void hangingColumnFollowsItsCorner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = buildStemWithOneBranch(level, helper);
        helper.assertTrue(
                FairyDianthusFeature.placeDownward(level, RandomSource.create(3L), base, JOINT_Y, BRANCH),
                "a downward branch must fit");
        BlockPos dropPos = base.above(JOINT_Y).relative(BRANCH.getOpposite());
        int hanging = hangingLength(level, dropPos);
        helper.assertTrue(hanging >= 1, "the setup must hang at least one block");

        level.removeBlock(dropPos, false);
        for (int i = 1; i <= hanging; i++) {
            helper.assertTrue(
                    level.getBlockState(dropPos.below(i)).isAir(), "hanging block " + i + " must fall with its corner");
        }
        // 主柱与向上的那根分支不受影响
        helper.assertTrue(
                level.getBlockState(base.above(JOINT_Y)).is(ModTerrainBlocks.FAIRY_DIANTHUS.get()),
                "the main column must survive the downward branch");
        helper.succeed();
    }

    /**
     * 挖任意一格：整列一起断，只掉一个仙灵埴轮
     *
     * <p>从中间挖，上面（含拐角）与下面都得断；掉落必须是<b>一件</b>，不是一格一件
     */
    @GameTest(template = "ddt_empty")
    public static void breakingAnyHangingBlockDropsExactlyOneHaniwa(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = buildStemWithOneBranch(level, helper);
        helper.assertTrue(
                FairyDianthusFeature.placeDownward(level, RandomSource.create(5L), base, JOINT_Y, BRANCH),
                "a downward branch must fit");
        BlockPos dropPos = base.above(JOINT_Y).relative(BRANCH.getOpposite());
        int hanging = hangingLength(level, dropPos);
        helper.assertTrue(hanging >= 2, "this case needs at least two hanging blocks, got " + hanging);
        BlockPos mined = dropPos.below(hanging / 2);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        mineAsPlayer(level, player, mined);

        helper.assertTrue(
                level.getBlockState(dropPos).isAir(),
                "the corner must break with the column, whatever block was mined: mined "
                        + mined + ", corner now " + level.getBlockState(dropPos) + ", first hanging now "
                        + level.getBlockState(dropPos.below(1)));
        for (int i = 1; i <= hanging; i++) {
            helper.assertTrue(
                    level.getBlockState(dropPos.below(i)).isAir(),
                    "hanging block " + i + " must break with the column");
        }

        List<ItemEntity> drops =
                level.getEntitiesOfClass(ItemEntity.class, new AABB(mined).inflate(6.0D), entity -> entity.getItem()
                        .is(ModTerrainBlocks.FAIRY_HANIWA_ITEM.get()));
        helper.assertTrue(
                drops.size() == 1, "the whole column must drop exactly one fairy haniwa, got " + drops.size());
        ItemStack stack = drops.get(0).getItem();
        helper.assertTrue(stack.getCount() == 1, "one column one haniwa, got " + stack.getCount());
        helper.succeed();
    }

    /** 5% 这个数写在特征里，配置改不了它；向下分支只抽到一根时才有 */
    @GameTest(template = "ddt_empty")
    public static void theChanceIsFivePercent(GameTestHelper helper) {
        helper.assertTrue(
                Math.abs(FairyDianthusFeature.DOWNWARD_CHANCE - 0.05F) < 1.0E-6F,
                "the downward branch chance must be 5%, got " + FairyDianthusFeature.DOWNWARD_CHANCE);
        helper.succeed();
    }

    /**
     * 创造模式手持埴轮对着主柱侧面右键：在那一侧挂一根向下的分支
     *
     * <p>几何与生成器一致：主柱那一格多一个连接，外面那格是向下拐角，再往下 1~3 格下垂柱
     */
    @GameTest(template = "ddt_empty")
    public static void creativeHaniwaCreatesTheDownwardBranch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = buildStemWithOneBranch(level, helper);
        BlockPos joint = base.above(3);
        helper.assertFalse(
                level.getBlockState(joint).getValue(FairyDianthusBlock.stub(BRANCH.getOpposite())),
                "the west side must be free before the haniwa is used");

        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModTerrainBlocks.FAIRY_HANIWA_ITEM.get()));
        InteractionResult result = useHaniwaOn(player, joint, BRANCH.getOpposite());
        helper.assertTrue(result.consumesAction(), "using the haniwa on a side must report success, got " + result);

        helper.assertTrue(
                level.getBlockState(joint).getValue(FairyDianthusBlock.stub(BRANCH.getOpposite())),
                "the joint must gain a stub towards the new side");
        BlockPos corner = joint.relative(BRANCH.getOpposite());
        helper.assertTrue(
                FairyDianthusBlock.isDrop(level.getBlockState(corner)),
                "the cell beside the joint must become a downward corner, got " + level.getBlockState(corner));
        helper.assertTrue(
                level.getBlockState(corner).getValue(FairyDianthusBlock.stub(BRANCH)),
                "the downward corner must point back at the main column");
        int hanging = hangingLength(level, corner);
        helper.assertTrue(hanging >= 1 && hanging <= 3, "the hand made branch must hang 1 to 3 blocks, got " + hanging);
        for (int i = 1; i <= hanging; i++) {
            helper.assertTrue(
                    FairyDianthusBlock.isHang(level.getBlockState(corner.below(i))),
                    "hanging block " + i + " must be a hang column");
        }
        helper.succeed();
    }

    /** 生存模式、以及点顶面：都不该放 */
    @GameTest(template = "ddt_empty")
    public static void haniwaDoesNothingOutsideCreativeOrOnTopFaces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = buildStemWithOneBranch(level, helper);
        BlockPos joint = base.above(3);

        Player survival = helper.makeMockPlayer(GameType.SURVIVAL);
        survival.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModTerrainBlocks.FAIRY_HANIWA_ITEM.get()));
        helper.assertFalse(
                useHaniwaOn(survival, joint, BRANCH.getOpposite()).consumesAction(),
                "a survival player must not place a downward branch with the haniwa");
        helper.assertFalse(
                level.getBlockState(joint).getValue(FairyDianthusBlock.stub(BRANCH.getOpposite())),
                "the survival click must not touch the main column");

        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        creative.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModTerrainBlocks.FAIRY_HANIWA_ITEM.get()));
        helper.assertFalse(
                useHaniwaOn(creative, joint, Direction.UP).consumesAction(),
                "clicking a top or bottom face must not place a downward branch");
        helper.assertFalse(
                FairyDianthusBlock.isDrop(level.getBlockState(joint.above())), "nothing may appear above the joint");
        helper.succeed();
    }

    /**
     * 手工挂的长度是掷出来的，不是固定 3 格
     *
     * <p>同一格反复「挂 → 拆 → 再挂」，统计每次挂下来的格数：必须是 1~3，而且样本里不能只有一种长度。
     * 只按固定 3 格实现的话这条会当场红。
     */
    @GameTest(template = "ddt_empty")
    public static void handPlacedLengthIsRolled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = buildStemWithOneBranch(level, helper);
        BlockPos joint = base.above(3);
        Direction side = BRANCH.getOpposite();
        BlockPos corner = joint.relative(side);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModTerrainBlocks.FAIRY_HANIWA_ITEM.get()));

        Set<Integer> lengths = new LinkedHashSet<>();
        int samples = 60;
        for (int sample = 0; sample < samples; sample++) {
            helper.assertTrue(
                    useHaniwaOn(player, joint, side).consumesAction(),
                    "the haniwa must hang a branch on a fresh side, failed at sample " + sample);
            int hanging = hangingLength(level, corner);
            helper.assertTrue(
                    hanging >= 1 && hanging <= 3, "a hand made branch must hang 1 to 3 blocks, got " + hanging);
            lengths.add(hanging);
            // 拆掉这一列并清掉主柱上的连接，回到能再挂一次的状态
            for (int block = 1; block <= hanging; block++) {
                level.removeBlock(corner.below(block), false);
            }
            level.removeBlock(corner, false);
            level.setBlockAndUpdate(joint, ModTerrainBlocks.FAIRY_DIANTHUS.get().defaultBlockState());
        }
        helper.assertTrue(
                lengths.size() >= 2,
                "the hanging length must be rolled, but every one of the " + samples + " samples was " + lengths);
        helper.succeed();
    }

    /** 照着 {@code FairyDianthusTests#useItemOn} 的写法右键一格 */
    private static InteractionResult useHaniwaOn(Player player, BlockPos clicked, Direction face) {
        UseOnContext context = new UseOnContext(
                player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(clicked), face, clicked, false));
        return player.getItemInHand(InteractionHand.MAIN_HAND).useOn(context);
    }

    /** 一根主柱 + 一根向东的上分支，分支点就在 {@link #JOINT_Y} */
    private static BlockPos buildStemWithOneBranch(ServerLevel level, GameTestHelper helper) {
        BlockPos ground = helper.absolutePos(BlockPos.ZERO).above(LIFT);
        level.setBlockAndUpdate(ground, ModTerrainBlocks.ROAD_SAND.get().defaultBlockState());
        BlockPos base = ground.above();
        Block block = ModTerrainBlocks.FAIRY_DIANTHUS.get();
        for (int i = 0; i < MAIN_HEIGHT; i++) {
            level.setBlockAndUpdate(base.above(i), block.defaultBlockState());
        }
        BlockPos joint = base.above(JOINT_Y);
        level.setBlockAndUpdate(joint, FairyDianthusBlock.withStub(level.getBlockState(joint), BRANCH));
        level.setBlockAndUpdate(joint.relative(BRANCH), FairyDianthusBlock.turnBase(block, BRANCH.getOpposite()));
        level.setBlockAndUpdate(joint.relative(BRANCH).above(), block.defaultBlockState());
        return base;
    }

    /**
     * 照 {@code ServerPlayerGameMode#destroyBlock} 的顺序挖一格：先 {@code playerWillDestroy}、
     * 再移除这一格、最后 {@code playerDestroy}
     *
     * <p>{@code Level#destroyBlock} 并不走这条顺序（它直接掉战利品表），所以不能拿它代替玩家挖掘
     */
    private static void mineAsPlayer(ServerLevel level, Player player, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        state.getBlock().playerWillDestroy(level, pos, state, player);
        level.removeBlock(pos, false);
        state.getBlock().playerDestroy(level, player, pos, state, null, ItemStack.EMPTY);
    }

    /** 拐角下面挂了几格 */
    private static int hangingLength(ServerLevel level, BlockPos dropPos) {
        int hanging = 0;
        while (FairyDianthusBlock.isHang(level.getBlockState(dropPos.below(hanging + 1)))) {
            hanging++;
        }
        return hanging;
    }

    private FairyDianthusDownBranchTests() {}
}
