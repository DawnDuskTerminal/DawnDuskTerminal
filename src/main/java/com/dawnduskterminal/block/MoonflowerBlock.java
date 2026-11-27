package com.dawnduskterminal.block;

/**
 * 月葵：夜侧的向日葵
 *
 * <p>与 {@link SunflowerBlock} 完全相同的行为与几何，区别只在于它出现在晨昏线的夜侧，
 * 渲染时固定使用夜版贴图。
 *
 * <p>两侧拆成两个方块、两个物品，是为了将来任意一边要独立的东西（掉落、名字、外观、行为）时
 * 不必再动另一边，也不必按位置去猜「这一株算哪边」。
 */
public class MoonflowerBlock extends AbstractSunflowerBlock {
    public MoonflowerBlock(Properties properties) {
        super(properties);
    }
}
