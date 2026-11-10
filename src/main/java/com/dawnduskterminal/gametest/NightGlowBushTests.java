package com.dawnduskterminal.gametest;

import com.dawnduskterminal.block.NightGlowBushBlock;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 夜光灌木的硬约束：光照 7、不生长、只长在枯死的灌木能长的底面上
 *
 * <p>分布方式与原版枯死的灌木一致，所以底面判定直接复用 {@code BlockTags.DEAD_BUSH_MAY_PLACE_ON}：
 * 沙类能托住，石头不能。这里对这两件事各断言一次，防止以后有人把标签换成写死的方块列表。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class NightGlowBushTests {
    /** 测试平台抬到离测试原点 40 格，躲开模板本身的方块 */
    private static final int LIFT = 40;

    /** 光照 7，而且不生长：没有覆写 {@code randomTick}，也不接受骨粉 */
    @GameTest(template = "ddt_empty")
    public static void bushGlowsAndNeverGrows(GameTestHelper helper) {
        Block bush = ModTerrainBlocks.NIGHT_GLOW_BUSH.get();
        helper.assertTrue(
                bush.defaultBlockState().getLightEmission() == NightGlowBushBlock.LIGHT,
                "the night glow bush must glow at light " + NightGlowBushBlock.LIGHT + ", got "
                        + bush.defaultBlockState().getLightEmission());
        helper.assertTrue(NightGlowBushBlock.LIGHT == 7, "the light level is part of the design: 7");
        helper.assertFalse(bush instanceof BonemealableBlock, "the night glow bush must not accept bone meal");
        helper.assertFalse(
                declaresRandomTick(bush.getClass()),
                "the night glow bush must not override randomTick, otherwise it would grow");
        helper.succeed();
    }

    /** 沙类与土类能托住，石头不能，与枯死的灌木同一套判定 */
    @GameTest(template = "ddt_empty")
    public static void bushFollowsTheDeadBushSoilRules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos sand = helper.absolutePos(BlockPos.ZERO).above(LIFT);
        level.setBlockAndUpdate(sand, Blocks.SAND.defaultBlockState());
        level.setBlockAndUpdate(sand.above(), Blocks.AIR.defaultBlockState());

        BlockState bush = ModTerrainBlocks.NIGHT_GLOW_BUSH.get().defaultBlockState();
        helper.assertTrue(bush.canSurvive(level, sand.above()), "sand must hold the night glow bush");

        level.setBlockAndUpdate(sand, Blocks.STONE.defaultBlockState());
        helper.assertFalse(bush.canSurvive(level, sand.above()), "stone must not hold the night glow bush");

        // 放下去之后脚下被挖掉，灌木随之消失
        level.setBlockAndUpdate(sand, Blocks.SAND.defaultBlockState());
        level.setBlockAndUpdate(sand.above(), bush);
        level.setBlockAndUpdate(sand, Blocks.AIR.defaultBlockState());
        helper.assertTrue(level.getBlockState(sand.above()).isAir(), "removing the soil must remove the bush");
        helper.succeed();
    }

    /** 这个方块有没有覆写 randomTick（覆写了就说明它会生长） */
    private static boolean declaresRandomTick(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("randomTick") && method.getDeclaringClass() == type) {
                return true;
            }
        }
        return false;
    }
}
