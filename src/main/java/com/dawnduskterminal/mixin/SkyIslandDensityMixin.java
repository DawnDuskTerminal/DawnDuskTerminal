package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.ChronoTerrain;
import com.dawnduskterminal.world.SkyIslandDensity;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在 c2me 编译路由前保存种子化地形和空岛判据。 */
@Mixin(RandomState.class)
public abstract class SkyIslandDensityMixin implements SkyIslandDensity.Access, ChronoTerrain.RandomStateAccess {
    @Shadow
    private NoiseRouter router;

    @Unique
    private ChronoTerrain ddt$terrain;

    @Inject(
            method = "<init>",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/world/level/levelgen/RandomState;router:Lnet/minecraft/world/level/levelgen/NoiseRouter;",
                            opcode = org.objectweb.asm.Opcodes.PUTFIELD,
                            shift = At.Shift.AFTER))
    private void ddt$captureTerrain(
            NoiseGeneratorSettings settings,
            HolderGetter<NormalNoise.NoiseParameters> noises,
            long seed,
            CallbackInfo ci) {
        var density = this.router.finalDensity();
        if (density instanceof DensityFunctions.HolderHolder holder) {
            density = holder.function().value();
        }
        if (density instanceof ChronoTerrain terrain) {
            this.ddt$terrain = terrain;
            this.ddt$islandDensity = SkyIslandDensity.resolve((RandomState) (Object) this);
        }
    }

    @Override
    public ChronoTerrain ddt$chronoTerrain() {
        return this.ddt$terrain;
    }

    @Unique
    private volatile SkyIslandDensity ddt$islandDensity;

    @Override
    public SkyIslandDensity ddt$islandDensity() {
        SkyIslandDensity density = this.ddt$islandDensity;
        if (density == null) {
            synchronized (this) {
                density = this.ddt$islandDensity;
                if (density == null) {
                    this.ddt$islandDensity = density = SkyIslandDensity.resolve((RandomState) (Object) this);
                }
            }
        }
        return density;
    }
}
