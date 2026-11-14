package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.SkyIslandDensity;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 空岛判据必须在真实维度里能用：既要真的抓到两个密度函数，也要真的挑得出岛、又不会把地下认成岛
 *
 * <p>函数抓取失败时整个模组会静默退化成"没有空岛"，所以这条断言比几何测试更靠前
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class SkyIslandDensityTests {
    private static final ResourceKey<NoiseGeneratorSettings> CHRONO =
            ResourceKey.create(Registries.NOISE_SETTINGS, DawnDuskTerminal.id("chrono"));

    @GameTest(template = "ddt_empty")
    public static void densityFunctionsAreResolved(GameTestHelper helper) {
        SkyIslandDensity density = density(helper, 0x5EED5EEDL);

        helper.assertTrue(
                density.ready(),
                "the island predicate must resolve " + SkyIslandDensity.SKY_ISLAND + " and "
                        + SkyIslandDensity.BASE_3D_NOISE);
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void undergroundIsNotIsland(GameTestHelper helper) {
        SkyIslandDensity density = density(helper, 0x5EED5EEDL);

        for (int x = -64; x <= 64; x += 16) {
            for (int z = -64; z <= 64; z += 16) {
                helper.assertTrue(
                        !density.isIsland(x, -50, z),
                        "solid ground at y -50 must never be an island, failed at " + x + "," + z);
            }
        }
        helper.succeed();
    }

    /** 用真实的晨昏线设置建一个带种子的 {@code RandomState}，跟世界生成走的同一条路 */
    private static SkyIslandDensity density(GameTestHelper helper, long seed) {
        var access = helper.getLevel().getServer().registryAccess();
        NoiseGeneratorSettings settings =
                access.registryOrThrow(Registries.NOISE_SETTINGS).getOrThrow(CHRONO);
        return SkyIslandDensity.of(RandomState.create(settings, access.lookupOrThrow(Registries.NOISE), seed));
    }

    @GameTest(template = "ddt_empty")
    public static void theIslandBandHasIslands(GameTestHelper helper) {
        SkyIslandDensity density = density(helper, 0x5EED5EEDL);

        int columns = 0;
        int islandColumns = 0;
        for (int x = -192; x <= 192; x += 4) {
            for (int z = -192; z <= 192; z += 4) {
                boolean island = false;
                for (int y = 136; y <= 310; y++) {
                    if (density.isIsland(x, y, z)) {
                        island = true;
                    }
                }
                columns++;
                if (island) {
                    islandColumns++;
                }
                for (int y = 136; y < 140; y++) {
                    helper.assertTrue(
                            !density.isIsland(x, y, z),
                            "no island material may sit below the band, failed at " + x + "," + y + "," + z);
                }
            }
        }
        double coverage = (double) islandColumns / columns;

        helper.assertTrue(
                islandColumns > 0, "the y 140~310 band must hold island material somewhere near spawn, found none");
        // 实测约 7%，给到 12% 只是挡住"整片大陆"这种回归
        helper.assertTrue(
                coverage < 0.12D,
                "islands must stay islands, not a continent: " + Math.round(coverage * 100.0D)
                        + "% of sampled columns");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void densityBelongsToItsRandomState(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        NoiseGeneratorSettings settings =
                access.registryOrThrow(Registries.NOISE_SETTINGS).getOrThrow(CHRONO);
        RandomState first = RandomState.create(settings, access.lookupOrThrow(Registries.NOISE), 1L);
        SkyIslandDensity density = SkyIslandDensity.of(first);
        double original = density.skyValue(20, 220, 30);
        SkyIslandDensity other = density(helper, 2L);
        helper.assertTrue(density != other, "different random states must own different predicates");
        helper.assertTrue(density == SkyIslandDensity.of(first), "one random state must reuse its predicate");
        helper.assertTrue(
                original == density.skyValue(20, 220, 30), "another seed must not replace the first predicate");
        NoiseGeneratorSettings vanilla =
                access.registryOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        SkyIslandDensity missing =
                SkyIslandDensity.of(RandomState.create(vanilla, access.lookupOrThrow(Registries.NOISE), 3L));
        helper.assertFalse(missing.ready(), "a state without island markers must not reuse previous functions");
        helper.assertFalse(missing.isIsland(20, 220, 30), "missing markers must disable the predicate");
        helper.assertTrue(density.ready(), "a failed resolution must not invalidate another state");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void concurrentResolutionPublishesOneCompletePair(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        NoiseGeneratorSettings settings =
                access.registryOrThrow(Registries.NOISE_SETTINGS).getOrThrow(CHRONO);
        RandomState state = RandomState.create(settings, access.lookupOrThrow(Registries.NOISE), 4L);
        var tasks = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> java.util.concurrent.CompletableFuture.supplyAsync(() -> SkyIslandDensity.of(state))
                        .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS))
                .toList();
        SkyIslandDensity first = tasks.getFirst().join();
        for (var task : tasks) {
            helper.assertTrue(
                    task.join() == first && task.join().ready(), "all callers must see the same complete pair");
        }
        helper.succeed();
    }
}
