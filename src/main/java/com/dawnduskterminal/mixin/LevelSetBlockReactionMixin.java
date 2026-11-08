package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.AntibuilderType2Field;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 反建器二型的场：方块被破坏或被放置的那一刻当场反应
 *
 * <p>{@code Level#setBlock(BlockPos, BlockState, int, int)} 是原版世界改方块唯一的漏斗：玩家挖方块走的
 * {@code ServerPlayerGameMode#removeBlock} → {@code BlockState#onDestroyedByPlayer} → {@code Level#removeBlock}、
 * 爆炸与活塞走自己的路径、{@code /setblock} 与 {@code setBlockAndUpdate} 也在这里，
 * 最后都落到这一个方法上。于是只需要在这一处判定，不必去每个入口各挂一次
 *
 * <p>判定本身在 {@link AntibuilderType2Field#react}：它拿「原来的状态」与「要写进去的状态」比一次，
 * 只在「实心方块 → 空气」与「空气 → 实心方块」这两种变化上下手，其余（包括方块自己的状态变化、
 * 水的流动与退去）一律放过
 *
 * <p>反应不是改参数而是整个接管这一格：在 HEAD 处取消原调用，换成把反应结果写进去，再把这个布尔值原样返回。
 * 直接改参数再放行是错的——原调用接着会把真正想写的空气盖到反建方块上面，等于什么也没做
 *
 * <p>重入闸是必须的：反应自己也要写方块，那次写入的「原来的状态」一定是空气、新状态是实心方块，
 * 不挡住就会被判成一次「放置」，转头又退回去，来回翻烧饼。闸开在 {@link ThreadLocal} 上，
 * 因为服务端只有一条世界刻线程，客户端这边压根不判定
 */
@Mixin(Level.class)
public abstract class LevelSetBlockReactionMixin {
    /** 反应自己正在写方块：这段时间里的 setBlock 一律按原样放行 */
    @Unique
    private static final ThreadLocal<Boolean> ddt$reacting = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void ddt$antibuilderType2Reaction(
            BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        if (ddt$reacting.get()) {
            return;
        }
        Level level = (Level) (Object) this;
        if (level.isClientSide || !AntibuilderType2Field.active(level)) {
            return;
        }
        BlockState reaction = AntibuilderType2Field.react(level, pos, level.getBlockState(pos), state);
        if (reaction == null || reaction == state) {
            return;
        }
        ddt$reacting.set(Boolean.TRUE);
        try {
            cir.setReturnValue(level.setBlock(pos, reaction, 3));
        } finally {
            ddt$reacting.set(Boolean.FALSE);
        }
    }
}
