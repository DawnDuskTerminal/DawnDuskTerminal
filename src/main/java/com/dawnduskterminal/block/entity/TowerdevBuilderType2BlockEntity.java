package com.dawnduskterminal.block.entity;

import com.dawnduskterminal.block.TowerdevBuilderType2Block;
import com.dawnduskterminal.registry.ModBlockEntities;
import com.dawnduskterminal.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import twilightforest.init.TFSounds;

/**
 * 砷铅铁建造器二型的方块实体：建造进度全在这里
 *
 * <p>每 {@link #BUILD_INTERVAL} tick 往「附近那位玩家正在看的方向」多造一格，判据与暮色森林那一台一致：
 * <ul>
 *   <li>附近 = {@link #PLAYER_RADIUS} 格内最近的玩家，创造与旁观之外的都算；一个人都没有时这一 tick 什么也不做，
 *       机器不停摆，人一回来接着造
 *   <li>方向按那位玩家的俯仰角定：往上看就竖着往上造、往下看就往下造，其余按水平朝向
 *   <li>目标格必须是空气，那一格被占了就在原地等，直到玩家转向或者那一格空出来
 * </ul>
 *
 * <p>与那一台的两条差别写在 {@link TowerdevBuilderType2Block} 上：没有格数上限，也没有 timeout。
 * 于是这里没有 {@code blocksMade} 与 {@code blockedCounter}，只有一个 tick 计数与一个游标。
 *
 * <p>进度不落盘：{@code saveAdditional} 什么也不写。区块重新载入后游标从本方块那一格重新起头，
 * 与暮色森林那一台一致；也正因为不落盘，关机（{@code active} 变假）那一刻直接清空即可，不必留着等重启。
 */
public class TowerdevBuilderType2BlockEntity extends BlockEntity {
    /** 多少 tick 造一格。10 tick 是半秒，与暮色森林那一台一样 */
    public static final int BUILD_INTERVAL = 10;

    /** 多近算「附近有人」。16 格，与暮色森林那一台找玩家的半径一样 */
    public static final int PLAYER_RADIUS = 16;

    /** 一个 tick 最多跨过几格已经造出来的产物。产物不会消失，一条老线段可能很长，所以分几拍走完 */
    public static final int SCAN_PER_TICK = 64;

    /** 数到第几 tick；只在通着电时往前走 */
    private int ticks;

    /** 上一格造在哪、下一格从这里往外推；关机时清空，重新通电从本方块那一格起头 */
    @Nullable
    private BlockPos cursor;

    public TowerdevBuilderType2BlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TOWERDEV_BUILDER_TYPE2.get(), pos, state);
    }

    /**
     * 方块 ticker 走的那个入口：自己去找附近最近的玩家
     *
     * <p>关机时连玩家都不用问，直接把手上的进度清掉
     */
    public static void tick(Level level, BlockPos pos, BlockState state, TowerdevBuilderType2BlockEntity builder) {
        if (level.isClientSide) {
            return;
        }
        Player player = state.getValue(TowerdevBuilderType2Block.ACTIVE)
                ? level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, PLAYER_RADIUS, false)
                : null;
        tick(level, pos, state, builder, player);
    }

    /**
     * 拿定了一位玩家的那一份：建造的全部判定都在这里
     *
     * <p>单独摆出来是为了让测试能直接递一个玩家进来，不必真的把玩家塞进世界再等 16 格内的最近玩家查询；
     * 生产那条路只有上面那个入口，语义一个字节都没变
     */
    public static void tick(
            Level level,
            BlockPos pos,
            BlockState state,
            TowerdevBuilderType2BlockEntity builder,
            @Nullable Player player) {
        if (level.isClientSide) {
            return;
        }
        if (!state.getValue(TowerdevBuilderType2Block.ACTIVE)) {
            builder.ticks = 0;
            builder.cursor = null;
            return;
        }

        ++builder.ticks;
        if (builder.ticks % BUILD_INTERVAL != 0) {
            return;
        }

        Direction direction = facing(player);
        if (direction == null) {
            return;
        }

        BlockPos from = builder.cursor == null ? pos : builder.cursor;
        BlockPos next = from.relative(direction);
        // 产物不会消失：上一次开机造过的那一段可能正好挡在前面，于是就沿着这个方向把它走到底，
        // 从末端接着造。一格一格把游标往前推，长线也是每 tick 走一段，不会一口气扫到底
        int skipped = 0;
        while (skipped < SCAN_PER_TICK && isBuilt(level, next)) {
            builder.cursor = next;
            next = next.relative(direction);
            skipped++;
        }
        // 造到没载入的区块边上就停一停：那里 setBlock 会把区块读进来，一路造下去就是一台撞穿世界的机器
        if (!level.isLoaded(next) || !level.isEmptyBlock(next)) {
            return;
        }

        level.setBlock(next, ModBlocks.TOWERDEV_BUILT_TYPE2.get().defaultBlockState(), Block.UPDATE_ALL);
        level.playSound(null, pos, TFSounds.BUILDER_CREATE.get(), SoundSource.BLOCKS, 0.75F, 1.2F);
        builder.cursor = next;
    }

    /** 这一格是不是本模组的构建方块（两档都算：绿的那一段同样要跨过去） */
    private static boolean isBuilt(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(ModBlocks.TOWERDEV_BUILT_TYPE2.get());
    }

    /**
     * 那位玩家正在看的方向
     *
     * <p>俯仰角按 90 度一档折成四份，与暮色森林那一台的 {@code findNextFacing} 同一套算法：
     * {@code -90}（正上看）折成 0 → 朝上，{@code 90}（正下看）折成 2 → 朝下，其余取水平朝向。
     * 没有玩家时返回 {@code null}
     */
    @Nullable
    public static Direction facing(@Nullable Player player) {
        if (player == null) {
            return null;
        }
        int pitch = Mth.floor(player.getXRot() * 4.0F / 360.0F + 1.5D) & 3;
        if (pitch == 0) {
            return Direction.UP;
        }
        if (pitch == 2) {
            return Direction.DOWN;
        }
        return player.getDirection();
    }
}
