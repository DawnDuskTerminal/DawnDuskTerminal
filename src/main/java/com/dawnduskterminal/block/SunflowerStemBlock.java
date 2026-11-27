package com.dawnduskterminal.block;

/**
 * 向日葵的茎：只有柱身，没有花盘，也没有独立的物品
 *
 * <p>茎与花盘是两种方块；玩家破坏茎时由父类按整株结算，掉的是顶段的物品，茎本身不掉。
 *
 * <p>茎的 {@code HEAD} 恒为 {@code false}，几何走 16 像素的整格柱身。
 */
public class SunflowerStemBlock extends AbstractSunflowerBlock {
    public SunflowerStemBlock(Properties properties) {
        super(properties);
    }
}
