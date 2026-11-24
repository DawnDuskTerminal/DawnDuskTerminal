package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("dawnduskterminalworldgensurvey")
@PrefixGameTestTemplate(false)
public final class UndergroundAquiferTests {
    @GameTest(template = "ddt_empty")
    public static void undergroundFluidIsSparserThanOverworld(GameTestHelper helper) {
        var chrono = survey(helper, ResourceKey.create(Registries.NOISE_SETTINGS, DawnDuskTerminal.id("chrono")));
        var overworld = survey(helper, NoiseGeneratorSettings.OVERWORLD);
        DawnDuskTerminal.LOGGER.info("Underground aquifer survey: chrono={}, overworld={}", chrono, overworld);
        helper.assertTrue(
                chrono.fluid() < overworld.fluid() && chrono.water() < overworld.water(),
                "expected fewer underground fluid and water blocks than in the overworld");
        helper.assertTrue(
                chrono.wetColumns() > 58 && chrono.wetColumns() < overworld.wetColumns(),
                "expected water in more sampled columns than the previous 58 and fewer than in the overworld");
        helper.assertTrue(
                chrono.minY() >= -37 && chrono.maxY() <= -17 && chrono.maxRun() <= 11,
                "expected underground water in thin bands below bedrock");
        helper.succeed();
    }

    private static Survey survey(GameTestHelper helper, ResourceKey<NoiseGeneratorSettings> key) {
        var registries = helper.getLevel().registryAccess();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key);
        var generator = new NoiseBasedChunkGenerator(
                new FixedBiomeSource(helper.getLevel().getBiome(BlockPos.ZERO)), settings);
        var heights = LevelHeightAccessor.create(-64, 384);
        int fluid = 0;
        int water = 0;
        int minY = 0;
        int maxY = -64;
        int maxRun = 0;
        int wetColumns = 0;
        for (long seed : new long[] {1, 2, 1592614637L}) {
            var state = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), seed);
            for (int x = -1152; x <= 1152; x += 96) {
                for (int z = -1152; z <= 1152; z += 96) {
                    var column = generator.getBaseColumn(x, z, heights, state);
                    int run = 0;
                    boolean wet = false;
                    for (int y = -63; y < 0; y++) {
                        var block = column.getBlock(y);
                        var stateAtY = block.getFluidState();
                        if (!stateAtY.isEmpty()) {
                            fluid++;
                        }
                        if (block.is(ModBlocks.PORTAL_FLUID.get()) || stateAtY.is(FluidTags.WATER)) {
                            water++;
                            wet = true;
                            minY = Math.min(minY, y);
                            maxY = Math.max(maxY, y);
                            maxRun = Math.max(maxRun, ++run);
                        } else {
                            run = 0;
                        }
                    }
                    if (wet) {
                        wetColumns++;
                    }
                }
            }
        }
        return new Survey(fluid, water, minY, maxY, maxRun, wetColumns);
    }

    private record Survey(int fluid, int water, int minY, int maxY, int maxRun, int wetColumns) {}
}
