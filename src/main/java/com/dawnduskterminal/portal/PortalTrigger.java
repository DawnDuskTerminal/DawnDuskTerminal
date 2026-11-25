package com.dawnduskterminal.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class PortalTrigger {
    private PortalTrigger() {}

    public static boolean isPortalBlock(BlockState state) {
        return PortalRoute.from(state) != null;
    }

    public static boolean isChrono(Entity entity) {
        return entity.level().dimension().equals(com.dawnduskterminal.registry.ModDimensions.CHRONO);
    }

    public static boolean isActivePortal(Entity entity, BlockPos pos) {
        BlockState state = entity.level().getBlockState(pos);
        PortalRoute route = PortalRoute.from(state);
        return route != null && route.destination(entity.level().dimension()) != null;
    }

    public static boolean isInsidePortal(Entity entity, BlockPos pos) {
        Level level = entity.level();
        return isActivePortal(entity, pos)
                && (level.getBlockState(pos).is(PortalRoute.TWILIGHT.block())
                        ? TwilightPoolPortalBlock.isFramed(level, pos)
                        : PortalShape.isCompletePortalPart(level, pos));
    }

    public static boolean shouldTeleport(Entity entity, BlockPos pos) {
        if (!isInsidePortal(entity, pos)) {
            return false;
        }
        if (entity instanceof Player player && (player.isSwimming() || player.isVisuallySwimming())) {
            return false;
        }
        return true;
    }
}
