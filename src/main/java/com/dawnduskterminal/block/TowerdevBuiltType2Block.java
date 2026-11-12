package com.dawnduskterminal.block;

import com.mojang.serialization.MapCodec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import twilightforest.init.TFSounds;

/**
 * 砷铅铁构建方块二型：砷铅铁建造器二型建出来的那一格
 *
 * <p>与暮色森林的 {@code twilightforest:built_block} 只差在下面几条，其余一律照它：
 * <ul>
 *   <li>不会自己消失：那一块被点亮成绿的就重新排一次 10 tick 的刻，到点整格删掉，还顺带把邻格也点亮，
 *       整片会像涟漪一样一路化掉；这一块两个档位都一直留着
 *   <li>可以挖：那一块 {@code noLootTable}，挖掉什么都不掉；这一块带战利品表，挖掉掉自己
 *   <li>右键换档：两个档位都是可交互的，右键把<b>连在一起的同一档</b>整片翻到另一档，
 *       一次最多带 64 格（{@link #MAX_CONNECTED}）
 *   <li>绿的那一档能穿过去，并且能在里面往上爬
 *   <li>两档的贴图都按满亮画（模型上的 {@code neoforge_data}），不靠方块自己发光
 * </ul>
 *
 * <p>两个档位是同一个方块的 {@link #ON} 状态，不是两块方块：暮色森林那一块也是「一块方块两个状态」，
 * 只有这样才能在挖掘、战利品表与物品形态上只写一份。贴图名按档位分，
 * {@code towerdev_built_type2_off} 与 {@code towerdev_built_type2_on}，各自的 8x8 连接图集另有一张。
 *
 * <p>「相连」按六个面算，与那一块化掉时点亮邻格的判据一致：斜角挨着的两格不互通。
 * 搜索走广度优先，凑够 {@link #MAX_CONNECTED} 格就停，所以一大片连着的格子一次只翻一小块，
 * 多右键几次才全翻完——上限是规格要求，不是漏写。
 */
public class TowerdevBuiltType2Block extends Block {
    public static final MapCodec<TowerdevBuiltType2Block> CODEC = simpleCodec(TowerdevBuiltType2Block::new);

    /** 绿的那一档：没有碰撞、可攀爬、右键翻回暗的那一档 */
    public static final BooleanProperty ON = BooleanProperty.create("on");

    /** 一次右键最多翻多少格 */
    public static final int MAX_CONNECTED = 64;

    public TowerdevBuiltType2Block(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ON, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ON);
    }

    /**
     * 绿的那一档没有碰撞，可以穿过去
     *
     * <p>轮廓形状仍然是一个整方块（{@code BlockBehaviour#getShape} 的默认值就是整方块，与碰撞无关），
     * 所以穿过去的格子照样点得到，右键才换得回来。
     */
    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(ON) ? Shapes.empty() : super.getCollisionShape(state, level, pos, context);
    }

    /**
     * 绿的那一档当梯子用：站在里面按跳就往上爬
     *
     * <p>{@code LivingEntity#onClimbable} 里的梯子判定走 NeoForge 的 {@code IBlockExtension#isLadder}，
     * 原版那张 {@code #minecraft:climbable} 标签管不到状态，而这里只有绿的那一档该能爬
     */
    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) {
        return state.getValue(ON);
    }

    /** 空着手右键：整片翻档 */
    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide) {
            toggle(level, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 挨着的两块之间那一面不画
     *
     * <p>格子是一层疏格，两块并排时中间那一面在视觉上本来就该并成一格；留着它既有重叠的格线，
     * 又是同一平面上两份几何。原版的写法是 {@code skipRendering}：{@code Block#shouldRenderFace}
     * 第一件事就问它，返回真这一面直接不生成。判据「邻格也是本方块」不分档位——
     * 暗的挨着绿的也一样该并起来
     */
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacentState, Direction side) {
        return adjacentState.is(this) || super.skipRendering(state, adjacentState, side);
    }

    /**
     * 拿着东西右键：手上就是本方块自己的方块物品时，优先摆下去，不是换档
     *
     * <p>这里必须返回 {@code SKIP_DEFAULT_BLOCK_INTERACTION}，不能返回
     * {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}：后者在 {@code ServerPlayerGameMode#useItemOn} 与客户端那份
     * {@code MultiPlayerGameMode#performUseItemOn} 里是「交给方块的默认交互」，也就是接着调
     * {@link #useWithoutItem} —— 那正是换档，点击在放置之前就被吃掉了。跳过默认交互之后才会走到
     * {@code BlockItem#useOn}，放置本身照原版那一套落在点击面的邻格
     * （{@code BlockPlaceContext#getClickedPos} 在点到的方块不可替换时给的是相邻格）
     */
    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit) {
        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == this) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            toggle(level, pos);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * 把 {@code origin} 这一格连着的那一片翻到另一档，返回翻了多少格
     *
     * <p>只翻「同一档」的格子：暗的那一片翻成绿的，绿的那一片翻回暗的。
     * 两片挨着摆时互不影响，各自翻各自的
     */
    public static int toggle(Level level, BlockPos origin) {
        BlockState state = level.getBlockState(origin);
        if (!(state.getBlock() instanceof TowerdevBuiltType2Block)) {
            return 0;
        }
        boolean on = state.getValue(ON);
        List<BlockPos> group = connected(level, origin, on);
        if (group.isEmpty()) {
            return 0;
        }
        for (BlockPos pos : group) {
            level.setBlock(pos, level.getBlockState(pos).setValue(ON, !on), Block.UPDATE_ALL);
        }
        // 变形音效照抄暮色森林「重现方块」的那一对，事件、音源、音量与音高全都一样
        // （{@code VanishingBlock#tick}）：变绿是重现，0.3 音量 0.6 音高；变回暗的是那一声消失，0.3 音量 0.5 音高
        level.playSound(
                null,
                origin,
                on ? TFSounds.REAPPEAR_POOF.get() : TFSounds.REAPPEAR_BLOCK.get(),
                SoundSource.BLOCKS,
                0.3F,
                on ? 0.5F : 0.6F);
        return group.size();
    }

    /**
     * 从 {@code origin} 出发，按六个面连成一片、档位相同的前 {@link #MAX_CONNECTED} 格
     *
     * <p>{@code origin} 本身不算命中时返回空表：那里不是本方块，或者档位与 {@code on} 不符
     */
    public static List<BlockPos> connected(LevelReader level, BlockPos origin, boolean on) {
        List<BlockPos> found = new ArrayList<>();
        BlockState start = level.getBlockState(origin);
        if (!(start.getBlock() instanceof TowerdevBuiltType2Block) || start.getValue(ON) != on) {
            return found;
        }

        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        seen.add(origin);
        queue.addLast(origin);
        while (!queue.isEmpty() && found.size() < MAX_CONNECTED) {
            BlockPos pos = queue.removeFirst();
            found.add(pos);
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (seen.add(next) && same(level, next, on)) {
                    queue.addLast(next);
                }
            }
        }
        return found;
    }

    /** 这一格是不是本方块、而且就是 {@code on} 那一档 */
    private static boolean same(LevelReader level, BlockPos pos, boolean on) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof TowerdevBuiltType2Block && state.getValue(ON) == on;
    }
}
