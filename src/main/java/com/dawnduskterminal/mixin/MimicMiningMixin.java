package com.dawnduskterminal.mixin;

import com.dawnduskterminal.block.MimicBlock;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayerGameMode.class)
public abstract class MimicMiningMixin {
    @WrapWithCondition(
            method = "destroyBlock",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/item/ItemStack;mineBlock(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V"))
    private boolean ddt$useMimicDurability(ItemStack tool, Level level, BlockState state, BlockPos pos, Player player) {
        return !(state.getBlock() instanceof MimicBlock);
    }
}
