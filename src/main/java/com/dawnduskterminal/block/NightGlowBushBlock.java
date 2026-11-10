package com.dawnduskterminal.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 夜光灌木：蓝色沙漠里的一丛小灌木，贴图是枯死的灌木调成仙灵石竹的配色
 *
 * <p>它不生长：没有 {@code randomTicks()}，也不实现 {@code BonemealableBlock}。
 * 摆放条件与原版枯死的灌木完全一致，取的是同一个方块标签 {@link BlockTags#DEAD_BUSH_MAY_PLACE_ON}，
 * 因此沙、陶瓦、土之类的底面都能托住它；脚下的方块消失时它随之消失（{@code BushBlock} 的既有行为）。
 *
 * <p>光照 7，并且贴图满亮度渲染（属性里的 {@code emissiveRendering}），所以它在夜里也是一丛亮着的灌木。
 */
public class NightGlowBushBlock extends BushBlock {
    /** 光照等级 */
    public static final int LIGHT = 7;

    public static final MapCodec<NightGlowBushBlock> CODEC = simpleCodec(NightGlowBushBlock::new);

    public NightGlowBushBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<NightGlowBushBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.is(BlockTags.DEAD_BUSH_MAY_PLACE_ON);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
