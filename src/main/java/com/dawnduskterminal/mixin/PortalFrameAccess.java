package com.dawnduskterminal.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.portal.PortalShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PortalShape.class)
public interface PortalFrameAccess {
    @Accessor("bottomLeft")
    BlockPos ddt$bottomLeft();

    @Accessor("rightDir")
    Direction ddt$rightDir();

    @Accessor("width")
    int ddt$width();

    @Accessor("height")
    int ddt$height();
}
