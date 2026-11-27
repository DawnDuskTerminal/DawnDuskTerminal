package com.dawnduskterminal.entity;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 西瓜炮与南瓜炮共用的两种果实
 *
 * <p>弹药、炮弹外观、爆裂时的主方块与全部战斗数值都从这里取，物品与实体两侧因此只有这一处来源。
 * 副粒子（西瓜的火珊瑚块、南瓜的黄色羊毛）与命中音效差别较大，留在实体里按种类分支。
 *
 * <p>两种炮弹的定位由 {@link Stats} 分开，差别来自果实的软硬：西瓜软，命中时容易裂开并留下大范围，
 * 撞上没爆裂的目标时被甩得更偏、损失更多速度；南瓜硬，直射重、范围小，命中后不易爆裂，
 * 击退更强，自身偏转小、掉速少，撞方块还有机会弹开。
 */
public enum FruitKind {
    MELON(
            "melon",
            "melongun",
            Items.MELON,
            Blocks.MELON,
            new Stats(10.0F, 6.0F, 3.5D, 0.6D, 0.1D, 1.0D, 40.0D, 0.3D, 0.0D)),
    PUMPKIN(
            "pumpkin",
            "pumpkingun",
            Items.PUMPKIN,
            Blocks.PUMPKIN,
            new Stats(16.0F, 2.0F, 2.5D, 1.2D, 0.25D, 1.5D, 25.0D, 0.15D, 0.6D));

    private final String id;
    private final String gunId;
    private final Item ammo;
    private final Block block;
    private final Stats stats;

    FruitKind(String id, String gunId, Item ammo, Block block, Stats stats) {
        this.id = id;
        this.gunId = gunId;
        this.ammo = ammo;
        this.block = block;
        this.stats = stats;
    }

    /**
     * 一种炮弹的战斗数值
     *
     * @param directDamage 命中活物的直射伤害
     * @param areaDamage 爆裂时的范围伤害
     * @param areaRadius 范围伤害半径，单位格
     * @param punchStrength 触发击退时沿弹道加给目标的水平速度
     * @param punchLift 触发击退时加给目标的竖直速度
     * @param knockbackScale 不爆裂（改走击退）的概率系数，实际概率是它除以目标身高
     * @param deflectDegreesAtTwoBlocks 2 格身高目标的偏转上限，单位度，按身高线性放大
     * @param speedLossAtTwoBlocks 2 格身高目标让炮弹损失的速度比例，按身高线性放大
     * @param blockBounceChance 命中方块时弹性反射而不爆裂的概率，0 表示必定爆裂
     */
    public record Stats(
            float directDamage,
            float areaDamage,
            double areaRadius,
            double punchStrength,
            double punchLift,
            double knockbackScale,
            double deflectDegreesAtTwoBlocks,
            double speedLossAtTwoBlocks,
            double blockBounceChance) {}

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

    /** 这一种炮弹的战斗数值 */
    public Stats stats() {
        return this.stats;
    }
}
