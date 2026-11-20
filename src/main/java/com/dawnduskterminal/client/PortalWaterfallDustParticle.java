package com.dawnduskterminal.client;

import com.dawnduskterminal.world.ChronoPlantTints;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;

/** 使用原版尘粒贴图，按目标距离控制下落。 */
public final class PortalWaterfallDustParticle extends TextureSheetParticle {
    private static final ParticleRenderType DUST_SHEET = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textures) {
            return PARTICLE_SHEET_TRANSLUCENT.begin(tesselator, textures);
        }

        @Override
        public boolean isTranslucent() {
            // 普通图形模式先绘制尘粒，再将前方的半透明瀑布表面叠加到尘粒上。
            return false;
        }
    };
    private final SpriteSet sprites;

    private PortalWaterfallDustParticle(
            ClientLevel level, double x, double y, double z, double driftX, double driftZ, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        lifetime = 40 + random.nextInt(21);
        setParticleSpeed(driftX, -(16 + random.nextDouble() * 16) / lifetime, driftZ);
        quadSize = 0.20F + 0.20F * random.nextFloat();
        setSize(0.10F, 0.10F);
        int fluidColor = ChronoPlantTints.PURPLE;
        float shade = random.nextInt(256) / 255.0F;
        setColor(
                Mth.lerp(shade, FastColor.ARGB32.red(fluidColor) / 255.0F, 0.95F),
                Mth.lerp(shade, FastColor.ARGB32.green(fluidColor) / 255.0F, 0.90F),
                Mth.lerp(shade, FastColor.ARGB32.blue(fluidColor) / 255.0F, 0.99F));
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        if (age++ >= lifetime) {
            remove();
            return;
        }
        double previousY = y;
        move(xd, yd, zd);
        if (y - previousY > yd + 1.0e-5D) {
            remove();
            return;
        }
        setSpriteFromAge(sprites);
        setAlpha(Math.min(1.0F, (lifetime - age) / 10.0F));
    }

    @Override
    public ParticleRenderType getRenderType() {
        return DUST_SHEET;
    }

    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override
        public Particle createParticle(
                SimpleParticleType type,
                ClientLevel level,
                double x,
                double y,
                double z,
                double driftX,
                double driftY,
                double driftZ) {
            return new PortalWaterfallDustParticle(level, x, y, z, driftX, driftZ, sprites);
        }
    }
}
