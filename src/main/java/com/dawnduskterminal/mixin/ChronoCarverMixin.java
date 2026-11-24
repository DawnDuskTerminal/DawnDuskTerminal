package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.ChronoAquifer;
import com.dawnduskterminal.world.ChronoTerrain;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldCarver.class)
public abstract class ChronoCarverMixin {
    @Inject(method = "carveBlock", at = @At("HEAD"), cancellable = true)
    private void ddt$protectCore(
            CarvingContext context,
            CarverConfiguration config,
            ChunkAccess chunk,
            Function<BlockPos, Holder<Biome>> biomes,
            CarvingMask mask,
            BlockPos.MutableBlockPos position,
            BlockPos.MutableBlockPos below,
            Aquifer aquifer,
            MutableBoolean grass,
            CallbackInfoReturnable<Boolean> cir) {
        if ((ChronoTerrain.protectsBedrock(position.getY()) || position.getY() <= ChronoTerrain.BOTTOM_Y)
                && aquifer instanceof ChronoAquifer) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getCarveState", at = @At("HEAD"), cancellable = true)
    private void ddt$useUndergroundAquifer(
            CarvingContext context,
            CarverConfiguration config,
            BlockPos position,
            Aquifer aquifer,
            CallbackInfoReturnable<BlockState> cir) {
        if (position.getY() < 0
                && aquifer instanceof ChronoAquifer
                && position.getY() <= config.lavaLevel.resolveY(context)) {
            cir.setReturnValue(aquifer.computeSubstance(
                    new DensityFunction.SinglePointContext(position.getX(), position.getY(), position.getZ()), 0));
        }
    }
}
