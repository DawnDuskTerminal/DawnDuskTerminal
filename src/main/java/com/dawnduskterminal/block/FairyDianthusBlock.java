package com.dawnduskterminal.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
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
 * </ul>
 *
 * <p><b>侧面依附关系</b>：转向件必须靠它连着的那一格待着，主柱被挖掉时整根分支柱一起断；
 * 主柱上带连接的那一格自己立在下面那格上，不依赖分支柱。
 *
 * <p>分支柱与主柱总是相邻：连接柱只占 1 格的水平距离，不会再往外延伸。
 */
public class FairyDianthusBlock extends AbstractFairyDianthusBlock {
    /** 这一格在一根柱子里的角色；三份模型对应示例里的三个 bone */
    public enum Part implements StringRepresentable {
        STEM("stem"),
        BRANCH("branch"),
        TURN("turn");

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
        return state.getValue(PART) == Part.TURN;
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

    @Override
    protected boolean isConnected(BlockState state, Direction direction) {
        BooleanProperty stub = STUBS.get(direction);
        return stub != null && state.getValue(stub);
    }

    /** 转向件要靠在它连着的那一格上；普通柱子只靠脚下 */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!super.canSurvive(state, level, pos)) {
            return false;
        }
        if (!isTurn(state)) {
            return true;
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
     * 反过来，转向件（分支柱的底格）必须靠它连着的那根主柱，主柱没了它就断。
     */
    @Override
    protected boolean isSupport(BlockState state, Direction direction, BlockState neighbour) {
        if (!this.isConnected(state, direction) || !isTurn(state)) {
            return true;
        }
        return neighbour.is(this);
    }

    /** 转向件的柱子抬 4 像素，瞄准框与碰撞箱都不该从格底开始 */
    @Override
    protected VoxelShape stemShape(BlockState state) {
        return isTurn(state) ? TURN_STEM : STEM;
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
}
