package com.dawnduskterminal.entity;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 西瓜炮与南瓜炮共用的两种果实
 *
 * <p>弹药、炮弹外观与爆裂时的主方块都从这里取，物品与实体两侧因此只有这一处来源。
 * 副粒子（西瓜的火珊瑚块、南瓜的黄色羊毛）与命中音效差别较大，留在实体里按种类分支。
 */
public enum FruitKind {
    MELON("melon", "melongun", Items.MELON, Blocks.MELON),
    PUMPKIN("pumpkin", "pumpkingun", Items.PUMPKIN, Blocks.PUMPKIN);

    private final String id;
    private final String gunId;
    private final Item ammo;
    private final Block block;

    FruitKind(String id, String gunId, Item ammo, Block block) {
        this.id = id;
        this.gunId = gunId;
        this.ammo = ammo;
        this.block = block;
    }

    /** 实体注册名与语言键使用的那一段，例如 {@code melon} */
    public String id() {
        return this.id;
    }

    /** 物品注册名使用的那一段，例如 {@code melongun} */
    public String gunId() {
        return this.gunId;
    }

    /** 发射时消耗的物品 */
    public Item ammo() {
        return this.ammo;
    }

    /** 炮弹渲染与爆裂主粒子使用的方块 */
    public Block block() {
        return this.block;
    }
}
