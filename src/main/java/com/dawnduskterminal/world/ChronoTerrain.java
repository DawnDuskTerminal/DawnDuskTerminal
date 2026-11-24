package com.dawnduskterminal.world;

import com.dawnduskterminal.mixin.DensityNoiseAccessor;
import com.dawnduskterminal.registry.ModDensityFunctions;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/** 组合地表密度、地下浮岛与零高度的基岩隔层。 */
public record ChronoTerrain(
        DensityFunction shaped,
        DensityFunction islands,
        DensityFunction strata,
        DensityFunction wetness,
        DensityFunction wildVoid)
        implements DensityFunction {
    public static final MapCodec<ChronoTerrain> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("shaped").forGetter(ChronoTerrain::shaped),
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("islands").forGetter(ChronoTerrain::islands),
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("strata").forGetter(ChronoTerrain::strata),
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("wetness").forGetter(ChronoTerrain::wetness),
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("wild_void").forGetter(ChronoTerrain::wildVoid))
            .apply(instance, ChronoTerrain::new));

    public static final int BOTTOM_Y = -64;
    private static final int AQUIFER_CENTER_Y = -27;
    private static final int AQUIFER_CENTER_VARIATION = 5;
    private static final int AQUIFER_MAX_HALF_THICKNESS = 5;
    static final int MIN_AQUIFER_Y = AQUIFER_CENTER_Y - AQUIFER_CENTER_VARIATION - AQUIFER_MAX_HALF_THICKNESS;
    static final int MAX_AQUIFER_Y = AQUIFER_CENTER_Y + AQUIFER_CENTER_VARIATION + AQUIFER_MAX_HALF_THICKNESS;
    private static final double TOP_OFFSET = 0.86;
    private static final double BOTTOM_OFFSET = 0.68;
    private static final double[] ISLAND_OFFSETS = islandOffsets();

    public interface RandomStateAccess {
        ChronoTerrain ddt$chronoTerrain();
    }

    public static boolean protectsBedrock(int y) {
        return y == 0;
    }

    @Override
    public double compute(FunctionContext context) {
        int y = context.blockY();
        if (y <= BOTTOM_Y) {
            return -1.0;
        }
        if (protectsBedrock(y)) {
            return 1.0;
        }
        if (y < 0) {
            double offset = ISLAND_OFFSETS[y - BOTTOM_Y];
            double island = this.islands.compute(context) + offset;
            return island <= 0 ? island : Math.min(island, this.strata.compute(context) + offset);
        }
        return this.shaped.compute(context);
    }

    private static double[] islandOffsets() {
        double[] offsets = new double[-BOTTOM_Y];
        for (int y = BOTTOM_Y; y < 0; y++) {
            offsets[y - BOTTOM_Y] =
                    TOP_OFFSET * smoothRamp(y, -16, -4) - BOTTOM_OFFSET * (1 - smoothRamp(y, BOTTOM_Y, -51));
        }
        return offsets;
    }

    private static double smoothRamp(int y, int from, int to) {
        double t = Mth.clamp((double) (y - from) / (to - from), 0, 1);
        return t * t * (3 - 2 * t);
    }

    boolean hasColumnWetness() {
        DensityFunction density = unwrap(this.wetness);
        return density instanceof DensityNoiseAccessor noise && noise.ddt$yScale() == 0.0;
    }

    /** 湿度决定含水带的厚度，并使其中心随水平方向缓慢起伏。 */
    int undergroundAquiferBand(FunctionContext context) {
        double wetnessValue = this.wetness.compute(context);
        double strength = Mth.clamp(wetnessValue / 0.25, 0.0, 1.0);
        if (strength == 0.0) {
            return -1;
        }
        double center =
                AQUIFER_CENTER_Y + Mth.clamp(wetnessValue * 12.0, -AQUIFER_CENTER_VARIATION, AQUIFER_CENTER_VARIATION);
        double halfThickness = AQUIFER_MAX_HALF_THICKNESS * Math.sqrt(strength);
        int bottom = (int) Math.ceil(center - halfThickness);
        int top = (int) Math.floor(center + halfThickness);
        // 两个字节分别存储上下界相对维度底层的高度。零保留为缓存未初始化标记。
        return ((bottom - BOTTOM_Y) << 8) | (top - BOTTOM_Y);
    }

    WildVoidDensity wildVoidShape() {
        DensityFunction density = unwrap(this.wildVoid);
        return density instanceof WildVoidDensity shape ? shape : null;
    }

    private static DensityFunction unwrap(DensityFunction density) {
        return density instanceof DensityFunctions.HolderHolder holder
                ? holder.function().value()
                : density;
    }

    public double voidDensity(FunctionContext context) {
        return this.wildVoid.compute(context);
    }

    @Override
    public void fillArray(double[] array, ContextProvider provider) {
        provider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new ChronoTerrain(
                this.shaped.mapAll(visitor),
                this.islands.mapAll(visitor),
                this.strata.mapAll(visitor),
                this.wetness.mapAll(visitor),
                this.wildVoid.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        double underground = Math.min(this.islands.minValue(), this.strata.minValue()) - BOTTOM_OFFSET;
        return Math.min(-1, Math.min(this.shaped.minValue(), underground));
    }

    @Override
    public double maxValue() {
        return Math.max(1, Math.max(this.shaped.maxValue(), this.islands.maxValue() + TOP_OFFSET));
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return ModDensityFunctions.CHRONO_TERRAIN_CODEC;
    }
}
