package com.dawnduskterminal.client;

import com.dawnduskterminal.registry.ModParticles;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 晨昏线维度的天气观感：雨天落地的水花换成传送门流体的色相
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>原版雨点的落地水花是 {@code ParticleTypes.RAIN}，颜色写死在贴图里。要让「雨相关的东西」统一到
 * {@code portal_fluid} 的色相上，只能换掉生成水花的那一段；而原版生成水花的地方是
 * {@code LevelRenderer#tickRain}，它第一句就问这个维度的 {@link DimensionSpecialEffects}：
 * 返回 {@code true} 表示「本维度自己处理」。于是这个类接管它，刷
 * {@link ModParticles#PORTAL_RAIN}（见 {@link PortalRainParticle}，贴图是转好色相的水花）。
 * 天空里那道竖着的雨丝由原版画，贴图在 {@code LevelRendererRainTextureMixin} 里换成
 * {@link ChronoRainTextures} 那一张
 *
 * <h2>照抄了哪一段</h2>
 *
 * <p>水花的分布、数量、声音与「岩浆或点着的篝火上不落水花」这几条逐行照抄原版
 * {@code LevelRenderer#tickRain}：采样半径 10 格、只在相机上下 10 格以内、只落在会下雨的群系上、
 * 粒子数按 {@code 100 × f²} 再按画质设置减半。唯一替换的是粒子类型本身（岩浆那一格仍撒原版烟）
 *
 * <p>继承 {@link DimensionSpecialEffects.OverworldEffects} 而不是自己实现：雾色、云高、天空类型
 * 全部照主世界，这个维度只改雨
 */
public final class ChronoDimensionEffects extends DimensionSpecialEffects.OverworldEffects {
    /** 只在客户端注册一次，见 {@code DdtClient#registerDimensionEffects} */
    public static final ChronoDimensionEffects INSTANCE = new ChronoDimensionEffects();

    /** 与 {@code LevelRenderer#rainSoundTime} 同一个意思：攒够三次候选再播一声 */
    private int rainSoundTime;

    private ChronoDimensionEffects() {}

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        float rain = level.getRainLevel(1.0F) / (Minecraft.useFancyGraphics() ? 1.0F : 2.0F);
        if (rain > 0.0F) {
            tickSplashes(level, ticks, camera, rain);
        }
        // 无论有没有雨，原版那一套都不该再跑：水花只有这一处生成
        return true;
    }

    private void tickSplashes(ClientLevel level, int ticks, Camera camera, float rain) {
        RandomSource random = RandomSource.create((long) ticks * 312987231L);
        ParticleStatus status = Minecraft.getInstance().options.particles().get();
        BlockPos center = BlockPos.containing(camera.getPosition());
        BlockPos last = null;
        int count = (int) (100.0F * rain * rain) / (status == ParticleStatus.DECREASED ? 2 : 1);
        for (int i = 0; i < count; i++) {
            int dx = random.nextInt(21) - 10;
            int dz = random.nextInt(21) - 10;
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, center.offset(dx, 0, dz));
            if (surface.getY() <= level.getMinBuildHeight()
                    || surface.getY() > center.getY() + 10
                    || surface.getY() < center.getY() - 10) {
                continue;
            }
            Biome biome = level.getBiome(surface).value();
            if (biome.getPrecipitationAt(surface) != Biome.Precipitation.RAIN) {
                continue;
            }
            last = surface.below();
            if (status == ParticleStatus.MINIMAL) {
                break;
            }
            splat(level, last, random);
        }
        if (last != null && random.nextInt(3) < this.rainSoundTime++) {
            this.rainSoundTime = 0;
            if (last.getY() > center.getY() + 1
                    && level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, center)
                                    .getY()
                            > (double) center.getY()) {
                level.playLocalSound(last, SoundEvents.WEATHER_RAIN_ABOVE, SoundSource.WEATHER, 0.1F, 0.5F, false);
            } else {
                level.playLocalSound(last, SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, 0.2F, 1.0F, false);
            }
        }
    }

    /** 一格水花：落点高度按碰撞箱与液面取高的那个，与原版一致 */
    private static void splat(ClientLevel level, BlockPos ground, RandomSource random) {
        double x = random.nextDouble();
        double z = random.nextDouble();
        BlockState state = level.getBlockState(ground);
        FluidState fluid = level.getFluidState(ground);
        VoxelShape shape = state.getCollisionShape(level, ground);
        double height = Math.max(shape.max(Direction.Axis.Y, x, z), (double) fluid.getHeight(level, ground));
        ParticleOptions particle =
                !fluid.is(FluidTags.LAVA) && !state.is(Blocks.MAGMA_BLOCK) && !CampfireBlock.isLitCampfire(state)
                        ? ModParticles.PORTAL_RAIN.get()
                        : ParticleTypes.SMOKE;
        level.addParticle(
                particle,
                (double) ground.getX() + x,
                (double) ground.getY() + height,
                (double) ground.getZ() + z,
                0.0D,
                0.0D,
                0.0D);
    }
}
