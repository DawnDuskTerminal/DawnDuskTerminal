package com.dawnduskterminal.block;

import com.dawnduskterminal.block.entity.AntibuilderType2BlockEntity;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.world.AntibuilderType2Field;
import com.dawnduskterminal.world.CubeFieldMarker;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import twilightforest.init.TFBlocks;

/**
 * 反建器二型：照暮色森林的 {@code twilightforest:antibuilder} 做的那台「反建器」的第二型
 *
 * <p>与那一台只差在下面四条，其余一律照它：
 * <ul>
 *   <li>范围可变：场是一个立方体，半径由 {@link #RANGE} 这一条状态给出（1~8，即 3×3×3 到 17×17×17），
 *       默认最大那档；创造模式玩家右键加大一格、潜行右键缩小一格，两端封顶不再变化
 *   <li>不扫也不存方块状态：那台机器每 tick 把 17³ 格抄进 {@code BlockState[]}，再慢慢往回改；
 *       这一台一个字节都不存，方块被破坏或被放置的那一刻当场反应，判定在 {@link AntibuilderType2Field}
 *   <li>被破坏的方块换成 {@code dawnduskterminal:antibuildblock_type2}，也就是
 *       {@link ModBlocks#ANTIBUILD_BLOCK_TYPE2}
 *   <li>反建器家族例外：范围内被破坏的方块本身是反建器或本方块时把它复原，被放置进范围的是这两样时不动它。
 *       唯一的例外是相邻：本方块挨着另一台开着的本方块放（切比雪夫距离 ≤ 1，斜角也算）会被破坏，摆不下去
 *   <li>收到红石信号就关机：场整个停摆，框线不画，方块自己也不再发光；信号一断就恢复
 * </ul>
 *
 * <p>方块自己那一格不受自己的场管辖（判定在 {@link AntibuilderType2Field#covers}）：否则任何半径都包住场心，
 * 单台机器放下就再也拆不掉。别的机器的场照管它，所以范围内的反建器二型被破坏时照样复原；
 * 距离 2 格以上摆进彼此的场里时就会互相保护，只能靠命令移走，这一点是规格的直接后果
 *
 * <p>登记走两条路：{@link #onPlace} 让方块摆下的那一 tick 就生效，方块实体的 {@code onLoad} 管区块重新载入；
 * 撤登记同理。场的范围由 {@link CubeFieldMarker} 那圈红石粉框线画出来（与暮色森林反建器同款，只在客户端画），
 * 服务端那颗 ticker 是空的
 */
public class AntibuilderType2Block extends Block implements EntityBlock {
    public static final MapCodec<AntibuilderType2Block> CODEC = simpleCodec(AntibuilderType2Block::new);

    /** 最小场：3×3×3，中心那一格往每个方向各推 1 格 */
    public static final int MIN_RADIUS = 1;

    /** 最大场：17×17×17，中心那一格往每个方向各推 8 格，与暮色森林反建器的 9×9×9 之外的「更高级那一档」一致 */
    public static final int MAX_RADIUS = 8;

    /** 场的半边长，也就是这条状态；直径是 {@code 2 * range + 1} */
    public static final IntegerProperty RANGE = IntegerProperty.create("range", MIN_RADIUS, MAX_RADIUS);

    /**
     * 收到红石信号就关机：为真时这个场整个停摆（不反应、不画框线），方块自己也不再发光
     *
     * <p>用方块状态而不是现算：客户端要照着它决定画不画框线，而红石信号在客户端算不准。
     * 摆下那一刻按当时的信号定下（{@link #getStateForPlacement}），之后靠 {@link #neighborChanged} 跟着变
     */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public AntibuilderType2Block(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(RANGE, MAX_RADIUS).setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RANGE, POWERED);
    }

    /**
     * 摆下那一刻就按当时的红石信号定下 powered
     *
     * <p>贴着一条通着电的红石线摆下时，线本身不会因为这台机器落地而变化，也就不会给这台机器发更新，
     * 只靠 {@link #neighborChanged} 会漏掉这一种
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    /** 红石信号一来一去都在这里换 powered：关机只停场，方块、半径与场心登记都留着 */
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
        boolean powered = level.hasNeighborSignal(pos);
        if (powered != state.getValue(POWERED)) {
            level.setBlock(pos, state.setValue(POWERED, powered), 3);
        }
    }

    /** 这一条状态是不是反建器家族：暮色森林那一台，或者本模组这一台 */
    public static boolean isAntibuilderFamily(BlockState state) {
        return state.is(TFBlocks.ANTIBUILDER.get()) || state.is(ModBlocks.ANTIBUILDER_TYPE2.get());
    }

    /** 场心那一格给出的半边长；不是本方块的状态就按最小场算 */
    public static int radiusOf(BlockState state) {
        return state.hasProperty(RANGE) ? state.getValue(RANGE) : MIN_RADIUS;
    }

    /** 半边长换算成直径：{@code 2 * range + 1} */
    public static int diameter(int radius) {
        return radius * 2 + 1;
    }

    /**
     * 右键一格之后的新半边长
     *
     * <p>右键加大、潜行右键缩小，两端封顶不再变化（不回绕）。判定单独摆出来是为了让测试能直接钉住两个方向，
     * 不必去构造一个「按住潜行键的假玩家」
     */
    public static int nextRadius(int radius, boolean shrink) {
        return Mth.clamp(shrink ? radius - 1 : radius + 1, MIN_RADIUS, MAX_RADIUS);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AntibuilderType2BlockEntity(pos, state);
    }

    /** 只有客户端刻：那颗 ticker 只负责把场的立方框画出来，服务端没有任何每 tick 要做的事 */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (!level.isClientSide) {
            return null;
        }
        return (clientLevel, pos, tickState, blockEntity) -> {
            if (tickState.getValue(POWERED)) {
                // 关机：框线一颗粒子都不画
                return;
            }
            CubeFieldMarker.tick(clientLevel, pos, tickState, blockEntity, radiusOf(tickState));
        };
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!oldState.is(this)) {
            AntibuilderType2Field.track(level, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            AntibuilderType2Field.untrack(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** 空着手右键：创造模式调范围，其它模式什么也不做（不拦交互） */
    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!adjust(state, level, pos, player)) {
            return InteractionResult.PASS;
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 拿着东西右键也一样要能调，否则手上随便拿块方块就只剩放置这一条路 */
    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (!adjust(state, level, pos, player)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 调一格范围，返回这次右键算不算「这个方块的交互」
     *
     * <p>只有创造模式玩家能调，其它玩家右键等于没点在这台机器上（返回 {@code false}，交互继续往下走）。
     * 两端封顶时仍然算交互成功，只是数值不变，玩家能从动作栏那一行看出已经到头了
     */
    private static boolean adjust(BlockState state, Level level, BlockPos pos, Player player) {
        if (!player.isCreative()) {
            return false;
        }
        if (level.isClientSide) {
            return true;
        }
        int before = radiusOf(state);
        int after = nextRadius(before, player.isShiftKeyDown());
        if (after != before) {
            level.setBlock(pos, state.setValue(RANGE, after), 3);
        }
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.5F, 0.6F + 0.05F * after);
        int size = diameter(after);
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.displayClientMessage(
                    Component.translatable("block.dawnduskterminal.antibuilder_type2.range", size, size, size), true);
        }
        return true;
    }
}
