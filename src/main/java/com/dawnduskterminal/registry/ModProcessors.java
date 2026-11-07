package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.structure.BandProcessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModProcessors {
    public static final DeferredRegister<StructureProcessorType<?>> TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, DawnDuskTerminal.MOD_ID);

    /** 只保留某个 y 区间的方块，见 {@link BandProcessor} */
    public static final DeferredHolder<StructureProcessorType<?>, StructureProcessorType<BandProcessor>> BAND =
            TYPES.register("band", () -> () -> BandProcessor.CODEC);

    private ModProcessors() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }
}
