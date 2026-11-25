package com.dawnduskterminal.block;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModChronoOres;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

public final class MimicBlock extends MultifaceBlock implements SimpleWaterloggedBlock {
    public static final MapCodec<MimicBlock> CODEC = simpleCodec(MimicBlock::new);
    public static final BooleanProperty DEEP = BooleanProperty.create("deep");
    private static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final TagKey<Block> SUPPORTS =
            TagKey.create(Registries.BLOCK, DawnDuskTerminal.id("mimic_supports"));
    private static final TagKey<Block> ORE_SUPPORTS =
            TagKey.create(Registries.BLOCK, DawnDuskTerminal.id("mimic_ores"));

    private static final List<Supplier<? extends Block>> SHALLOW_ORES = List.of(
            ModChronoOres.CANOPY_STONE_COPPER_ORE,
            ModChronoOres.CANOPY_STONE_IRON_ORE,
            ModChronoOres.CANOPY_STONE_GOLD_ORE,
            ModChronoOres.CANOPY_STONE_REDSTONE_ORE,
            ModChronoOres.CANOPY_STONE_LAPIS_ORE,
            ModChronoOres.CANOPY_STONE_DIAMOND_ORE,
            ModTerrainBlocks.SCARLET_MOLYBDENUM_ORE,
            ModChronoOres.CARID_ORE,
            ModChronoOres.TRANSFORE_ORE);

    private static final List<Supplier<? extends Block>> DEEP_ORES = List.of(
            ModChronoOres.CHARNOCKITE_COPPER_ORE,
            ModChronoOres.CHARNOCKITE_IRON_ORE,
            ModChronoOres.CHARNOCKITE_GOLD_ORE,
            ModChronoOres.CHARNOCKITE_REDSTONE_ORE,
            ModChronoOres.CHARNOCKITE_LAPIS_ORE,
            ModChronoOres.CHARNOCKITE_DIAMOND_ORE,
            ModChronoOres.DEEP_SCARLET,
            ModChronoOres.DIRAC_ORE);

    private final MultifaceSpreader spreader = new MultifaceSpreader(this);

    public MimicBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(DEEP, false).setValue(WATERLOGGED, false));
    }

    @Override
    public MapCodec<MimicBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(DEEP, WATERLOGGED);
    }

    @Override
    public boolean isValidStateForPlacement(BlockGetter level, BlockState state, BlockPos pos, Direction face) {
        return level.getBlockState(pos.relative(face)).is(SUPPORTS)
                && super.isValidStateForPlacement(level, state, pos, face);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        BlockState placed = super.getStateForPlacement(state, level, pos, face);
        if (placed != null && !state.is(this)) {
            BlockState support = level.getBlockState(pos.relative(face));
            boolean deep = support.is(ModTerrainBlocks.CHARNOCKITE.get())
                    || support.is(ORE_SUPPORTS) && DEEP_ORES.stream().anyMatch(ore -> support.is(ore.get()));
            return placed.setValue(DEEP, deep);
        }
        return placed;
    }

    public static Block disguise(BlockGetter level, BlockState state, BlockPos pos) {
        for (Direction face : DIRECTIONS) {
            if (hasFace(state, face)) {
                BlockState support = level.getBlockState(pos.relative(face));
                if (support.is(ORE_SUPPORTS)) {
                    return support.getBlock();
                }
            }
        }
        List<Supplier<? extends Block>> ores = state.getValue(DEEP) ? DEEP_ORES : SHALLOW_ORES;
        long seed = state.getSeed(pos);
        return ores.get(Math.floorMod(seed, ores.size())).get();
    }

    @Override
    public MultifaceSpreader getSpreader() {
        return spreader;
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbor,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        if (hasFace(state, direction) && !neighbor.is(SUPPORTS)) {
            BlockState remaining = state.setValue(getFaceProperty(direction), false);
            return hasAnyFace(remaining) ? remaining : state.getFluidState().createLegacyBlock();
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public boolean onDestroyedByPlayer(
            BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        boolean removed = super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        if (removed && player instanceof ServerPlayer serverPlayer && !player.hasInfiniteMaterials()) {
            ItemStack tool = player.getMainHandItem();
            if (tool.isDamageableItem()) {
                damageTool(serverPlayer, tool);
            }
        }
        return removed;
    }

    private static void damageTool(ServerPlayer player, ItemStack tool) {
        // 直接结算实际损耗，使耐久附魔不会降低模仿者的扣除量。
        int damage = tool.getDamageValue() + player.getRandom().nextIntBetweenInclusive(64, 128);
        var item = tool.getItem();
        player.awardStat(Stats.ITEM_USED.get(item));
        CriteriaTriggers.ITEM_DURABILITY_CHANGED.trigger(player, tool, damage);
        tool.setDamageValue(damage);
        if (damage >= tool.getMaxDamage()) {
            tool.shrink(1);
            player.onEquippedItemBroken(item, EquipmentSlot.MAINHAND);
        }
    }
}
