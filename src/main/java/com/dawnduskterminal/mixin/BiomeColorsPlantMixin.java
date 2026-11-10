package com.dawnduskterminal.mixin;

import com.dawnduskterminal.client.ChronoFoliageColors;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 包住 {@code BiomeColors} 的两个植物取色器，让晨昏线维度移植来的暮色森林群系拿到自己的草色与树叶色
 *
 * <p>树叶： jungle 走魔法森林的蓝色渐变、dark_forest 固定染红（原有行为），sparse_jungle 走暮色森林
 * 彩虹橡树叶的算法
 *
 * <p>草方块：sparse_jungle 与树叶同步，河海类群系固定为紫色，其余不动
 *
 * <p>两个包装都保留原取色器不动，只对认得出来的晨昏线群系 ID 改结果，所以暮色森林自己的 ASM 补丁
 * 与其他所有群系都不受影响
 */
@Mixin(BiomeColors.class)
public abstract class BiomeColorsPlantMixin {
    @Shadow
    @Final
    @Mutable
    public static ColorResolver FOLIAGE_COLOR_RESOLVER;

    @Shadow
    @Final
    @Mutable
    public static ColorResolver GRASS_COLOR_RESOLVER;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void ddt$wrapPlantResolvers(CallbackInfo ci) {
        ColorResolver foliage = FOLIAGE_COLOR_RESOLVER;
        FOLIAGE_COLOR_RESOLVER =
                (biome, x, z) -> ChronoFoliageColors.resolve(foliage.getColor(biome, x, z), biome, x, z);
        ColorResolver grass = GRASS_COLOR_RESOLVER;
        GRASS_COLOR_RESOLVER =
                (biome, x, z) -> ChronoFoliageColors.resolveGrass(grass.getColor(biome, x, z), biome, x, z);
    }
}
