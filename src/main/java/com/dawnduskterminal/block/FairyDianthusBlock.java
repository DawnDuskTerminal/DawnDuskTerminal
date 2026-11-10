package com.dawnduskterminal.block;

import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 高的那一种仙灵石竹：4~12 格高的主柱，中下部可以伸出 0~2 根分支柱
 *
 * <p>四向的布尔状态表示「这一格从哪个侧面接出一根分支」。与栅栏不同，这里<b>没有自动连接</b>：
 * 放下一格不会去改邻居，邻居也不会来改它。连接只有两个来源：
 *
 * <ul>
 *   <li>生成器（{@link com.dawnduskterminal.world.FairyDianthusFeature}）按几何一次性摆好</li>
 *   <li>玩家手持这种方块点击某一格的<b>侧面</b>时，由 {@link com.dawnduskterminal.item.FairyDianthusItem}
 *       在那一格打上连接，并在相邻格摆一个朝回主柱的转向件</li>
 * </ul>
 *
 * <p>{@link Part} 表示这一格在一根柱子里的角色，也就是取哪一份模型——{@code example.json} 里三个 bone
 * 的竖柱贴图并不一样：
 *
 * <ul>
 *   <li>{@link Part#STEM}：直柱，四面同一块柱子贴图</li>
 *   <li>{@link Part#BRANCH}：带分支的柱子，东 / 西两面换用示例里 {@code fairy_dianthus_branch} 那块</li>
 *   <li>{@link Part#TURN}：转向件，柱子从格底抬 4 像素、十二格高，东面是镜像</li>
 *   <li>{@link Part#DROP}：向下拐角，转向件的竖直镜像——柱子占 0~12 像素，从这一格往下挂</li>
 *   <li>{@link Part#HANG}：下垂柱，吊在拐角或另一根下垂柱下面的一格竖柱，没有侧向连接</li>
 * </ul>
 *
 * <p><b>侧面依附关系</b>：转向件与向下拐角都必须靠它们连着的那一格待着，主柱被挖掉时整根分支柱一起断；
 * 主柱上带连接的那一格自己立在下面那格上，不依赖分支柱。下垂柱的支撑在<b>上面</b>：
 * 拐角没了，下面一整列跟着断。
 *
 * <p><b>向下的一条整套用同一种方块</b>：拐角与下垂柱只差 {@link Part}，所以挖任意一格都走
 * {@link #playerDestroy}——整列一起拆掉，只掉一个 {@code fairy_haniwa}。
 *
 * <p>分支柱与主柱总是相邻：连接柱只占 1 格的水平距离，不会再往外延伸。
 */
public class FairyDianthusBlock extends AbstractFairyDianthusBlock {
    /** 这一格在一根柱子里的角色；五份模型对应示例里的三个 bone 与向下的一套 */
    public enum Part implements StringRepresentable {
        STEM("stem"),
        BRANCH("branch"),
        TURN("turn"),
        /** 向下拐角：柱子占格子的下半截，从这一格往下挂一根下垂柱 */
        DROP("drop"),
        /** 下垂柱：吊在拐角（或另一根下垂柱）下面的一格竖柱 */
        HANG("hang");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public FairyDianthusBlock(Properties properties) {
        super(properties);
        BlockState state = this.defaultBlockState();
        for (BooleanProperty stub : STUBS.values()) {
            state = state.setValue(stub, Boolean.FALSE);
        }
        this.registerDefaultState(state.setValue(PART, Part.STEM));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        for (BooleanProperty stub : STUBS.values()) {
            builder.add(stub);
        }
        builder.add(PART);
    }

    /** 某一侧的连接状态；竖直方向没有连接，返回 null */
    public static BooleanProperty stub(Direction direction) {
        return STUBS.get(direction);
    }

    /** 转向件：柱子抬起 4 像素的那种 */
    public static boolean isTurn(BlockState state) {
        return hasPart(state) && state.getValue(PART) == Part.TURN;
    }

    /** 向下拐角：一整列下垂柱的顶格 */
    public static boolean isDrop(BlockState state) {
        return hasPart(state) && state.getValue(PART) == Part.DROP;
    }

    /** 下垂柱：吊在拐角（或另一根下垂柱）下面的那一格 */
    public static boolean isHang(BlockState state) {
        return hasPart(state) && state.getValue(PART) == Part.HANG;
    }

    /** 向下的那一套（拐角 + 下垂柱）：挖任意一格都要整列拆掉 */
    public static boolean isHanging(BlockState state) {
        return hasPart(state) && isHangingPart(state.getValue(PART));
    }

    private static boolean isHangingPart(Part part) {
        return part == Part.DROP || part == Part.HANG;
    }

    /** 别的方块（比如空气）上没有 {@code part} 这个属性，取之前必须先判 */
    private static boolean hasPart(BlockState state) {
        return state.hasProperty(PART);
    }

    /**
     * 在某一格上接出一根分支
     *
     * <p>在当前状态上加一个连接，并把角色从 {@link Part#STEM} 换成 {@link Part#BRANCH}
     * （转向件保持 {@link Part#TURN}）。同一格上接第二、第三根分支走的是同一条路，
     * 所以不会把前一个连接覆盖掉。
     */
    public static BlockState withStub(BlockState state, Direction direction) {
        Part part = isTurn(state) ? Part.TURN : Part.BRANCH;
        return state.setValue(stub(direction), Boolean.TRUE).setValue(PART, part);
    }

    /** 分支柱的底格：朝回主柱接一根，角色是转向件 */
    public static BlockState turnBase(Block block, Direction towardsMain) {
        return block.defaultBlockState()
                .setValue(stub(towardsMain), Boolean.TRUE)
                .setValue(PART, Part.TURN);
    }

    /** 向下分支的底格：朝回主柱接一根，柱子挂在这一格下面 */
    public static BlockState dropBase(Block block, Direction towardsMain) {
        return block.defaultBlockState()
                .setValue(stub(towardsMain), Boolean.TRUE)
                .setValue(PART, Part.DROP);
    }

    /** 下垂柱的一格：没有侧向连接，只靠上面那格吊着 */
    public static BlockState hang(Block block) {
        return block.defaultBlockState().setValue(PART, Part.HANG);
    }

    /**
     * 在 {@code joint} 这一格的 {@code direction} 侧挂一根向下的分支：拐角 + 最多 {@code maxHang} 格下垂柱
     *
     * <p>玩家手持埴轮右键走这条（{@code FairyHaniwaItem}），与生成器
     * {@code FairyDianthusFeature#placeDownward} 的几何一致；差别只在写方块的方式——
     * 世界生成用 {@code UPDATE_CLIENTS}，玩家操作要带邻居更新，让支撑判定当场跑一遍
     *
     * @return 有没有挂成；外面没地方挂或这一侧已经有连接都算没挂成
     */
    public static boolean placeDownwardBranch(
            Block block, Level level, BlockPos joint, Direction direction, int maxHang) {
        BlockState jointState = level.getBlockState(joint);
        if (!jointState.is(block) || jointState.getValue(stub(direction))) {
            return false;
        }
        BlockPos corner = joint.relative(direction);
        if (!level.getBlockState(corner).canBeReplaced()) {
            return false;
        }
        int fitted = 0;
        while (fitted < maxHang && level.getBlockState(corner.below(fitted + 1)).canBeReplaced()) {
            fitted++;
        }
        if (fitted < 1) {
            return false;
        }
        level.setBlockAndUpdate(joint, withStub(jointState, direction));
        level.setBlockAndUpdate(corner, dropBase(block, direction.getOpposite()));
        for (int i = 1; i <= fitted; i++) {
            level.setBlockAndUpdate(corner.below(i), hang(block));
        }
        return true;
    }

    @Override
    protected boolean isConnected(BlockState state, Direction direction) {
        BooleanProperty stub = STUBS.get(direction);
        return stub != null && state.getValue(stub);
    }

    /**
     * 这一格靠什么撑着
     *
     * <ul>
     *   <li>{@link Part#STEM} / {@link Part#BRANCH}：脚下那一格（沙土，或同一根柱子的下一格）</li>
     *   <li>{@link Part#TURN} / {@link Part#DROP}：只看连出去的那一面，不要求脚下有东西——
     *       拐角本来就长在半空中</li>
     *   <li>{@link Part#HANG}：只看上面那一格，得是拐角或另一根下垂柱</li>
     * </ul>
     */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Part part = state.getValue(PART);
        if (part == Part.HANG) {
            BlockState above = level.getBlockState(pos.above());
            return above.is(this) && isHanging(above);
        }
        if (part != Part.TURN && part != Part.DROP) {
            return super.canSurvive(state, level, pos);
        }
        for (Direction direction : STUBS.keySet()) {
            if (state.getValue(STUBS.get(direction))
                    && level.getBlockState(pos.relative(direction)).is(this)) {
                return true;
            }
        }
        return false;
    }
    /**
     * 连出去的那一面撑不撑得住
     *
     * <p>主柱上带连接的那一格<b>不</b>靠分支柱：它自己立在下面那格上，分支柱只是搭出去的；
     * 反过来，转向件与向下拐角必须靠它连着的那根主柱，主柱没了它就断。
     */
    @Override
    protected boolean isSupport(BlockState state, Direction direction, BlockState neighbour) {
        if (!this.isConnected(state, direction) || (!isTurn(state) && !isDrop(state))) {
            return true;
        }
        return neighbour.is(this);
    }

    /**
     * 下垂柱吊在上面那一格上，所以上面变了也得重新判一次
     *
     * <p>脚下变了照旧走父类那条（对下垂柱来说 {@code canSurvive} 不看脚下，永远为真）
     */
    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbour,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighbourPos) {
        if (direction == Direction.UP && !this.canSurvive(state, level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    /** 转向件的柱子抬 4 像素、向下拐角的柱子占下半截，瞄准框与碰撞箱都不该是一整格 */
    @Override
    protected VoxelShape stemShape(BlockState state) {
        if (isTurn(state)) {
            return TURN_STEM;
        }
        if (isDrop(state)) {
            return DROP_STEM;
        }
        return STEM;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.shapeOf(state);
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.shapeOf(state);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    /**
     * 玩家挖掉向下的一套里的任意一格：整列一起拆
     *
     * <p>这里挂 {@code playerWillDestroy} 而不是只挂 {@code playerDestroy}：后者在创造模式下不会被调用，
     * 而「整列一起断」在创造模式里同样要发生。这一格本身由游戏自己移除，所以只拆其余的
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (isHanging(state) && !level.isClientSide) {
            collapseHangingColumn(level, pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 整列拆完之后只掉一个仙灵埴轮
     *
     * <p>不走战利品表：这一列只产出一件埴轮，所以这里不复用 {@code super.playerDestroy}，
     * 免得同时再掉繁缕菜
     */
    @Override
    public void playerDestroy(
            Level level,
            Player player,
            BlockPos pos,
            BlockState state,
            @Nullable BlockEntity blockEntity,
            ItemStack tool) {
        if (!isHanging(state)) {
            super.playerDestroy(level, player, pos, state, blockEntity, tool);
            return;
        }
        if (level.isClientSide) {
            return;
        }
        // 兜底：没走 playerWillDestroy 的调用方（别的模组、命令）也照样整列拆掉
        collapseHangingColumn(level, pos);
        Block.popResource(level, pos, new ItemStack(ModTerrainBlocks.FAIRY_HANIWA_ITEM.get()));
    }

    /** 把这一列里除 {@code pos} 以外的每一格都拆掉，返回拆了几格 */
    private int collapseHangingColumn(Level level, BlockPos pos) {
        List<BlockPos> column = new ArrayList<>();
        BlockPos cursor = pos.above();
        while (isHangingAt(level, cursor)) {
            column.add(cursor);
            cursor = cursor.above();
        }
        cursor = pos.below();
        while (isHangingAt(level, cursor)) {
            column.add(cursor);
            cursor = cursor.below();
        }
        for (BlockPos hanging : column) {
            level.removeBlock(hanging, false);
        }
        return column.size();
    }

    /** 这一格是不是向下那一套的一格 */
    private boolean isHangingAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(this) && isHanging(state);
    }
}
