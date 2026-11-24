package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.ChronoTerrain;
import com.dawnduskterminal.world.PaletteBiomeSource;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkGenerator.class)
public abstract class WildVoidBiomeMixin {
    @Inject(method = "createState", at = @At("HEAD"))
    private void ddt$bindWildVoid(
            HolderLookup<StructureSet> structures,
            RandomState state,
            long seed,
            CallbackInfoReturnable<ChunkGeneratorStructureState> cir) {
        if (((ChunkGenerator) (Object) this).getBiomeSource() instanceof PaletteBiomeSource source) {
            ChronoTerrain terrain = ((ChronoTerrain.RandomStateAccess) (Object) state).ddt$chronoTerrain();
            if (terrain != null) {
                source.bindWildVoid(terrain);
            }
        }
    }
}
