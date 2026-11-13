package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.CompoundErosionEffect;
import com.dawnduskterminal.world.PortalSicknessEffect;
import com.dawnduskterminal.world.StabilityEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, DawnDuskTerminal.MOD_ID);

    public static final DeferredHolder<MobEffect, MobEffect> PORTAL_SICKNESS =
            EFFECTS.register("portal_sickness", PortalSicknessEffect::new);

    public static final DeferredHolder<MobEffect, MobEffect> STABILITY =
            EFFECTS.register("stability", StabilityEffect::new);

    /** 复方侵蚀：每 2 秒 3 点传送门流体的接触伤害，颜色与稳定一致 */
    public static final DeferredHolder<MobEffect, MobEffect> COMPOUND_EROSION =
            EFFECTS.register("compound_erosion", CompoundErosionEffect::new);

    private ModEffects() {}

    public static void register(IEventBus bus) {
        EFFECTS.register(bus);
    }
}
