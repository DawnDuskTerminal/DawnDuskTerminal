package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.ChronoFreezeFeature;
import com.dawnduskterminal.world.FairyDianthusFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModFeatures {
    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, DawnDuskTerminal.MOD_ID);

    /** 接管 {@code minecraft:freeze_top_layer}，原版那个是在代码里放原版雪与冰 */
    public static final DeferredHolder<Feature<?>, ChronoFreezeFeature> FREEZE_TOP_LAYER =
            FEATURES.register("freeze_top_layer", ChronoFreezeFeature::new);

    /** 仙灵石竹的一簇：矮的那片先撒，高的几根在同一片里长，见 {@link FairyDianthusFeature} */
    public static final DeferredHolder<Feature<?>, FairyDianthusFeature> FAIRY_DIANTHUS =
            FEATURES.register("fairy_dianthus", FairyDianthusFeature::new);

    private ModFeatures() {}

    public static void register(IEventBus bus) {
        FEATURES.register(bus);
    }
}
