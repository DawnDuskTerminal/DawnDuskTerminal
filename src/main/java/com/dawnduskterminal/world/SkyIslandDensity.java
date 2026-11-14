package com.dawnduskterminal.world;

import com.dawnduskterminal.DawnDuskTerminal;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

/** 所属 RandomState 的空岛判据：sky > 0 且 sky > base。函数对在构造完成后不可变。 */
public final class SkyIslandDensity {
    public static final ResourceLocation SKY_ISLAND = DawnDuskTerminal.id("sky_island_density");
    public static final ResourceLocation BASE_3D_NOISE = DawnDuskTerminal.id("overworld/base_3d_noise");

    private static final ThreadLocal<Cursor> CURSOR = ThreadLocal.withInitial(Cursor::new);

    private final DensityFunction sky;
    private final DensityFunction base;

    private SkyIslandDensity(DensityFunction sky, DensityFunction base) {
        this.sky = sky;
        this.base = base;
    }

    /** Mixin 为每个 RandomState 保存一份实例，不在进程级缓存世界状态。 */
    public interface Access {
        SkyIslandDensity ddt$islandDensity();
    }

    public static SkyIslandDensity of(RandomState randomState) {
        return ((Access) (Object) randomState).ddt$islandDensity();
    }

    /** 从已经完成种子替换的函数树解析。缺少任一标记时，该实例始终不可用。 */
    public static SkyIslandDensity resolve(RandomState randomState) {
        DensityFunction[] found = new DensityFunction[2];
        randomState.router().finalDensity().mapAll(node -> {
            if (node instanceof SkyIslandMarker marker) {
                if (marker.wrapped() instanceof BlendedNoise) {
                    found[1] = marker;
                } else {
                    found[0] = marker;
                }
            }
            return node;
        });
        if (found[0] == null || found[1] == null) {
            DawnDuskTerminal.LOGGER.warn(
                    "DawnDuskTerminal could not find both island density markers, sky islands will not be recognised");
            return new SkyIslandDensity(null, null);
        }
        return new SkyIslandDensity(found[0], found[1]);
    }

    public boolean ready() {
        return this.sky != null && this.base != null;
    }

    public boolean isIsland(int x, int y, int z) {
        if (!ready()) {
            return false;
        }
        Cursor cursor = CURSOR.get();
        cursor.set(x, y, z);
        double value = this.sky.compute(cursor);
        return value > 0.0D && value > this.base.compute(cursor);
    }

    /** 返回所属状态的原始空岛密度；缺少标记时返回 NaN。 */
    public double skyValue(int x, int y, int z) {
        if (!ready()) {
            return Double.NaN;
        }
        Cursor cursor = CURSOR.get();
        cursor.set(x, y, z);
        return this.sky.compute(cursor);
    }

    /** 避免逐格分配采样坐标；不持有世界或函数树。 */
    private static final class Cursor implements DensityFunction.FunctionContext {
        private int x;
        private int y;
        private int z;

        void set(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

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
}
