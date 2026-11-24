package com.dawnduskterminal.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Noise")
public interface DensityNoiseAccessor {
    @Accessor("yScale")
    double ddt$yScale();
}
