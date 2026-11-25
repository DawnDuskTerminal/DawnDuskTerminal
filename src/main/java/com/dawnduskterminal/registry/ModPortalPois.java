package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModPortalPois {
    private static final DeferredRegister<PoiType> TYPES =
            DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, DawnDuskTerminal.MOD_ID);

    static {
        TYPES.register("twilight_portal", () -> of(ModBlocks.TWILIGHT_PORTAL.get()));
        TYPES.register("nether_gate", () -> of(ModBlocks.NETHER_PORTAL.get()));
    }

    private static PoiType of(Block block) {
        return new PoiType(Set.copyOf(block.getStateDefinition().getPossibleStates()), 0, 1);
    }

    public static void register(IEventBus bus) {
        TYPES.register(bus);
    }

    private ModPortalPois() {}
}
