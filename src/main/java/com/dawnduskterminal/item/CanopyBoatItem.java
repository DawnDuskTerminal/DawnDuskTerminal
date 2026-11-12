package com.dawnduskterminal.item;

import com.dawnduskterminal.entity.CanopyBoatEntity;
import com.dawnduskterminal.registry.ModEntities;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 苍穹船的物品形态：右键把 {@link CanopyBoatEntity} 放到视线命中的那一格上
 *
 * <p>照着原版 {@code BoatItem} 抄的：同样的「视线 5 格内有没有挡住落点的实体」预检、同样的
 * {@code noCollision} 判定、同样的 {@code ENTITY_PLACE} 游戏事件、同样的消耗与统计。
 * 不继承它是因为 {@code BoatItem} 里那两个实体类型写死成 {@code EntityType.BOAT} /
 * {@code EntityType.CHEST_BOAT}，没有给自定义船留口子
 *
 * <p>落点取 {@code ClipContext.Fluid.ANY} 命中的位置：点在水面、岩浆面或不稳定液体面上都能把船放下去
 */
public class CanopyBoatItem extends Item {
    private static final Predicate<Entity> ENTITY_PREDICATE = EntitySelector.NO_SPECTATORS.and(Entity::isPickable);

    /** 视线这么远之内的实体算挡住落点 */
    private static final double REACH = 5.0D;

    public CanopyBoatItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        HitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
        if (hit.getType() == HitResult.Type.MISS) {
            return InteractionResultHolder.pass(stack);
        }
        Vec3 view = player.getViewVector(1.0F);
        List<Entity> blocking = level.getEntities(
                player, player.getBoundingBox().expandTowards(view.scale(REACH)).inflate(1.0D), ENTITY_PREDICATE);
        if (!blocking.isEmpty()) {
            Vec3 eye = player.getEyePosition();
            for (Entity entity : blocking) {
                AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
                if (box.contains(eye)) {
                    return InteractionResultHolder.pass(stack);
                }
            }
        }
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(stack);
        }
        Vec3 location = hit.getLocation();
        CanopyBoatEntity boat = new CanopyBoatEntity(ModEntities.CANOPY_BOAT.get(), level);
        boat.setPos(location.x, location.y, location.z);
        boat.setYRot(player.getYRot());
        if (level instanceof ServerLevel serverLevel) {
            EntityType.createDefaultStackConfig(serverLevel, stack, player).accept(boat);
        }
        if (!level.noCollision(boat, boat.getBoundingBox())) {
            return InteractionResultHolder.fail(stack);
        }
        if (!level.isClientSide) {
            level.addFreshEntity(boat);
            level.gameEvent(player, GameEvent.ENTITY_PLACE, location);
            stack.consume(1, player);
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
