package com.dawnduskterminal.block;

import com.dawnduskterminal.block.entity.SunflowerBlockEntity;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.List;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 向日葵、月葵与鬼花的公共底座：段号、顶段、向下依附、掉落时机、瞄准框、方块实体
 *
 * <p>四类花盘（{@code sunflower}、{@code moonflower}、{@code ghoflower}、{@code lunarghoflower}）
 * 与两类茎都继承本类。昼侧与夜侧、普通与鬼花各自是独立的方块，
 * 因此任何一边要独立的名字、掉落、外观或行为时，都不必改动另一边。
 *
 * <h2>依附方向向下</h2>
 *
 * <p>每段只依赖它下面那一格：底段看地面（{@code #minecraft:dirt}），其余段看下一格是本侧方块且段号正好小一。
 * 挖任意一段，它上面各段依次失去支撑、自毁；下面那截留在原地。
 *
 * <h2>掉落</h2>
 *
 * <p>玩家破坏任意一段时，{@link #onDestroyedByPlayer} 沿这一列找到顶段，再由 {@link #dropPlant}
 * 掉一件；顶段已经不在了就不掉。{@link #playerDestroy} 只记统计与消耗，{@link #onRemove} 只做移除。
 * {@link #getDrops} 返回空，因此倒塌带走的段与其它按战利品表结算的路径都不会掉出第二件。
 * 战利品表文件保留在资源里，但不是掉落来源。
 */
public abstract class AbstractSunflowerBlock extends Block implements EntityBlock {
    /** 段号：0 是底段，最多 {@link #MAX_HEIGHT} 段 */
    public static final IntegerProperty SEGMENT = IntegerProperty.create("segment", 0, 2);

    /** 这一格是不是顶段，也就带不带花盘 */
    public static final BooleanProperty HEAD = BooleanProperty.create("head");

    /** 一株最多几格 */
    public static final int MAX_HEIGHT = 3;

    /** 顶段的柱身高（像素）：顶上那半格留给花盘 */
    public static final float HEAD_STEM_HEIGHT = 8.0F;

    protected AbstractSunflowerBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(SEGMENT, 0).setValue(HEAD, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SEGMENT, HEAD);
    }

    /**
     * 玩家放下来的苗按生成器同样的掷法抽 1~3 格，能放下几格就放几格
     *
     * <p>与 {@code SunflowerFeature} 同一套随机：{@code 1 + random.nextInt(MAX_HEIGHT)}，
     * 上方空间不够就按实际能放下的高度截断，最少仍是一格。
     *
     * <p>底段换成茎方块。这一步不走 {@link #playerDestroy}，因此不产生物品。
     */
    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !state.getValue(HEAD)) {
            return;
        }
        int wanted = 1 + level.getRandom().nextInt(MAX_HEIGHT);
        int height = 1;
        while (height < wanted && level.getBlockState(pos.above(height)).canBeReplaced()) {
            height++;
        }
        if (height <= 1) {
            return;
        }
        // 底段与中段换成茎方块，顶段才是手里的花盘方块；整株因此只有一个「盘」
        Block stem = this.stemBlock();
        level.setBlock(pos, stem.defaultBlockState().setValue(SEGMENT, 0).setValue(HEAD, false), Block.UPDATE_ALL);
        for (int index = 1; index < height; index++) {
            if (index == height - 1) {
                level.setBlock(
                        pos.above(index),
                        this.defaultBlockState().setValue(SEGMENT, index).setValue(HEAD, true),
                        Block.UPDATE_ALL);
            } else {
                level.setBlock(
                        pos.above(index),
                        stem.defaultBlockState().setValue(SEGMENT, index).setValue(HEAD, false),
                        Block.UPDATE_ALL);
            }
        }
    }

    /**
     * 这一格能不能长在 {@code below} 上
     *
     * <p>两种方块共用这一条：段号必须接上（下面那格段号减一），并且属于同一株
     * （昼侧的茎配昼侧的花盘、夜侧的茎配夜侧的花盘、鬼花配任意一侧的茎）。
     */
    static boolean isSamePlant(BlockState below, BlockState state) {
        Block belowBlock = below.getBlock();
        Block block = state.getBlock();
        if (block instanceof MoonflowerBlock || block instanceof LunarGhoflowerBlock) {
            return belowBlock instanceof MoonflowerStemBlock || belowBlock instanceof MoonflowerBlock;
        }
        if (block instanceof GhoflowerBlock) {
            return belowBlock instanceof SunflowerStemBlock || belowBlock instanceof SunflowerBlock;
        }
        if (block instanceof SunflowerBlock) {
            return belowBlock instanceof SunflowerStemBlock || belowBlock instanceof SunflowerBlock;
        }
        if (block instanceof MoonflowerStemBlock) {
            return belowBlock instanceof MoonflowerStemBlock || belowBlock instanceof MoonflowerBlock;
        }
        if (block instanceof SunflowerStemBlock) {
            return belowBlock instanceof SunflowerStemBlock || belowBlock instanceof SunflowerBlock;
        }
        return false;
    }

    /** 这一侧对应的茎方块：昼侧向日葵茎、夜侧月葵茎、鬼花按它所在的一侧 */
    protected Block stemBlock() {
        if (this instanceof MoonflowerBlock || this instanceof LunarGhoflowerBlock) {
            return ModTerrainBlocks.MOONFLOWER_STEM.get();
        }
        return ModTerrainBlocks.SUNFLOWER_STEM.get();
    }

    /** 物品放下来是一格的独立植株，段号 0、带花盘 */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(SEGMENT, 0).setValue(HEAD, true);
    }

    /** 只向下依附：下面那格是同一株的上一段，或者底段下面是土类 */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        int segment = state.getValue(SEGMENT);
        BlockState below = level.getBlockState(pos.below());
        if (below.getBlock() instanceof AbstractSunflowerBlock) {
            return isSamePlant(below, state) && below.getValue(SEGMENT) == segment - 1;
        }
        return segment == 0 && below.is(BlockTags.DIRT);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbour,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighbourPos) {
        if (direction == Direction.DOWN && !this.canSurvive(state, level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    /**
     * 每格一个方块实体
     *
     * <p>方块的模型是空的，几何全部由 {@code SunflowerRenderer} 画。
     * 没有这个方块实体，渲染器一次也不会被调用，方块就只剩瞄准框。
     */
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SunflowerBlockEntity(pos, state);
    }

    /**
     * 这里只做移除
     *
     * <p>整株倒塌是逐段移除的，每一段都会走到这里；掉落由 {@link #onDestroyedByPlayer}
     * 与 {@link #onExplosionHit} 按整株结算，掉在这里就是「每节都掉」。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * 玩家破坏任意一段：按整株结算一次
     *
     * <p>顶段可能不是被挖的那一段（挖茎时花盘还在），所以先沿这一列向上找顶段。
     * 顶段已经不在（例如先前被挖掉，只剩茎）就不掉。创造模式不掉。
     */
    @Override
    public boolean onDestroyedByPlayer(
            BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest, FluidState fluid) {
        if (level instanceof ServerLevel serverLevel && player != null && !player.isCreative()) {
            BlockPos headPos = findHead(level, pos);
            if (headPos != null) {
                this.dropPlant(serverLevel, headPos, player);
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    /** 沿这一列向上找顶段；这一列不是完整的同株花就不返回 */
    @Nullable
    private static BlockPos findHead(LevelReader level, BlockPos pos) {
        BlockPos cursor = pos;
        for (int step = 0; step <= MAX_HEIGHT; step++) {
            BlockState current = level.getBlockState(cursor);
            if (!(current.getBlock() instanceof AbstractSunflowerBlock)) {
                return null;
            }
            if (current.getValue(HEAD)) {
                return cursor;
            }
            cursor = cursor.above();
        }
        return null;
    }

    /**
     * 顶段被破坏时掉的那一件
     *
     * <p>普通花就是顶段自己的物品；鬼花覆写这里，改成按阶段掉并传染。
     */
    protected void dropPlant(ServerLevel level, BlockPos headPos, @Nullable Player player) {
        BlockState head = level.getBlockState(headPos);
        if (head.getValue(HEAD)) {
            Block.popResource(level, headPos, new ItemStack(head.getBlock().asItem()));
        }
    }

    /**
     * 这里不查战利品表
     *
     * <p>掉落只由 {@link #onDestroyedByPlayer} 与 {@link #onExplosionHit} 发出；
     * 倒塌带走的段、以及其它经 {@code Block.dropResources} 的路径都拿不到物品，
     * 免得同一株掉出第二件。战利品表不作为掉落来源。
     */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        return List.of();
    }

    /** 玩家破坏：只记统计与消耗，掉落由 {@link #onDestroyedByPlayer} 结算 */
    @Override
    public void playerDestroy(
            Level level,
            Player player,
            BlockPos pos,
            BlockState state,
            @Nullable BlockEntity blockEntity,
            ItemStack tool) {
        player.awardStat(Stats.BLOCK_MINED.get(this));
        player.causeFoodExhaustion(0.005F);
    }

    /** 爆炸：只有顶段掉一件；倒塌带走的其余段不掉 */
    @Override
    protected void onExplosionHit(
            BlockState state,
            Level level,
            BlockPos pos,
            Explosion explosion,
            BiConsumer<ItemStack, BlockPos> dropConsumer) {
        if (!level.isClientSide && state.getValue(HEAD) && level instanceof ServerLevel serverLevel) {
            this.dropPlant(serverLevel, pos, null);
        }
        state.onBlockExploded(level, pos, explosion);
    }

    /** 没有碰撞，可以走过去 */
    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
