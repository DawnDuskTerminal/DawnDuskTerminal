package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.ChunkSpawnBiome;
import com.dawnduskterminal.world.PaletteBiomeSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 区块生成刷怪取哪一格群系
 *
 * <h2>钉住的是什么</h2>
 *
 * <p>原版 {@code NoiseBasedChunkGenerator#spawnOriginalMobs} 取表用的是世界顶端那一格群系。
 * 晨昏线维度的高度是 384，顶端为 319，那一格的采样深度恒低于 −1.6，因此必然落到天门群系
 * （{@code dawnduskterminal:floatrees}）。{@link #topOfTheWorldIsTheSkyBiome} 直接按噪声路由把这一条
 * 算出来，确认「顶端是天空群系、地表不是」这一对条件在真实世界生成里成立——一旦天门判据或高度改动，
 * 这条会失败并提醒重看 {@link ChunkSpawnBiome} 与 {@code ChunkSpawnBiomeMixin}。
 *
 * <p>{@link #spawnYFollowsTheSurface} 钉住取样点的换算：不许退回建筑上限减一。
 *
 * <p>混入本身不在这里跑：测试服没有晨昏线维度，{@code spawnOriginalMobs} 的注入发生在真实维度的区块生成上。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class ChunkSpawnBiomeTests {
    private static final ResourceLocation CHRONO = DawnDuskTerminal.id("chrono");

    /** 维度高度 384、{@code min_y} 为 −64，因此顶端那一格是 319 */
    private static final int TOP_OF_WORLD_Y = 319;

    /** 取一个河海地表附近的 y，用来对照顶端 */
    private static final int SURFACE_Y = 70;

    /** 扫描半径，单位是区块 */
    private static final int CHUNK_RADIUS = 64;

    private static final long SEED = 42L;

    /**
     * 顶端那一格恒为天门群系，而地表不是
     *
     * <p>只要找到一列满足「顶端深度低于 −1.6、地表深度不低于 −1.6」，就说明原版取样点在本维度上
     * 取到的是天空群系而不是脚下那份表
     */
    @GameTest(template = "ddt_empty")
    public static void topOfTheWorldIsTheSkyBiome(GameTestHelper helper) {
        DensityFunction depth = depthFunction(helper);
        int matches = 0;
        int scanned = 0;
        for (int chunkX = -CHUNK_RADIUS; chunkX <= CHUNK_RADIUS; chunkX++) {
            for (int chunkZ = -CHUNK_RADIUS; chunkZ <= CHUNK_RADIUS; chunkZ++) {
                int x = (chunkX << 4) + 8;
                int z = (chunkZ << 4) + 8;
                scanned++;
                boolean skyAtTop = PaletteBiomeSource.isSkyBiomeDepth(evaluate(depth, x, TOP_OF_WORLD_Y, z));
                boolean skyAtSurface = PaletteBiomeSource.isSkyBiomeDepth(evaluate(depth, x, SURFACE_Y, z));
                if (skyAtTop && !skyAtSurface) {
                    matches++;
                }
            }
        }
        helper.assertTrue(scanned > 0, "the scan must cover at least one column");
        helper.assertTrue(
                matches > 0,
                "no column has the sky biome at y " + TOP_OF_WORLD_Y + " and the ground biome at y " + SURFACE_Y
                        + " over " + scanned + " columns; the sampling point in ChunkSpawnBiomeMixin assumes it does");
        helper.succeed();
    }

    /** 取样点跟着地表走，不跟着建筑上限走 */
    @GameTest(template = "ddt_empty")
    public static void spawnYFollowsTheSurface(GameTestHelper helper) {
        int minY = -64;
        int maxY = 320;

        // 高度图给的是地表之上第一格：63 的第一格可用高度是 64，减一落在 63
        helper.assertTrue(
                ChunkSpawnBiome.spawnY(64, minY, maxY) == 63,
                "a surface whose first free block is 64 must be sampled at 63, got "
                        + ChunkSpawnBiome.spawnY(64, minY, maxY));
        helper.assertTrue(
                ChunkSpawnBiome.spawnY(64, minY, maxY) != maxY - 1,
                "the sampling point must not be the build limit minus one");
        // 满列夹到顶端那一格
        helper.assertTrue(
                ChunkSpawnBiome.spawnY(maxY, minY, maxY) == maxY - 1,
                "a full column must clamp to the topmost block, got " + ChunkSpawnBiome.spawnY(maxY, minY, maxY));
        helper.assertTrue(
                ChunkSpawnBiome.spawnY(minY, minY, maxY) == minY,
                "a column below the world must clamp to the lowest block");
        helper.succeed();
    }

    private static DensityFunction depthFunction(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        var noises = access.lookupOrThrow(Registries.NOISE);
        NoiseGeneratorSettings settings =
                access.registryOrThrow(Registries.NOISE_SETTINGS).get(CHRONO);
        if (settings == null) {
            throw new IllegalStateException("noise settings " + CHRONO + " missing");
        }
        return RandomState.create(settings, noises, SEED).sampler().depth();
    }

    private static double evaluate(DensityFunction depth, int x, int y, int z) {
        return depth.compute(new DensityFunction.SinglePointContext(x, y, z));
    }
}
