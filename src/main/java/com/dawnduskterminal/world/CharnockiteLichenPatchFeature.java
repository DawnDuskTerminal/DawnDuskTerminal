package com.dawnduskterminal.world;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

public final class CharnockiteLichenPatchFeature extends Feature<NoneFeatureConfiguration> {
    private static final ResourceKey<Biome> DEEP_DARK =
            ResourceKey.create(Registries.BIOME, DawnDuskTerminal.id("deep_dark"));

    public CharnockiteLichenPatchFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        Block stone = ModTerrainBlocks.CHARNOCKITE.get();
        if (!level.getLevel().dimension().equals(ModDimensions.CHRONO)) {
            return false;
        }

        BlockPos.MutableBlockPos floor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        int centerY = origin.getY() + 1;
        for (; centerY >= origin.getY() - 3; centerY--) {
            floor.set(origin.getX(), centerY, origin.getZ());
            above.setWithOffset(floor, 0, 1, 0);
            if (isExposedFloor(level, floor, above, stone)) {
                break;
            }
        }
        if (centerY < origin.getY() - 3) {
            return false;
        }

        RandomSource random = context.random();
        int radius = 2 + random.nextInt(4);
        BlockState lichen = ModTerrainBlocks.CHARNOCKITE_LICHEN.get().defaultBlockState();
        boolean placed = false;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                if (x * x + z * z > radius * radius * (0.65F + 0.45F * random.nextFloat())) {
                    continue;
                }
                int worldX = origin.getX() + x;
                int worldZ = origin.getZ() + z;
                for (int y = centerY + 2; y >= centerY - 2; y--) {
                    floor.set(worldX, y, worldZ);
                    above.set(worldX, y + 1, worldZ);
                    if (isExposedFloor(level, floor, above, stone)) {
                        placed |= level.setBlock(floor, lichen, Block.UPDATE_CLIENTS);
                        break;
                    }
                }
            }
        }
        return placed;
    }

    private static boolean isExposedFloor(WorldGenLevel level, BlockPos floor, BlockPos above, Block stone) {
        return level.getBlockState(floor).is(stone)
                && level.isEmptyBlock(above)
                && level.getBiome(floor).is(DEEP_DARK);
    }
}
