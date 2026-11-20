package com.dawnduskterminal.block;

import com.dawnduskterminal.block.entity.PortalWaterfallBlockEntity;
import com.dawnduskterminal.fluid.PortalWaterfallSupply;
import com.dawnduskterminal.fluid.WaterfallFluid;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.world.PortalWaterfallColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 保存装饰瀑布的长度，并在上游或同列地形变化后重新校验。 */
public final class PortalWaterfallBlock extends Block implements EntityBlock {
    public static final int MIN_LENGTH = 16;
    public static final int MAX_LENGTH = 48;
    public static final IntegerProperty LENGTH = IntegerProperty.create("length", 1, MAX_LENGTH);
    public static final EnumProperty<WaterfallFluid> FLUID = EnumProperty.create("fluid", WaterfallFluid.class);
    public static final int FALLING_RUN = 4;

    public PortalWaterfallBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LENGTH, MIN_LENGTH).setValue(FLUID, WaterfallFluid.PORTAL));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LENGTH, FLUID);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public PortalWaterfallBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PortalWaterfallBlockEntity(pos, state);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
        super.onPlace(state, level, pos, previous, moved);
        if (!previous.is(this) && level instanceof ServerLevel server) {
            PortalWaterfallColumns.track(server, pos);
        }
    }

    public static boolean trySpread(
            LevelAccessor level, BlockPos pos, BlockState replaced, Direction direction, WaterfallFluid fluid) {
        return direction == Direction.DOWN
                && level instanceof ServerLevel server
                && server.dimension().equals(ModDimensions.CHRONO)
                && tryPlace(server, pos, replaced, fluid);
    }

    public static boolean tryPlace(ServerLevel level, BlockPos pos, BlockState replaced, WaterfallFluid type) {
        if (replaced.is(ModBlocks.PORTAL_WATERFALL.get())) {
            return replaced.getValue(FLUID) == type;
        }
        if (!replaced.isAir() || !hasFallingRun(level, pos, type) || !isSupplied(level, pos, type)) {
            return false;
        }
        int length = measureLength(level, pos);
        return length >= requiredLength(level, pos)
                && level.setBlock(
                        pos,
                        ModBlocks.PORTAL_WATERFALL
                                .get()
                                .defaultBlockState()
                                .setValue(LENGTH, length)
                                .setValue(FLUID, type),
                        UPDATE_ALL);
    }

    private static boolean hasFallingRun(ServerLevel level, BlockPos pos, WaterfallFluid type) {
        for (int dy = 1; dy <= FALLING_RUN; dy++) {
            FluidState fluid = level.getFluidState(pos.above(dy));
            if (!fluid.is(type.flowing()) || !fluid.getValue(FlowingFluid.FALLING)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupplied(ServerLevel level, BlockPos pos, WaterfallFluid type) {
        return PortalWaterfallSupply.isSupplied(level, pos.above(), type.flowing());
    }

    private static int measureLength(ServerLevel level, BlockPos pos) {
        int target =
                MIN_LENGTH + RandomSource.create(level.getSeed() ^ pos.asLong()).nextInt(MAX_LENGTH - MIN_LENGTH + 1);
        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
        int length = 1;
        while (length < target
                && pos.getY() - length >= level.getMinBuildHeight()
                && level.getBlockState(scan.set(pos.getX(), pos.getY() - length, pos.getZ()))
                        .isAir()) {
            length++;
        }
        return length;
    }

    private static int requiredLength(ServerLevel level, BlockPos pos) {
        return Math.min(MIN_LENGTH, pos.getY() - level.getMinBuildHeight() + 1);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        WaterfallFluid type = state.getValue(FLUID);
        boolean supplied = isSupplied(level, pos, type);
        int length = supplied && hasFallingRun(level, pos, type) ? measureLength(level, pos) : 0;
        if (length < requiredLength(level, pos)) {
            level.removeBlock(pos, false);
            BlockPos above = pos.above();
            FluidState upstream = level.getFluidState(above);
            if (supplied && length > 0) {
                level.scheduleTick(above, upstream.getType(), upstream.getType().getTickDelay(level));
            }
        } else if (length != state.getValue(LENGTH)) {
            level.setBlock(pos, state.setValue(LENGTH, length), UPDATE_CLIENTS);
        }
    }
}
