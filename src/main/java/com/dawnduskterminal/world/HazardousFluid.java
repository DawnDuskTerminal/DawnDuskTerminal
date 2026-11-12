package com.dawnduskterminal.world;

import com.dawnduskterminal.registry.ModFluids;
import com.dawnduskterminal.registry.ModScorchingTwilight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * 「会伤人的流体」：不稳定液体（{@code portal_fluid}，每 2 秒 2 点 {@code stability} 伤害，
 * 见 {@code PortalBlock#entityInside}）与热情的暮光（{@code enthu_fluid}，接触即 6 点
 * {@code enthu_fluid} 伤害，见 {@code ScorchingTwilightBlock#entityInside}）
 *
 * <p>两者都是「沾上就掉血」，所以生物该把它们当成岩浆那样的危险格：寻路绕开
 * （{@code HazardousFluidPathingMixin}），没有寻路的那几个（恶魂变体）由
 * {@code AvoidHazardousFluidGoal} 自己退开
 *
 * <p>只认这两种流体本身，不认 {@code #minecraft:water} 那种「泡进去只是淹」的普通液体
 */
public final class HazardousFluid {
    /** 这一格流体是不是会伤人 */
    public static boolean isHazard(FluidState fluid) {
        Fluid type = fluid.getType();
        return type == ModFluids.PORTAL_FLUID.get()
                || type == ModFluids.PORTAL_FLOWING.get()
                || type == ModScorchingTwilight.SOURCE.get()
                || type == ModScorchingTwilight.FLOWING.get();
    }

    public static boolean isHazard(BlockGetter level, BlockPos pos) {
        return isHazard(level.getFluidState(pos));
    }

    private HazardousFluid() {}
}
