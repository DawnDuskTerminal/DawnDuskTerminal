package com.dawnduskterminal.portal;

import java.util.Comparator;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import twilightforest.data.tags.BlockTagGenerator;
import twilightforest.init.TFDimension;

final class RouteExit {
    private RouteExit() {}

    static Vec3 findOrCreate(ServerLevel level, BlockPos origin, PortalRoute route, LivingEntity entity) {
        var pois = level.getPoiManager();
        int radius = route == PortalRoute.NETHER && !level.dimension().equals(Level.NETHER) ? 128 : 24;
        pois.ensureLoadedAndValid(level, origin, radius);
        var existing = pois.getInSquare(
                        type -> type.value()
                                .matchingStates()
                                .contains(route.block().defaultBlockState()),
                        origin,
                        radius,
                        PoiManager.Occupancy.ANY)
                .map(record -> record.getPos())
                .sorted(Comparator.comparingDouble(origin::distSqr))
                .filter(pos -> level.getBlockState(pos).is(route.block())
                        && (route == PortalRoute.TWILIGHT
                                ? TwilightPoolPortalBlock.isFramed(level, pos)
                                        && (!level.dimension().equals(TFDimension.DIMENSION_KEY)
                                                || hasOpenSurface(level, pos.above(), false))
                                : PortalShape.isCompletePortalPart(level, pos)))
                .map(pos -> beside(level, pos, entity, route))
                .filter(Objects::nonNull)
                .findFirst();
        if (existing.isPresent()) return existing.get();

        if (route == PortalRoute.TWILIGHT) return buildSurfacePool(level, origin, entity);

        int low = level.getMinBuildHeight() + 2;
        int high = level.getMaxBuildHeight() - 5;
        if (level.dimension().equals(Level.NETHER))
            high = Math.min(
                    high, level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 6);
        int y = Mth.clamp(origin.getY(), low, high);
        BlockPos floating = null;
        for (int ring = 0; ring <= 12; ring += 4) {
            for (int dx = -ring; dx <= ring; dx += 4) {
                for (int dz = -ring; dz <= ring; dz += 4) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int i = 0; i <= 2 * Math.max(high - y, y - low); i++) {
                        int height = y + (i % 2 == 0 ? -i / 2 : (i + 1) / 2);
                        if (height < low || height > high) continue;
                        BlockPos feet = new BlockPos(origin.getX() + dx, height, origin.getZ() + dz);
                        if (!level.getBlockState(feet).isAir()) continue;
                        boolean grounded = solid(level, feet.below());
                        if (!grounded && floating != null) continue;
                        if (!fitsNetherGate(level, feet, entity)) continue;
                        if (grounded) return buildNetherGate(level, feet);
                        floating = feet;
                    }
                }
            }
        }
        return floating == null ? null : buildNetherGate(level, floating);
    }

    private static Vec3 buildSurfacePool(ServerLevel level, BlockPos origin, LivingEntity entity) {
        BlockPos site = findPoolSite(level, origin, entity, false);
        if (site == null) site = findPoolSite(level, origin, entity, true);
        if (site == null) return null;

        for (BlockPos pos : BlockPos.betweenClosed(site.offset(-1, -1, -3), site.offset(2, -1, 0))) {
            if (poolCorner(site, pos)) continue;
            boolean interior = poolInterior(site, pos);
            if (interior && !poolGround(level, pos.below())) {
                level.setBlockAndUpdate(pos.below(), Blocks.DIRT.defaultBlockState());
            }
            var state = interior
                    ? PortalRoute.TWILIGHT.block().defaultBlockState()
                    : Blocks.GRASS_BLOCK.defaultBlockState();
            BlockPos above = pos.above();
            if (interior || !PortalShape.isPlantable(level.getBlockState(pos))) {
                if (!interior && level.getBlockState(above).is(Blocks.SNOW)) {
                    state = state.setValue(net.minecraft.world.level.block.SnowyDirtBlock.SNOWY, true);
                }
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            }
            boolean entrance = above.equals(site) || above.equals(site.east());
            for (int dy = 0; dy < 2; dy++) {
                BlockPos clear = above.above(dy);
                BlockState cover = level.getBlockState(clear);
                if (cover.isAir()
                        || (!interior && (cover.is(Blocks.SNOW) || (!entrance && PortalShape.isPlant(cover)))))
                    continue;
                level.setBlockAndUpdate(clear, Blocks.AIR.defaultBlockState());
            }
            if (!interior && !entrance && level.isEmptyBlock(above)) {
                BlockState plant = BuiltInRegistries.BLOCK
                        .getTag(BlockTagGenerator.GENERATED_PORTAL_DECO)
                        .flatMap(tag -> tag.getRandomElement(level.getRandom()))
                        .map(holder -> holder.value().defaultBlockState())
                        .orElse(Blocks.SHORT_GRASS.defaultBlockState());
                if (plant.canSurvive(level, above)) level.setBlockAndUpdate(above, plant);
            }
        }
        return poolLanding(level, site);
    }

    private static BlockPos findPoolSite(ServerLevel level, BlockPos origin, LivingEntity entity, boolean fallback) {
        int step = fallback ? 1 : 2;
        int radius = fallback ? 16 : 32;
        for (int ring = 0; ring <= radius; ring += step) {
            for (int dx = -ring; dx <= ring; dx += step) {
                for (int dz = -ring; dz <= ring; dz += step) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    int surface = surfaceHeight(level, x, z);
                    int lowest = Math.max(level.getMinBuildHeight() + 2, surface - (fallback ? 2 : 1));
                    for (int y = surface; y >= lowest; y--) {
                        BlockPos feet = new BlockPos(x, y, z);
                        if (fitsPool(level, feet, entity, fallback)) return feet;
                    }
                }
            }
        }
        return null;
    }

    private static boolean fitsPool(ServerLevel level, BlockPos feet, LivingEntity entity, boolean fallback) {
        var box = entity.getDimensions(entity.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(feet));
        if (!level.getWorldBorder().isWithinBounds(box)
                || box.maxY > feet.getY() + 2
                || box.getXsize() > 1
                || box.getZsize() > 1
                || feet.getY() >= level.getMaxBuildHeight() - 1) return false;
        if (!poolGround(level, feet.below()) && !(fallback && poolGround(level, feet.below(2)))) return false;
        if (!hasOpenSurface(level, feet, fallback)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-1, -1, -3), feet.offset(2, -1, 0))) {
            if (poolCorner(feet, pos)) continue;
            boolean interior = poolInterior(feet, pos);
            if (!level.getWorldBorder().isWithinBounds(pos)) return false;
            if (!poolGround(level, pos) && !poolClearable(level, pos, fallback)) return false;
            if (interior && !poolGround(level, pos.below())) {
                if (!fallback || !poolClearable(level, pos.below(), true) || !poolGround(level, pos.below(2)))
                    return false;
            } else if (!interior && !poolGround(level, pos) && !poolGround(level, pos.below())) {
                return false;
            }
            if (!hasOpenSurface(level, pos.above(), fallback)
                    || !poolClearable(level, pos.above(), fallback)
                    || !poolClearable(level, pos.above(2), fallback)) return false;
        }
        return true;
    }

    private static int surfaceHeight(ServerLevel level, int x, int z) {
        // 兴趣点索引只要求空区块，读高度图前先取得完整地形。
        var chunk = level.getChunk(x >> 4, z >> 4);
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
    }

    private static boolean hasOpenSurface(ServerLevel level, BlockPos feet, boolean fallback) {
        int surface = surfaceHeight(level, feet.getX(), feet.getZ());
        for (int y = feet.getY(); y < surface; y++) {
            if (!poolClearable(level, feet.atY(y), fallback)) return false;
        }
        return true;
    }

    private static Vec3 poolLanding(ServerLevel level, BlockPos feet) {
        Vec3 landing = Vec3.atBottomCenterOf(feet);
        var state = level.getBlockState(feet);
        if (!state.is(Blocks.SNOW)) return landing;
        var snow = state.getCollisionShape(level, feet);
        return snow.isEmpty() ? landing : landing.add(0, snow.max(Direction.Axis.Y), 0);
    }

    private static boolean poolClearable(ServerLevel level, BlockPos pos, boolean fallback) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.SNOW) || PortalShape.isPlant(state)) return true;
        if (!fallback || state.is(BlockTags.FEATURES_CANNOT_REPLACE) || state.hasBlockEntity()) return false;
        var fluid = state.getFluidState();
        return fluid.isEmpty()
                ? state.canBeReplaced() || state.is(BlockTags.LEAVES)
                : fluid.is(FluidTags.WATER) && state.liquid();
    }

    private static boolean poolCorner(BlockPos feet, BlockPos pos) {
        int x = pos.getX() - feet.getX();
        int z = pos.getZ() - feet.getZ();
        return (x == -1 || x == 2) && (z == -3 || z == 0);
    }

    private static boolean poolInterior(BlockPos feet, BlockPos pos) {
        int x = pos.getX() - feet.getX();
        int z = pos.getZ() - feet.getZ();
        return x >= 0 && x < 2 && z >= -2 && z <= -1;
    }

    private static boolean poolGround(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return solid(level, pos) && (PortalShape.isPlantable(state) || state.is(BlockTags.BASE_STONE_OVERWORLD));
    }

    private static Vec3 beside(ServerLevel level, BlockPos portal, LivingEntity entity, PortalRoute route) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = portal.relative(dir);
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos feet = side.above(dy);
                boolean pool = route == PortalRoute.TWILIGHT;
                Vec3 landing = pool ? poolLanding(level, feet) : Vec3.atBottomCenterOf(feet);
                if (solid(level, feet.below())
                        && clearLanding(level, landing, entity)
                        && (!pool
                                || !level.dimension().equals(TFDimension.DIMENSION_KEY)
                                || hasOpenSurface(level, feet, false))) return landing;
            }
        }
        return null;
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)
                && level.getFluidState(pos).isEmpty()
                && !level.getBlockState(pos).is(Blocks.MAGMA_BLOCK);
    }

    private static boolean clearLanding(ServerLevel level, BlockPos feet, LivingEntity entity) {
        return clearLanding(level, Vec3.atBottomCenterOf(feet), entity);
    }

    private static boolean clearLanding(ServerLevel level, Vec3 feet, LivingEntity entity) {
        var box = entity.getDimensions(entity.getPose()).makeBoundingBox(feet);
        return level.getWorldBorder().isWithinBounds(box)
                && level.noCollision(entity, box)
                && !level.containsAnyLiquid(box)
                && BlockPos.betweenClosedStream(box)
                        .noneMatch(p -> PortalRoute.from(level.getBlockState(p)) != null
                                || level.getBlockState(p).is(Blocks.NETHER_PORTAL)
                                || level.getBlockState(p).is(BlockTags.FIRE));
    }

    private static boolean fitsNetherGate(ServerLevel level, BlockPos feet, LivingEntity entity) {
        if (!clearLanding(level, feet, entity)) return false;
        BlockPos from = feet.offset(-1, -1, -1);
        BlockPos to = feet.offset(2, 3, -1);
        if (!level.getWorldBorder().isWithinBounds(from)
                || !level.getWorldBorder().isWithinBounds(to)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (level.getBlockState(pos).isAir()) continue;
            if (pos.getY() != feet.getY() - 1
                    || !level.getBlockState(pos).is(BlockTags.BASE_STONE_OVERWORLD)
                            && !level.getBlockState(pos).is(BlockTags.BASE_STONE_NETHER)
                            && !level.getBlockState(pos).is(BlockTags.DIRT)) return false;
        }
        return level.getBlockState(feet.below()).isAir() || solid(level, feet.below());
    }

    private static Vec3 buildNetherGate(ServerLevel level, BlockPos feet) {
        if (!solid(level, feet.below())) level.setBlockAndUpdate(feet.below(), Blocks.OBSIDIAN.defaultBlockState());
        for (int x = -1; x <= 2; x++) {
            for (int y = -1; y <= 3; y++) {
                boolean edge = x == -1 || x == 2 || y == -1 || y == 3;
                level.setBlock(
                        feet.offset(x, y, -1),
                        (edge ? Blocks.OBSIDIAN : PortalRoute.NETHER.block()).defaultBlockState(),
                        Block.UPDATE_CLIENTS);
            }
        }
        return Vec3.atBottomCenterOf(feet);
    }
}
