package com.dawnduskterminal.block;

/**
 * 月葵的茎：与 {@link SunflowerStemBlock} 同构，只出现在夜侧，固定用夜版贴图
 *
 * <p>玩家破坏茎时由父类按整株结算，掉的是顶段的物品，茎本身不掉；几何走 16 像素的整格柱身。
 */
public class MoonflowerStemBlock extends AbstractSunflowerBlock {
    public MoonflowerStemBlock(Properties properties) {
        super(properties);
    }
}
