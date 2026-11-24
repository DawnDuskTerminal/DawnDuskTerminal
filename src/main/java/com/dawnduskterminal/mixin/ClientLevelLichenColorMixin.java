package com.dawnduskterminal.mixin;

import com.dawnduskterminal.client.CharnockiteLichenColors;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelLichenColorMixin implements CharnockiteLichenColors.WorldColor {
    @Unique
    private int ddt$lichenColor;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ddt$chooseLichenColor(
            ClientPacketListener connection,
            ClientLevel.ClientLevelData data,
            ResourceKey<Level> dimension,
            Holder<DimensionType> dimensionType,
            int viewDistance,
            int simulationDistance,
            Supplier<ProfilerFiller> profiler,
            LevelRenderer renderer,
            boolean debug,
            long seedHash,
            CallbackInfo ci) {
        this.ddt$lichenColor = CharnockiteLichenColors.fromSeedHash(seedHash);
    }

    @Override
    public int ddt$lichenColor() {
        return this.ddt$lichenColor;
    }
}
