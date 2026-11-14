package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.SkyIslandDensity;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** 将完整的密度函数对保存在所属 RandomState 上，并在首次使用时安全发布。 */
@Mixin(RandomState.class)
public abstract class SkyIslandDensityMixin implements SkyIslandDensity.Access {
    @Unique
    private volatile SkyIslandDensity ddt$islandDensity;

    @Override
    public SkyIslandDensity ddt$islandDensity() {
        SkyIslandDensity density = this.ddt$islandDensity;
        if (density == null) {
            synchronized (this) {
                density = this.ddt$islandDensity;
                if (density == null) {
                    this.ddt$islandDensity = density = SkyIslandDensity.resolve((RandomState) (Object) this);
                }
            }
        }
        return density;
    }
}
