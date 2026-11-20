package com.dawnduskterminal.fluid;

import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.registry.ModFluids;
import com.dawnduskterminal.registry.ModScorchingTwilight;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/** 保存在瀑布锚点中的流体种类。 */
public enum WaterfallFluid implements StringRepresentable {
    PORTAL("portal"),
    SCORCHING("scorching");

    private final String name;

    WaterfallFluid(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public FlowingFluid flowing() {
        return this == PORTAL ? ModFluids.PORTAL_FLOWING.get() : ModScorchingTwilight.FLOWING.get();
    }

    public boolean stopsDetachedFlow(Level level, BlockPos pos, FluidState state) {
        return !state.isEmpty()
                && state.getValue(FlowingFluid.FALLING)
                && level instanceof ServerLevel server
                && server.dimension().equals(ModDimensions.CHRONO)
                && !PortalWaterfallSupply.isSupplied(server, pos, flowing());
    }
}
