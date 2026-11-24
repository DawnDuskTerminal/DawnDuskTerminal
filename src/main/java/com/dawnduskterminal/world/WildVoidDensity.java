package com.dawnduskterminal.world;

import com.dawnduskterminal.registry.ModDensityFunctions;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

public record WildVoidDensity(NoiseHolder noise, DensityFunction edgeNoise) implements DensityFunction {
    public static final MapCodec<WildVoidDensity> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    NoiseHolder.CODEC.fieldOf("noise").forGetter(WildVoidDensity::noise),
                    DensityFunction.HOLDER_HELPER_CODEC.fieldOf("edge_noise").forGetter(WildVoidDensity::edgeNoise))
            .apply(instance, WildVoidDensity::new));

    private static final int CELL_SIZE = 1024;
    private static final double SEARCH_RADIUS = 320;
    private static final int TOP_Y = 192;
    private static final double OUTLINE_AMPLITUDE = 57;
    private static final double LEDGE_AMPLITUDE = 24;
    private static final double LEDGE_BAND = 36;
    private static final double WALL_DISPLACEMENT = OUTLINE_AMPLITUDE + LEDGE_AMPLITUDE;
    private static final ThreadLocal<Neighborhood> NEIGHBORHOOD = ThreadLocal.withInitial(Neighborhood::new);

    @Override
    public double compute(FunctionContext context) {
        int y = context.blockY();
        if (y >= TOP_Y) {
            return maxValue();
        }
        return computeAt(context, neighborhoodAt(context.blockX(), context.blockZ()).regions, false, 0);
    }

    private double computeAt(FunctionContext context, List<Region> regions, boolean signOnly, int margin) {
        int x = context.blockX();
        int y = context.blockY();
        int z = context.blockZ();
        double height = Math.max(0, Math.abs(y) - 4);
        double density = maxValue();
        for (int i = 0, size = regions.size(); i < size; i++) {
            Region region = regions.get(i);
            int dx = x - region.x;
            int dz = z - region.z;
            // 洞壁位移把 u 与 v 分别平移 0.12h 与 0.20h，所以越过下列上界必然在 outer 之外，
            // 此时 regionDensity 只会返回 maxValue，跳过与否结果相同
            double outer = region.radius - Math.max(0, y - 105) * 3.4 + WALL_DISPLACEMENT + margin;
            if (outer <= 0) {
                continue;
            }
            if (Math.abs(dx) >= outer + 0.12 * height || Math.abs(dz) >= outer + 0.20 * height) {
                continue;
            }
            density = Math.min(density, regionDensity(region, x, y, z, dx, dz, signOnly, margin));
        }
        return density;
    }

    boolean coversBiome(FunctionContext context) {
        int margin = 8;
        return context.blockY() < TOP_Y
                && computeAt(context, neighborhoodAt(context.blockX(), context.blockZ()).regions, false, margin)
                        <= margin * 0.04;
    }

    List<Region> regionsInChunk(int minX, int minZ) {
        List<Region> nearby = null;
        for (Region region : neighborhoodAt(minX, minZ).regions) {
            if (region.x + SEARCH_RADIUS >= minX
                    && region.x - SEARCH_RADIUS <= minX + 15
                    && region.z + SEARCH_RADIUS >= minZ
                    && region.z - SEARCH_RADIUS <= minZ + 15) {
                if (nearby == null) {
                    nearby = new ArrayList<>();
                }
                nearby.add(region);
            }
        }
        return nearby == null ? List.of() : nearby;
    }

    boolean contains(FunctionContext context, List<Region> regions) {
        return context.blockY() < TOP_Y && computeAt(context, regions, true, 0) < 0;
    }

    private Neighborhood neighborhoodAt(int x, int z) {
        int cellX = Math.floorDiv(x, CELL_SIZE);
        int cellZ = Math.floorDiv(z, CELL_SIZE);
        Neighborhood neighborhood = NEIGHBORHOOD.get();
        if (neighborhood.noise != this.noise.noise() || neighborhood.x != cellX || neighborhood.z != cellZ) {
            neighborhood.update(this, cellX, cellZ);
        }
        return neighborhood;
    }

    private double regionDensity(
            Region region, int x, int y, int z, double dx, double dz, boolean signOnly, int margin) {
        double height = Math.max(0, Math.abs(y) - 4);
        double u = dx - height * 0.12;
        double v = dz + height * 0.20;
        double radius = region.radius - Math.max(0, y - 105) * 3.4;
        double distanceSquared = u * u + v * v;
        double outer = radius + WALL_DISPLACEMENT + margin;
        if (outer <= 0 || distanceSquared >= outer * outer) {
            return maxValue();
        }
        double inner = radius - WALL_DISPLACEMENT;
        if (inner > 0 && distanceSquared < inner * inner) {
            return minValue();
        }
        double distance = Math.sqrt(distanceSquared);
        double angularScale = 105 / Math.max(1, distance);
        double outline = sample(region.x + u * angularScale, y * 0.30, region.z + v * angularScale);
        double wall = distance - radius - OUTLINE_AMPLITUDE * outline;
        double distanceToWall = Math.abs(wall);
        // 岩肩位移最多为 LEDGE_BAND - distanceToWall，超过半宽时无法翻转密度符号。
        if (signOnly && distanceToWall > LEDGE_BAND * 0.5) {
            return wall * 0.04;
        }
        double wallWeight = Mth.clamp((LEDGE_BAND - distanceToWall) / LEDGE_AMPLITUDE, 0, 1);
        if (wallWeight > 0) {
            EdgeContext edge = NEIGHBORHOOD.get().edge;
            edge.x = x;
            edge.y = y * 6;
            edge.z = z;
            // 地表噪声在洞壁采样中的振幅较小，位移仍受原有洞壁宽度限制。
            double detail = Mth.clamp(this.edgeNoise.compute(edge) * 2.5, -1, 1);
            wall -= LEDGE_AMPLITUDE * wallWeight * detail;
        }
        return Mth.clamp(wall * 0.04, minValue(), maxValue());
    }

    private double sample(double x, double y, double z) {
        return Mth.clamp(this.noise.getValue(x, y, z), -1, 1);
    }

    private void collect(List<Region> regions, int cellX, int cellZ) {
        long bits = Double.doubleToRawLongBits(this.noise.getValue(cellX * 117.0, 831, cellZ * 117.0));
        long hash = mix(bits ^ (cellX * 0x9E3779B97F4A7C15L) ^ (cellZ * 0xD1B54A32D192ED03L));
        // 两次独立抽样允许同格内出现零处、一处或两处。分格只用于限定查询范围。
        for (int slot = 0; slot < 2; slot++) {
            hash = mix(hash + 0x9E3779B97F4A7C15L);
            if ((hash & 0xffff) >= 3818) {
                continue;
            }
            int x = cellX * CELL_SIZE + (int) ((hash >>> 16) & 1023);
            int z = cellZ * CELL_SIZE + (int) ((hash >>> 26) & 1023);
            regions.add(new Region(x, z, 190 + (int) (mix(hash) & 31) * 0.75));
        }
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    @Override
    public void fillArray(double[] array, ContextProvider provider) {
        provider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new WildVoidDensity(visitor.visitNoise(this.noise), this.edgeNoise.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        return -1.1;
    }

    @Override
    public double maxValue() {
        return 8;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return ModDensityFunctions.WILD_VOID_CODEC;
    }

    record Region(int x, int z, double radius) {}

    private static final class EdgeContext implements FunctionContext {
        private int x;
        private int y;
        private int z;

        @Override
        public int blockX() {
            return this.x;
        }

        @Override
        public int blockY() {
            return this.y;
        }

        @Override
        public int blockZ() {
            return this.z;
        }
    }

    private static final class Neighborhood {
        private Object noise;
        private int x = Integer.MIN_VALUE;
        private int z;
        private final List<Region> regions = new ArrayList<>();
        private final EdgeContext edge = new EdgeContext();

        private void update(WildVoidDensity density, int cellX, int cellZ) {
            this.noise = density.noise.noise();
            this.x = cellX;
            this.z = cellZ;
            this.regions.clear();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    density.collect(this.regions, cellX + dx, cellZ + dz);
                }
            }
        }
    }
}
