package com.dawnduskterminal.client;

import com.dawnduskterminal.block.PortalWaterfallBlock;
import com.dawnduskterminal.block.entity.PortalWaterfallBlockEntity;
import com.dawnduskterminal.fluid.WaterfallFluid;
import com.dawnduskterminal.registry.ModParticles;
import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.ref.WeakReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 锚点只负责触发客户端尘粒，瀑布几何由区块的流体网格绘制。 */
public final class PortalWaterfallRenderer implements BlockEntityRenderer<PortalWaterfallBlockEntity> {
    private static final int PARTICLES_PER_TICK = 24;
    private static final double PARTICLE_DISTANCE_SQUARED = 32 * 32;

    private WeakReference<ClientLevel> particleLevel = new WeakReference<>(null);
    private long budgetTick = Long.MIN_VALUE;
    private int particlesLeft;

    public PortalWaterfallRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public AABB getRenderBoundingBox(PortalWaterfallBlockEntity anchor) {
        BlockPos pos = anchor.getBlockPos();
        int length = anchor.getBlockState().getValue(PortalWaterfallBlock.LENGTH);
        return new AABB(
                pos.getX(), pos.getY() + 1 - length, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    @Override
    public boolean shouldRenderOffScreen(PortalWaterfallBlockEntity anchor) {
        return true;
    }

    @Override
    public boolean shouldRender(PortalWaterfallBlockEntity anchor, Vec3 camera) {
        return getRenderBoundingBox(anchor).distanceToSqr(camera) < 64 * 64;
    }

    @Override
    public void render(
            PortalWaterfallBlockEntity anchor,
            float partialTick,
            PoseStack poses,
            MultiBufferSource buffers,
            int packedLight,
            int packedOverlay) {
        if (!(anchor.getLevel() instanceof ClientLevel level)
                || anchor.getBlockState().getValue(PortalWaterfallBlock.FLUID) != WaterfallFluid.PORTAL) {
            return;
        }
        int length = anchor.getBlockState().getValue(PortalWaterfallBlock.LENGTH);
        emitDust(anchor, level, Minecraft.getInstance(), length);
    }

    private static float dustDensity(double depth, int length) {
        double fade = Mth.clamp(length * 0.25, 4, 12);
        double t = Mth.clamp((length - depth) / fade, 0, 1);
        return (float) (t * t);
    }

    private void emitDust(PortalWaterfallBlockEntity anchor, ClientLevel level, Minecraft minecraft, int length) {
        long time = level.getGameTime();
        if (!anchor.beginParticleTick(time)) {
            return;
        }
        if (particleLevel.get() != level || budgetTick != time) {
            if (particleLevel.get() != level) {
                particleLevel = new WeakReference<>(level);
            }
            budgetTick = time;
            particlesLeft = PARTICLES_PER_TICK;
        }
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        if (particlesLeft <= 0 || getRenderBoundingBox(anchor).distanceToSqr(camera) > PARTICLE_DISTANCE_SQUARED) {
            return;
        }
        ParticleStatus setting = minecraft.options.particles().get();
        if (setting == ParticleStatus.MINIMAL) {
            return;
        }
        var random = level.getRandom();
        if (random.nextInt(setting == ParticleStatus.DECREASED ? 5 : 2) != 0) {
            return;
        }
        BlockPos pos = anchor.getBlockPos();
        double cameraDepth = pos.getY() + 1 - camera.y;
        double near = Math.max(0, cameraDepth - 32);
        double far = Math.min(length, cameraDepth + 32);
        double depth = Mth.lerp(random.nextDouble(), near, far);
        if (random.nextFloat() > dustDensity(depth, length)) {
            return;
        }
        double angle = random.nextDouble() * Mth.TWO_PI;
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        double x = pos.getX() + 0.12 + random.nextDouble() * 0.76;
        double y = pos.getY() + 1 - depth;
        double z = pos.getZ() + 0.12 + random.nextDouble() * 0.76;
        if (camera.distanceToSqr(x, y, z) > PARTICLE_DISTANCE_SQUARED) {
            return;
        }
        double drift = 0.0007 + 0.0011 * random.nextDouble();
        if (minecraft.particleEngine.createParticle(
                        ModParticles.PORTAL_WATERFALL_DUST.get(), x, y, z, dx * drift, 0, dz * drift)
                != null) {
            particlesLeft--;
        }
    }
}
