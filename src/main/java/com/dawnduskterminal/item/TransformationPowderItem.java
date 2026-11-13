package com.dawnduskterminal.item;

import com.dawnduskterminal.entity.TransformationPowderEntity;
import com.dawnduskterminal.registry.ModEntities;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 转换粉：右键扔出去，落点半径 5 格内的方块、群系、活物按另一侧的对照表换一遍
 *
 * <p>投掷那一行照 {@code ThrowablePotionItem#use}：服务器侧建实体、{@code setItem} 带上自己、
 * {@code shootFromRotation(玩家, 俯仰, 偏航, -20°, 0.5, 1.0)} 出手，客户端只回成功。
 * 物理（重力 0.05、空气阻力 0.99、命中判定）都在 {@link TransformationPowderEntity} 与父类里
 *
 * <p>{@code reversed} 决定扔出哪一条实体类型，也就是决定转换的方向：
 * false → 主世界那一侧（强效转换粉），true → 晨昏线那一侧（反转转换粉）
 */
public class TransformationPowderItem extends Item {
    /** 出手时向下偏的仰角，与喷溅药水一致 */
    private static final float SHOOT_ANGLE = -20.0F;

    private static final float SHOOT_POWER = 0.7F;

    private static final float SHOOT_UNCERTAINTY = 1.0F;

    private final boolean reversed;

    public TransformationPowderItem(boolean reversed, Item.Properties properties) {
        super(properties);
        this.reversed = reversed;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            EntityType<? extends TransformationPowderEntity> type = this.reversed
                    ? ModEntities.REVERSED_TRANSFORMATION_POWDER.get()
                    : ModEntities.POTENT_TRANSFORMATION_POWDER.get();
            TransformationPowderEntity powder = new TransformationPowderEntity(type, player, level);
            powder.setItem(stack);
            powder.shootFromRotation(
                    player, player.getXRot(), player.getYRot(), SHOOT_ANGLE, SHOOT_POWER, SHOOT_UNCERTAINTY);
            level.addFreshEntity(powder);
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        stack.consume(1, player);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
