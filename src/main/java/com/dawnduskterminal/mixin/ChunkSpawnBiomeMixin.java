package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.ChunkSpawnBiome;
import com.dawnduskterminal.world.PaletteBiomeSource;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 区块生成阶段的动物刷怪改用该区块的地表群系，而不是世界顶端那一格
 *
 * <h2>问题</h2>
 *
 * <p>原版 {@code NoiseBasedChunkGenerator#spawnOriginalMobs} 这样取表：
 * {@code getBiome(chunkPos.getWorldPosition().atY(getMaxBuildHeight() - 1))}。主世界里顶端与地表同群系，
 * 所以没问题；晨昏线维度的顶端恒落在天门群系（深度低于 −1.6），于是河海、海岸与其余空表群系的区块
 * 在生成阶段都按天门那份暮色森林动物表刷，一包鹿、羊、兔直接落在河海群系的霞草地上。
 * 落点判定只查方块标签与亮度，不复核落点群系，因此从数据包侧看不出来。
 *
 * <h2>改法</h2>
 *
 * <p>只接管晨昏线维度：其余维度原样返回，交给原版。随机数与种子照抄原版那三行，保证同一区块刷出的
 * 内容与改前一致（唯一区别是用哪份表、以及落点判定读到的群系）。问群系的一点在
 * {@link ChunkSpawnBiome#of}，见那里的说明。
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class ChunkSpawnBiomeMixin {
    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Inject(method = "spawnOriginalMobs", at = @At("HEAD"), cancellable = true)
    private void ddt$spawnWithSurfaceBiome(WorldGenRegion level, CallbackInfo ci) {
        // 以群系源认维度：PaletteBiomeSource 只被晨昏线维度引用，其余维度原样交回原版
        if (!(((ChunkGenerator) (Object) this).getBiomeSource() instanceof PaletteBiomeSource)) {
            return;
        }
        ci.cancel();
        if (this.settings.value().disableMobGeneration()) {
            return;
        }
        ChunkPos pos = level.getCenter();
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(level.getSeed(), pos.getMinBlockX(), pos.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(level, ChunkSpawnBiome.of(level, pos), pos, random);
    }
}
