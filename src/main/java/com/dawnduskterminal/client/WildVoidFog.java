package com.dawnduskterminal.client;

import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.world.PaletteBiomeSource;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;

final class WildVoidFog {
    private WildVoidFog() {}

    private static boolean applies(Camera camera) {
        if (camera.getFluidInCamera() != FogType.NONE || camera.getEntity() == null) {
            return false;
        }
        var level = camera.getEntity().level();
        return level.dimension().equals(ModDimensions.CHRONO)
                && level.getBiome(camera.getBlockPosition()).is(PaletteBiomeSource.WILD_VOID);
    }

    static void distance(ViewportEvent.RenderFog event) {
        Camera camera = event.getCamera();
        if (applies(camera)) {
            float distance = (float) Mth.clampedLerp(8.0, 64.0, camera.getPosition().y / 96.0);
            event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), distance));
            event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), event.getFarPlaneDistance() * 0.15F));
            event.setCanceled(true);
        }
    }

    static void color(ViewportEvent.ComputeFogColor event) {
        if (applies(event.getCamera())) {
            event.setRed(0);
            event.setGreen(0);
            event.setBlue(0);
        }
    }
}
