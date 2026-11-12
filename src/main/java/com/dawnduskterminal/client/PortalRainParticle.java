package com.dawnduskterminal.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * 晨昏线维度雨天落地的水花：物理逐字照抄原版 {@link net.minecraft.client.particle.WaterDropParticle}，
 * 贴图换成转好色相的那四张水花
 *
 * <h2>为什么不是原版水花</h2>
 *
 * <p>原版 {@code WaterDropParticle} 的构造函数是包内可见的，改不了它用的贴图；而雨相关的观感要统一到
 * {@code portal_fluid} 的色相上，所以另开一种粒子，贴图由 {@code tools/recolor_rain.py} 从原版的
 * {@code splash_0..3} 复制一份再逐像素转色相（见 {@code particles/particle_portal_rain.json}）。
 * 颜色完全来自贴图，<b>没有顶点着色</b>：乘法改不了色相，往冷蓝上乘什么都到不了紫
 *
 * <h2>物理</h2>
 *
 * <p>初速、重力、寿命、落地半概率消失、撞进方块碰撞箱或液面就消失——全部与
 * {@code WaterDropParticle} 一致，这里不改任何一项：水花只该「落下来、溅一下」，不该有自己的脾气
 *
 * <p>不透明的渲染层（{@link ParticleRenderType#PARTICLE_SHEET_OPAQUE}）也与原版一致，粒子贴图边缘是透明的，
 * 着色器会把全透明像素丢掉，不会画成黑框
 */
public final class PortalRainParticle extends TextureSheetParticle {
    private PortalRainParticle(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z, 0.0D, 0.0D, 0.0D);
        this.xd *= 0.3F;
        this.yd = Math.random() * 0.2F + 0.1F;
        this.zd *= 0.3F;
        this.setSize(0.01F, 0.01F);
        this.gravity = 0.06F;
        this.lifetime = (int) (8.0D / (Math.random() * 0.8D + 0.2D));
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.lifetime-- <= 0) {
            this.remove();
            return;
        }
        this.yd -= (double) this.gravity;
        this.move(this.xd, this.yd, this.zd);
        this.xd *= 0.98F;
        this.yd *= 0.98F;
        this.zd *= 0.98F;
        if (this.onGround) {
            if (Math.random() < 0.5D) {
                this.remove();
            }
            this.xd *= 0.7F;
            this.zd *= 0.7F;
        }
        BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
        double surface = Math.max(
                this.level
                        .getBlockState(pos)
                        .getCollisionShape(this.level, pos)
                        .max(Direction.Axis.Y, this.x - (double) pos.getX(), this.z - (double) pos.getZ()),
                (double) this.level.getFluidState(pos).getHeight(this.level, pos));
        if (surface > 0.0D && this.y < (double) pos.getY() + surface) {
            this.remove();
        }
    }

    /** 贴图表与官方那套注册方式一致：{@code SpriteSet} 由 {@code particles/particle_portal_rain.json} 给出 */
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(
                SimpleParticleType type,
                ClientLevel level,
                double x,
                double y,
                double z,
                double vx,
                double vy,
                double vz) {
            PortalRainParticle particle = new PortalRainParticle(level, x, y, z);
            particle.pickSprite(this.sprites);
            return particle;
        }
    }
}
