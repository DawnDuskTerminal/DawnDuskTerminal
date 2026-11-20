package com.dawnduskterminal.world;

import net.minecraft.util.Mth;

/** 将距晨昏线的有符号距离叠加到原版气候温度上。 */
public final class ChronoTemperature {
    private static final double OFFSET_SCALE = 5000.0D;
    public static final float CLIMATE_LIMIT = 2.0F;

    private ChronoTemperature() {}

    public static double shift(double vanillaTemperature, int blockX, int blockZ) {
        double distance = ChronoLineState.serverLine().distance(blockX, blockZ);
        return Mth.clamp(vanillaTemperature + distance / OFFSET_SCALE, -CLIMATE_LIMIT, CLIMATE_LIMIT);
    }
}
