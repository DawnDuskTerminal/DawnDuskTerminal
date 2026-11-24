package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.ChronoTerrain;
import com.dawnduskterminal.world.SkyIslandMarker;
import com.dawnduskterminal.world.WildVoidDensity;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 注册晨昏线地形使用的自定义密度函数。 */
public final class ModDensityFunctions {
    public static final DeferredRegister<MapCodec<? extends DensityFunction>> TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, DawnDuskTerminal.MOD_ID);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<? extends DensityFunction>>
            MARKER = TYPES.register("sky_island_marker", () -> SkyIslandMarker.CODEC);

    /** 每个 {@code DensityFunction} 实例都要回报一个，且必须与注册的那个是同一个对象 */
    public static final KeyDispatchDataCodec<SkyIslandMarker> SKY_ISLAND_MARKER_CODEC =
            KeyDispatchDataCodec.of(SkyIslandMarker.CODEC);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<? extends DensityFunction>>
            CHRONO_TERRAIN = TYPES.register("chrono_terrain", () -> ChronoTerrain.CODEC);
    public static final KeyDispatchDataCodec<ChronoTerrain> CHRONO_TERRAIN_CODEC =
            KeyDispatchDataCodec.of(ChronoTerrain.CODEC);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<? extends DensityFunction>>
            WILD_VOID = TYPES.register("wild_void", () -> WildVoidDensity.CODEC);
    public static final KeyDispatchDataCodec<WildVoidDensity> WILD_VOID_CODEC =
            KeyDispatchDataCodec.of(WildVoidDensity.CODEC);

    private ModDensityFunctions() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }
}
