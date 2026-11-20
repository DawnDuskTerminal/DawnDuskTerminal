package com.dawnduskterminal.block.entity;

import com.dawnduskterminal.registry.ModBlockEntities;
import com.dawnduskterminal.world.PortalWaterfallColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 长度随方块状态保存，方块实体负责呈现跨格的外观。 */
public final class PortalWaterfallBlockEntity extends BlockEntity {
    private long lastParticleTick = Long.MIN_VALUE;

    public PortalWaterfallBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PORTAL_WATERFALL.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            PortalWaterfallColumns.track(server, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) {
            PortalWaterfallColumns.untrack(server, worldPosition);
        }
        super.setRemoved();
    }

    public boolean beginParticleTick(long time) {
        if (lastParticleTick == time) {
            return false;
        }
        lastParticleTick = time;
        return true;
    }
}
