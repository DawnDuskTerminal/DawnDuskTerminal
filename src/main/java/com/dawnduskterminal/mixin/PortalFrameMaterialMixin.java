package com.dawnduskterminal.mixin;

import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PortalShape.class)
public abstract class PortalFrameMaterialMixin {
    @WrapOperation(
            method = {"getDistanceUntilEdgeAboveFrame", "hasTopFrame", "getDistanceUntilTop"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/block/state/BlockBehaviour$StatePredicate;test(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"),
            require = 5)
    private boolean ddt$acceptChaosStone(
            BlockBehaviour.StatePredicate predicate,
            BlockState state,
            BlockGetter level,
            BlockPos pos,
            Operation<Boolean> original) {
        return (level instanceof Level world
                        && world.dimension().equals(ModDimensions.CHRONO)
                        && state.is(ModTerrainBlocks.CHAOS_STONE.get()))
                || original.call(predicate, state, level, pos);
    }
}
