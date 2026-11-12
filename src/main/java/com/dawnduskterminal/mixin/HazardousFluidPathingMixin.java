package com.dawnduskterminal.mixin;

import com.dawnduskterminal.entity.StringEntity;
import com.dawnduskterminal.world.HazardousFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 会伤人的流体在寻路里算雷区
 *
 * <p>原版 {@code WalkNodeEvaluator#getPathTypeFromState} 只认得岩浆（{@link PathType#LAVA}），
 * 水（{@link PathType#WATER}）也不算危险——我们的两种流体既不是岩浆也不是水，于是所有生物都会
 * 大摇大摆地从 {@code portal_fluid} 里趟过去，每 2 秒掉 2 点血，直到淹死
 *
 * <p>拦截的是每次都走的那道口子 {@code getPathTypeOfMob}：{@code getCachedPathType}、
 * {@code getStartNode}、{@code canStartAt} 全部经它，于是路径里不会出现危险流体的格子。
 * 返回 {@link PathType#DAMAGE_OTHER}（代价 -1，与岩浆同档）而不是 {@link PathType#BLOCKED}：
 * BLOCKED 会把节点关死，已经泡在里面的生物再也寻不出路；DAMAGE_OTHER 只挡住「走进去」
 *
 * <p>「弦」例外：它就活在不稳定液体的液面上，往流体里寻路是它的日常，见 {@link StringEntity}
 *
 * <p>只管寻路。两只恶魂变体不走寻路（{@code FlyingMob}，直接推 {@code MoveControl}），
 * 它们靠 {@code entity/AvoidHazardousFluidGoal} 自己退开
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class HazardousFluidPathingMixin {
    /** 取格用的可变坐标，省得每个节点都新建一个 {@link BlockPos} */
    @Unique
    private BlockPos.MutableBlockPos ddt$cursor = new BlockPos.MutableBlockPos();

    @Inject(method = "getPathTypeOfMob", at = @At("HEAD"), cancellable = true)
    private void ddt$avoidHazardousFluid(
            PathfindingContext context, int x, int y, int z, Mob mob, CallbackInfoReturnable<PathType> cir) {
        if (mob instanceof StringEntity) {
            return;
        }
        if (HazardousFluid.isHazard(
                context.getBlockState(this.ddt$cursor.set(x, y, z)).getFluidState())) {
            cir.setReturnValue(PathType.DAMAGE_OTHER);
        }
    }
}
