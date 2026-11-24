package com.dawnduskterminal.world;

import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;

/** 在原版或 c2me 的含水层求值之前处理晨昏线的净空区域。 */
public final class ChronoAquifer implements Aquifer {
    private final Aquifer delegate;
    private final ChronoTerrain terrain;
    private final BlockState defaultBlock;
    private final WildVoidDensity voidShape;
    private final List<WildVoidDensity.Region> voidRegions;
    private final boolean cacheWetnessByColumn;
    private int[] aquiferBands;
    private boolean usedDelegate;

    public ChronoAquifer(Aquifer delegate, ChronoTerrain terrain, BlockState defaultBlock, int minX, int minZ) {
        this.delegate = delegate;
        this.terrain = terrain;
        this.defaultBlock = defaultBlock;
        this.voidShape = terrain.wildVoidShape();
        this.voidRegions = this.voidShape == null ? List.of() : this.voidShape.regionsInChunk(minX, minZ);
        this.cacheWetnessByColumn = terrain.hasColumnWetness();
    }

    @Override
    public BlockState computeSubstance(DensityFunction.FunctionContext context, double density) {
        int y = context.blockY();
        this.usedDelegate = false;
        if (y <= ChronoTerrain.BOTTOM_Y || insideVoid(context)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (ChronoTerrain.protectsBedrock(y)) {
            return this.defaultBlock;
        }
        if (density > 0) {
            return null;
        }
        if (y < 0 && !allowsUndergroundFluid(context, y)) {
            return Blocks.AIR.defaultBlockState();
        }
        this.usedDelegate = true;
        return this.delegate.computeSubstance(context, density);
    }

    private boolean allowsUndergroundFluid(DensityFunction.FunctionContext context, int y) {
        if (y < ChronoTerrain.MIN_AQUIFER_Y || y > ChronoTerrain.MAX_AQUIFER_Y) {
            return false;
        }
        int band;
        if (this.cacheWetnessByColumn) {
            if (this.aquiferBands == null) {
                this.aquiferBands = new int[256];
            }
            // 每个 NoiseChunk 的含水层至多处理一个区块范围内的水平坐标。
            int column = (context.blockZ() & 15) * 16 + (context.blockX() & 15);
            band = this.aquiferBands[column];
            if (band == 0) {
                band = this.terrain.undergroundAquiferBand(context);
                this.aquiferBands[column] = band;
            }
        } else {
            band = this.terrain.undergroundAquiferBand(context);
        }
        int height = y - ChronoTerrain.BOTTOM_Y;
        return band != -1 && height >= (band >>> 8) && height <= (band & 255);
    }

    private boolean insideVoid(DensityFunction.FunctionContext context) {
        return this.voidShape == null
                ? this.terrain.voidDensity(context) < 0
                : !this.voidRegions.isEmpty() && this.voidShape.contains(context, this.voidRegions);
    }

    @Override
    public boolean shouldScheduleFluidUpdate() {
        return this.usedDelegate && this.delegate.shouldScheduleFluidUpdate();
    }
}
