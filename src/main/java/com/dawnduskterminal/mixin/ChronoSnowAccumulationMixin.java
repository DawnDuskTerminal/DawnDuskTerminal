package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.ChronoRain;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 晨昏线维度的天气不再堆积雪层
 *
 * <h2>漏在哪</h2>
 *
 * <p>原版 {@code ServerLevel#tickPrecipitation} 每个被抽到的位置做四件事：冷群系把水面冻成冰、
 * 下雨时把雪层摞高、给炼药锅接水、扑灭火焰。前两件只看群系温度与降水类型，不看维度，
 * 于是本维度下雨时，雪原、冰刺、积雪山坡会凭空长出一层 {@code minecraft:snow}——
 * 与「这个维度的天气只有传送门流体的雨」直接冲突。
 *
 * <h2>为什么只掐这一处</h2>
 *
 * <p>只拦 {@code Biome#shouldSnow} 这一个调用：冻结成冰、炼药锅接水与灭火都保留，
 * 它们与雪层不是同一件事。地形生成时铺下的雪也不受影响——{@code SnowAndFreezeFeature} 不在这个方法里，
 * 它跑在 {@code WorldGenRegion} 上，判据见 {@link ChronoRain#snowAccumulates}
 *
 * <p>用 {@code @WrapOperation} 而不是 {@code @Redirect}：同一个 {@code Biome} 实例被别的模组也拦时，
 * 两者叠加由 MixinExtras 兼容，{@code @Redirect} 会直接冲突
 */
@Mixin(ServerLevel.class)
public abstract class ChronoSnowAccumulationMixin {
    @WrapOperation(
            method = "tickPrecipitation(Lnet/minecraft/core/BlockPos;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/biome/Biome;shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean ddt$skipSnowAccumulation(
            Biome biome, LevelReader level, BlockPos pos, Operation<Boolean> original) {
        if (level instanceof Level world && !ChronoRain.snowAccumulates(world.dimension())) {
            return false;
        }
        return original.call(biome, level, pos);
    }
}
