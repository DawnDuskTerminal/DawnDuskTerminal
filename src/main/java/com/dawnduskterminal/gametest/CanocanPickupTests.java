package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.block.TranslucentBuiltBlock;
import twilightforest.block.VanishingBlock;
import twilightforest.init.TFBlocks;

/**
 * 苍穹罐取走「正变绿」的方块：两块暮色森林方块的绿色窗口里点一下，方块整个被拿走、玩家拿到一枚试验物品 028；
 * 窗口之外一律不动，罐子照旧走舀流体那条路
 *
 * <p>两个窗口都由方块状态决定，所以测试直接摆出那个状态，不靠等 tick：
 * 重现方块是 {@code vanished && active}（缩成小方块、15 tick 之后长回来），
 * 建造方块是 {@code active}（10 tick 之后自己消失）
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class CanocanPickupTests {
    private static final BlockPos TARGET = new BlockPos(3, 3, 3);

    private static final String REWARD = "dawnduskterminal:experiment_028";

    /** 重现方块缩成小方块、正在变绿的那 15 tick 里点一下：方块被拿走，换来一枚 028 */
    @GameTest(template = "ddt_empty")
    public static void theGreenReappearingBlockIsTaken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(TARGET);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        try {
            level.setBlockAndUpdate(
                    pos,
                    TFBlocks.REAPPEARING_BLOCK
                            .get()
                            .defaultBlockState()
                            .setValue(VanishingBlock.VANISHED, true)
                            .setValue(VanishingBlock.ACTIVE, true));
            InteractionResult result = click(level, player, pos);
            helper.assertTrue(
                    result.consumesAction(),
                    "clicking the green reappearing block with the can must consume the click, got " + result);
            helper.assertTrue(
                    level.getBlockState(pos).isAir(), "the taken block must be gone, got " + level.getBlockState(pos));
            helper.assertTrue(
                    rewardCount(player) == 1,
                    "the player must end up with exactly one " + REWARD + ", got " + rewardCount(player));
            helper.succeed();
        } finally {
            clear(level, pos);
        }
    }

    /** 砷铅铁建造器搭出来的方块正在变绿的那 10 tick 里点一下：方块被拿走，换来一枚 028 */
    @GameTest(template = "ddt_empty")
    public static void theGreenBuiltBlockIsTaken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(TARGET);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        try {
            level.setBlockAndUpdate(
                    pos, TFBlocks.BUILT_BLOCK.get().defaultBlockState().setValue(TranslucentBuiltBlock.ACTIVE, true));
            InteractionResult result = click(level, player, pos);
            helper.assertTrue(
                    result.consumesAction(),
                    "clicking the green built block with the can must consume the click, got " + result);
            helper.assertTrue(
                    level.getBlockState(pos).isAir(), "the taken block must be gone, got " + level.getBlockState(pos));
            helper.assertTrue(
                    rewardCount(player) == 1,
                    "the player must end up with exactly one " + REWARD + ", got " + rewardCount(player));
            helper.succeed();
        } finally {
            clear(level, pos);
        }
    }

    /**
     * 窗口之外什么都不动：没变绿的重现方块、还没激活的建造方块、以及随便一块石头，
     * 点下去都只是把交互往下交（罐子还要靠那条路舀流体）
     */
    @GameTest(template = "ddt_empty")
    public static void outsideTheGreenWindowNothingIsTaken(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(TARGET);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        try {
            // 重现方块：已经缩成小方块，但还没到变绿那一段
            level.setBlockAndUpdate(
                    pos,
                    TFBlocks.REAPPEARING_BLOCK
                            .get()
                            .defaultBlockState()
                            .setValue(VanishingBlock.VANISHED, true)
                            .setValue(VanishingBlock.ACTIVE, false));
            assertLeftAlone(helper, level, player, pos, "a vanished reappearing block that is not green yet");
            // 重现方块：正常那一份
            level.setBlockAndUpdate(pos, TFBlocks.REAPPEARING_BLOCK.get().defaultBlockState());
            assertLeftAlone(helper, level, player, pos, "a plain reappearing block");
            // 建造方块：还没被激活
            level.setBlockAndUpdate(pos, TFBlocks.BUILT_BLOCK.get().defaultBlockState());
            assertLeftAlone(helper, level, player, pos, "a built block that is not green");
            // 别的方块：罐子照旧走舀流体那条路
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            assertLeftAlone(helper, level, player, pos, "plain stone");
            helper.succeed();
        } finally {
            clear(level, pos);
        }
    }

    /** 奖励就是注册表里那一枚 {@code dawnduskterminal:experiment_028} */
    @GameTest(template = "ddt_empty")
    public static void theRewardIsTheRegisteredExperiment028(GameTestHelper helper) {
        Item reward = BuiltInRegistries.ITEM.get(DawnDuskTerminal.id("experiment_028"));
        helper.assertTrue(
                reward != null && reward != net.minecraft.world.item.Items.AIR,
                REWARD + " must be registered, it is what the can gives back");
        helper.assertTrue(
                reward.getDescriptionId().equals("item.dawnduskterminal.experiment_028"),
                "the reward must be the experiment 028 item itself, got " + reward.getDescriptionId());
        helper.succeed();
    }

    private static InteractionResult click(Level level, Player player, BlockPos pos) {
        ItemStack can = new ItemStack(ModItems.CANOCAN.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, can);
        return ModItems.CANOCAN
                .get()
                .useOn(new UseOnContext(
                        level,
                        player,
                        InteractionHand.MAIN_HAND,
                        can,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
    }

    private static void assertLeftAlone(
            GameTestHelper helper, ServerLevel level, Player player, BlockPos pos, String what) {
        BlockState before = level.getBlockState(pos);
        InteractionResult result = click(level, player, pos);
        helper.assertTrue(
                result == InteractionResult.PASS,
                "clicking " + what + " must fall through to the fluid pickup, got " + result);
        helper.assertTrue(
                level.getBlockState(pos).is(before.getBlock()),
                what + " must stay where it is, got " + level.getBlockState(pos));
        helper.assertTrue(rewardCount(player) == 0, "nothing may be handed out for " + what);
    }

    private static int rewardCount(Player player) {
        Item reward = BuiltInRegistries.ITEM.get(DawnDuskTerminal.id("experiment_028"));
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(reward)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void clear(ServerLevel level, BlockPos pos) {
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    private CanocanPickupTests() {}
}
