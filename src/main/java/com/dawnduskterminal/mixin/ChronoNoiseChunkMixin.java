package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.ChronoAquifer;
import com.dawnduskterminal.world.ChronoTerrain;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseChunk.class)
public abstract class ChronoNoiseChunkMixin {
    @Shadow
    @Final
    @Mutable
    private Aquifer aquifer;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ddt$wireTerrainAquifer(
            int cells,
            RandomState randomState,
            int minX,
            int minZ,
            NoiseSettings noise,
            DensityFunctions.BeardifierOrMarker beardifier,
            NoiseGeneratorSettings settings,
            Aquifer.FluidPicker picker,
            Blender blender,
            CallbackInfo ci) {
        ChronoTerrain terrain = ((ChronoTerrain.RandomStateAccess) (Object) randomState).ddt$chronoTerrain();
        if (terrain != null) {
            this.aquifer = new ChronoAquifer(this.aquifer, terrain, settings.defaultBlock(), minX, minZ);
        }
    }
}
