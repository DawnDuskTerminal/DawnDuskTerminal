package com.dawnduskterminal.portal;

import com.dawnduskterminal.mixin.PortalFrameAccess;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public class NetherGateBlock extends RoutePortalBlock {
    public NetherGateBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_AXIS);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction side,
            BlockState neighbor,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        if (side.getAxis().isVertical() || side.getAxis() == state.getValue(BlockStateProperties.HORIZONTAL_AXIS)) {
            boolean frame = neighbor.is(Blocks.OBSIDIAN)
                    || (level instanceof Level world
                            && world.dimension().equals(ModDimensions.CHRONO)
                            && neighbor.is(ModTerrainBlocks.CHAOS_STONE.get()));
            if (!neighbor.equals(state) && !frame) return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(100) == 0) {
            level.playLocalSound(
                    pos.getX() + 0.5,
                    pos.getY() + 0.5,
                    pos.getZ() + 0.5,
                    SoundEvents.PORTAL_AMBIENT,
                    SoundSource.BLOCKS,
                    0.5F,
                    random.nextFloat() * 0.4F + 0.8F,
                    false);
        }
        boolean alongX = state.getValue(BlockStateProperties.HORIZONTAL_AXIS) == Direction.Axis.X;
        for (int i = 0; i < 4; i++) {
            int side = random.nextInt(2) * 2 - 1;
            double across = 0.5 + side * 0.25;
            double along = random.nextDouble();
            double vertical = random.nextDouble();
            double acrossSpeed = random.nextFloat() * 2 * side;
            double alongSpeed = (random.nextFloat() - 0.5) * 0.5;
            double verticalSpeed = (random.nextFloat() - 0.5) * 0.5;
            level.addParticle(
                    ParticleTypes.PORTAL,
                    pos.getX() + (alongX ? along : across),
                    pos.getY() + vertical,
                    pos.getZ() + (alongX ? across : along),
                    alongX ? alongSpeed : acrossSpeed,
                    verticalSpeed,
                    alongX ? acrossSpeed : alongSpeed);
        }
    }

    public static void ignite(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!level.dimension().equals(ModDimensions.CHRONO)
                || !event.getItemStack().is(Items.FLINT_AND_STEEL)
                || event.getFace() == null
                || !event.getEntity().mayBuild()) return;
        BlockPos pos = event.getPos().relative(event.getFace());
        if (!level.mayInteract(event.getEntity(), pos)) return;
        var shape = net.minecraft.world.level.portal.PortalShape.findEmptyPortalShape(level, pos, Direction.Axis.X);
        if (shape.isEmpty()) return;
        if (!level.isClientSide) {
            PortalFrameAccess frame = (PortalFrameAccess) shape.get();
            BlockState portal = ModBlocks.NETHER_PORTAL
                    .get()
                    .defaultBlockState()
                    .setValue(
                            BlockStateProperties.HORIZONTAL_AXIS,
                            frame.ddt$rightDir().getAxis());
            for (int x = 0; x < frame.ddt$width(); x++) {
                for (int y = 0; y < frame.ddt$height(); y++) {
                    level.setBlock(
                            frame.ddt$bottomLeft()
                                    .relative(frame.ddt$rightDir(), x)
                                    .above(y),
                            portal,
                            Block.UPDATE_CLIENTS);
                }
            }
            event.getItemStack().hurtAndBreak(1, event.getEntity(), LivingEntity.getSlotForHand(event.getHand()));
            level.playSound(null, pos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1, 1);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }
}
