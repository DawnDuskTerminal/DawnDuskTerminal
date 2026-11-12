package com.dawnduskterminal.block;

import com.dawnduskterminal.block.entity.TowerdevBuilderType2BlockEntity;
import com.dawnduskterminal.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;
import twilightforest.init.TFSounds;

/**
 * 砷铅铁建造器二型：照暮色森林的砷铅铁建造器（{@code twilightforest:carminite_builder}）做的那台的第二型
 *
 * <p>与那一台只差在下面三条，其余一律照它：
 * <ul>
 *   <li>不会超时：那一台造满 16 格、或者前路被堵住一秒之后就收工，
 *       再过 60 tick 自己切到 {@code timeout} 那一档并停摆；这一台只要还通着红石、附近还有人，
 *       就一直造下去，不设格数上限，也没有 timeout 那一档
 *   <li>造出来的方块是 {@link TowerdevBuiltType2Block}，稳定存在、可以挖、右键换档
 *   <li>红石关机时不发光，通着电时发 4 级光，与那一台同档
 * </ul>
 *
 * <p>照旧的部分：摆下那一刻与红石信号变化都换 {@link #ACTIVE} 状态，
 * 通着电时每 {@link TowerdevBuilderType2BlockEntity#BUILD_INTERVAL} tick 往「附近那位玩家正在看的方向」多造一格，
 * 视线朝上朝下就竖着造，其余按玩家的水平朝向；造之前要求那一格是空气；
 * 附近的判定是 16 格内最近的玩家（{@link TowerdevBuilderType2BlockEntity#PLAYER_RADIUS}），
 * 没有人的时候不造也不停，人一回来接着造。
 *
 * <p>方块状态只有 {@link #ACTIVE} 一条：没有 {@code range} 那种可调项，也没有 timeout 那一档。
 * 建造进度（造到哪一格、数到第几 tick）全在方块实体里，不落盘——区块重新载入后从本方块那一格重新起头，
 * 与暮色森林那一台的行为一致。
 */
public class TowerdevBuilderType2Block extends BaseEntityBlock {
    public static final MapCodec<TowerdevBuilderType2Block> CODEC = simpleCodec(TowerdevBuilderType2Block::new);

    /** 通着红石、正在造：为真时方块发 4 级光，每 10 tick 多造一格 */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public TowerdevBuilderType2Block(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    /** 方块实体只负责建造，外观仍是普通模型（{@code BaseEntityBlock} 的默认值是 invisible） */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    /**
     * 摆下那一刻就按当时的红石信号定下初值
     *
     * <p>贴着一条通着电的红石线摆下时，那条线不会因为这台机器落地而变化，也就不会给它发更新，
     * 只靠 {@link #neighborChanged} 会漏掉这一种
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(ACTIVE, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    /** 通着电摆下的那一台补一声「开启」：初值在 {@link #getStateForPlacement} 里就定好了，信号没变过 */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !oldState.is(this) && state.getValue(ACTIVE)) {
            level.playSound(null, pos, TFSounds.BUILDER_ON.get(), SoundSource.BLOCKS, 0.3F, 0.6F);
        }
    }

    /** 红石信号一来一去都在这里换 active：关机只停建造，方块与方块实体都留着 */
    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighborBlock,
            BlockPos neighborPos,
            boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide) {
            return;
        }
        boolean active = level.hasNeighborSignal(pos);
        if (active == state.getValue(ACTIVE)) {
            return;
        }
        level.setBlock(pos, state.setValue(ACTIVE, active), Block.UPDATE_ALL);
        level.playSound(
                null,
                pos,
                active ? TFSounds.BUILDER_ON.get() : TFSounds.BUILDER_OFF.get(),
                SoundSource.BLOCKS,
                0.3F,
                0.6F);
    }

    /** 通着电时照着暮色森林那一台的样子冒红石粉：只朝非实心的邻面冒，看不见的面不冒 */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ACTIVE)) {
            return;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (level.getBlockState(neighbour).isSolidRender(level, neighbour)) {
                continue;
            }
            Direction.Axis axis = direction.getAxis();
            double x = axis == Direction.Axis.X ? 0.5 + 0.5625 * direction.getStepX() : random.nextFloat();
            double y = axis == Direction.Axis.Y ? 0.5 + 0.5625 * direction.getStepY() : random.nextFloat();
            double z = axis == Direction.Axis.Z ? 0.5 + 0.5625 * direction.getStepZ() : random.nextFloat();
            level.addParticle(
                    DustParticleOptions.REDSTONE, pos.getX() + x, pos.getY() + y, pos.getZ() + z, 0.0, 0.0, 0.0);
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TowerdevBuilderType2BlockEntity(pos, state);
    }

    /**
     * 服务端每 tick 都要刻：关机时方块实体要清掉上一次的建造进度，通着电时要数到 10 再往前造一格
     *
     * <p>客户端一颗都不要，红石粉粒子由 {@link #animateTick} 负责
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(
                type, ModBlockEntities.TOWERDEV_BUILDER_TYPE2.get(), TowerdevBuilderType2BlockEntity::tick);
    }
}
