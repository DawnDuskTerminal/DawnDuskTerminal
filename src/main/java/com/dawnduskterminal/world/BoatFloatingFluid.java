package com.dawnduskterminal.world;

import com.dawnduskterminal.registry.ModFluids;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;

/**
 * 苍穹船浮得起来的那几种液体
 *
 * <p>原版船的漂浮判定（{@code Boat#checkInWater}、{@code Boat#getWaterLevelAbove}、
 * {@code Boat#isUnderwater}）都走 NeoForge 的 {@code IBoatExtension#canBoatInFluid}，
 * 默认实现是 {@code FluidState#supportsBoating}，而原版只有水打开了这一位。
 * {@code CanopyBoatEntity} 覆写那个钩子改成 {@link #floatsBoat}，于是水、岩浆与
 * {@code portal_fluid} 都托得住它
 *
 * <p>名单只有三种：水、岩浆、不稳定液体。热情的暮光（{@code enthu_fluid}）不在内——那是接触即 6 点伤害的
 * 流体，船浮在上面只会把船上的人一块烧掉
 */
public final class BoatFloatingFluid {
    /** 这种液体能不能托住苍穹船 */
    public static boolean floatsBoat(FluidState fluid) {
        if (fluid.is(FluidTags.WATER) || fluid.is(FluidTags.LAVA)) {
            return true;
        }
        return fluid.getType() == ModFluids.PORTAL_FLUID.get() || fluid.getType() == ModFluids.PORTAL_FLOWING.get();
    }

    private BoatFloatingFluid() {}
}
