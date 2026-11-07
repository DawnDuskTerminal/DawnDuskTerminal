package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.structure.GridStructure;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModStructures {
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, DawnDuskTerminal.MOD_ID);

    public static final DeferredHolder<StructureType<?>, StructureType<?>> GRID =
            STRUCTURE_TYPES.register("grid", () -> (StructureType<GridStructure>) () -> GridStructure.CODEC);

    private ModStructures() {}

    public static void register(IEventBus bus) {
        STRUCTURE_TYPES.register(bus);
    }
}
