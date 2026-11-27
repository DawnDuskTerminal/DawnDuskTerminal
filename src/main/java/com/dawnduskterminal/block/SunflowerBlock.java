package com.dawnduskterminal.block;

/**
 * 普通的向日葵：1~3 格，每格一个方块
 *
 * <p>依附、掉落时机、瞄准框、方块实体都在 {@link AbstractSunflowerBlock} 里；
 * 这个类不额外做任何事，存在只是为了让「普通向日葵」在注册表里是独立的一项。
 *
 * <p>被玩家破坏任意一段时按整株掉一件，见父类的 {@code onDestroyedByPlayer}。
 */
public class SunflowerBlock extends AbstractSunflowerBlock {
    public SunflowerBlock(Properties properties) {
        super(properties);
    }
}
