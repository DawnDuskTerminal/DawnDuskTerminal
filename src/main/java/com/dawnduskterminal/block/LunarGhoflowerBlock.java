package com.dawnduskterminal.block;

/**
 * 月鬼花：夜侧的鬼花
 *
 * <p>与昼侧的 {@link GhoflowerBlock} 行为相同，区别只在贴图与掉落的物品。
 * 昼夜本来就是两个方块、两个物品，鬼花与向日葵/月葵同一套拆法。
 */
public class LunarGhoflowerBlock extends GhoflowerBlock {
    public LunarGhoflowerBlock(Properties properties) {
        super(properties);
    }
}
