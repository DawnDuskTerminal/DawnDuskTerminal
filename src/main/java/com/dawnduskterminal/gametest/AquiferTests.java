package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.ChronoTerrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 晨昏线的噪声设置是 {@code minecraft:noise_settings/overworld.json} 的移植版本
 *
 * 地形方块 含水层流体 地表规则都直接指向晨昏线调色板：维度生成时就铺自己的方块，而不是先生成一列
 * 原版再改写
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class AquiferTests {
    private static final int VANILLA_SEA_LEVEL = 63;
    private static final ResourceLocation CHRONO = ResourceLocation.fromNamespaceAndPath("dawnduskterminal", "chrono");

    private static NoiseGeneratorSettings chronoSettings(GameTestHelper helper) {
        Registry<NoiseGeneratorSettings> registry =
                helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS);
        NoiseGeneratorSettings settings = registry.get(CHRONO);
        helper.assertTrue(settings != null, "noise settings " + CHRONO + " missing");
        return settings;
    }

    /** 生成器本身必须指向模组方块，这才是「原生调色板」的关键 */
    @GameTest(template = "ddt_empty")
    public static void terrainAndFluidUseTheModPalette(GameTestHelper helper) {
        NoiseGeneratorSettings settings = chronoSettings(helper);
        helper.assertTrue(
                settings.defaultBlock().is(ModTerrainBlocks.CANOPY_STONE.get()),
                "the chrono terrain must be generated as canopy_stone, got " + settings.defaultBlock());
        helper.assertTrue(
                settings.defaultFluid().is(ModBlocks.PORTAL_FLUID.get()),
                "aquifers and oceans must be generated as portal_fluid, got " + settings.defaultFluid());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void aquifersAreEnabledLikeVanilla(GameTestHelper helper) {
        NoiseGeneratorSettings settings = chronoSettings(helper);
        helper.assertTrue(settings.aquifersEnabled(), "chrono must keep aquifers enabled");
        helper.assertTrue(
                settings.seaLevel() == VANILLA_SEA_LEVEL,
                "sea level must match the vanilla overworld value " + VANILLA_SEA_LEVEL + ", got "
                        + settings.seaLevel());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void oreVeinsAreEnabledLikeVanilla(GameTestHelper helper) {
        NoiseGeneratorSettings settings = chronoSettings(helper);
        helper.assertTrue(
                settings.oreVeinsEnabled(),
                "the port keeps vanilla ore veins; the old hand-rolled terrain disabled them to hide the "
                        + "granite/andesite they blended with canopy_stone, which no longer applies");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void chronoBaseColumnsProtectBedrockAndClearTheBottom(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var settings = registries
                .lookupOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(ResourceKey.create(Registries.NOISE_SETTINGS, DawnDuskTerminal.id("chrono")));
        var state = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), 1592614637L);
        var terrain = ((ChronoTerrain.RandomStateAccess) (Object) state).ddt$chronoTerrain();
        helper.assertTrue(terrain != null, "the seeded terrain must be available to the aquifer");
        var generator = new NoiseBasedChunkGenerator(
                new FixedBiomeSource(helper.getLevel().getBiome(BlockPos.ZERO)), settings);
        var heights = LevelHeightAccessor.create(-64, 384);
        var column = generator.getBaseColumn(1520, -1680, heights, state);
        helper.assertTrue(column.getBlock(-64).isAir(), "the bottom must be air even in a wet column");
        helper.assertTrue(
                column.getBlock(0).equals(settings.value().defaultBlock()), "ordinary terrain must be solid at y=0");

        BlockPos core = findVoidCore(terrain);
        helper.assertTrue(core != null, "expected a wild void in the surveyed area");
        var cave = generator.getBaseColumn(core.getX(), core.getZ(), heights, state);
        for (int y = -4; y <= 4; y++) {
            helper.assertTrue(cave.getBlock(y).isAir(), "wild void must be dry through bedrock at y=" + y);
        }
        helper.succeed();
    }

    private static BlockPos findVoidCore(ChronoTerrain terrain) {
        for (int x = -3000; x <= 3000; x += 48) {
            for (int z = -3000; z <= 3000; z += 48) {
                if (terrain.voidDensity(new DensityFunction.SinglePointContext(x, 0, z)) < -0.92) {
                    return new BlockPos(x, 0, z);
                }
            }
        }
        return null;
    }

    @GameTest(template = "ddt_empty")
    public static void wildVoidSpansBothAxesAtBedrock(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var settings = chronoSettings(helper);
        for (long seed : new long[] {1, 2, 1592614637L}) {
            var state = RandomState.create(settings, registries.lookupOrThrow(Registries.NOISE), seed);
            var terrain = ((ChronoTerrain.RandomStateAccess) (Object) state).ddt$chronoTerrain();
            helper.assertTrue(terrain != null, "the seeded void shape must be available");
            BlockPos core = findVoidCore(terrain);
            helper.assertTrue(core != null, "expected a wild void in the surveyed area for seed " + seed);
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int x = core.getX() - 324; x <= core.getX() + 324; x += 4) {
                for (int z = core.getZ() - 324; z <= core.getZ() + 324; z += 4) {
                    if (terrain.voidDensity(new DensityFunction.SinglePointContext(x, 0, z)) < 0) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }
            int width = maxX - minX + 1;
            int length = maxZ - minZ + 1;
            DawnDuskTerminal.LOGGER.info(
                    "Wild void survey seed={} core={} width={} length={}", seed, core, width, length);
            helper.assertTrue(width > 160 && width <= 640, "void width at bedrock for seed " + seed + ": " + width);
            helper.assertTrue(length > 160 && length <= 640, "void length at bedrock for seed " + seed + ": " + length);
        }
        helper.succeed();
    }
}
