package com.dawnduskterminal.item;

import com.dawnduskterminal.entity.GhoflowerProjectile;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * 鬼花武器：手持右键打一发，冷却 2 秒
 *
 * <p>昼侧与夜侧各有一个物品（{@code ghoflower} 与 {@code lunarghoflower}），
 * 射弹的昼夜版本由物品本身决定。
 *
 * <p>弹药吃一切向日葵：原版 {@code minecraft:sunflower}、{@code sunflower}、{@code moonflower}。
 * 创造模式不检查弹药、也不消耗，但仍然进入冷却。
 *
 * <p>它是纯武器，<b>不放置方块</b>：右键方块与右键空气走同一条路，失败时返回 {@code PASS}，不会退回成放置。
 */
public class GhoflowerItem extends BlockItem {
    /** 冷却 2 秒 */
    public static final int COOLDOWN_TICKS = 2 * 20;

    /** 射弹飞行距离，格 */
    private static final double RANGE = 64.0D;

    private final boolean night;

    public GhoflowerItem(Block block, boolean night, Properties properties) {
        super(block, properties);
        this.night = night;
    }

    @Override
    public String getDescriptionId() {
        return "item.dawnduskterminal." + (this.night ? "lunarghoflower" : "ghoflower");
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return fire(level, player) ? InteractionResultHolder.consume(stack) : InteractionResultHolder.pass(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        return fire(context.getLevel(), player) ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    private boolean fire(Level level, Player player) {
        if (player.getCooldowns().isOnCooldown(this)) {
            return false;
        }
        boolean creative = player.isCreative();
        ItemStack ammo = creative ? ItemStack.EMPTY : findAmmo(player);
        if (!creative && ammo.isEmpty()) {
            return false;
        }
        if (level instanceof ServerLevel server) {
            if (!creative) {
                ammo.shrink(1);
            }
            Vec3 from = player.getEyePosition(1.0F);
            Vec3 to = from.add(player.getLookAngle().scale(RANGE));
            // 射弹跟着物品走：鬼花物品打昼版，月鬼花物品打夜版
            GhoflowerProjectile.spawn(server, from, to, player, this.night);
            server.playSound(null, from.x, from.y, from.z, SoundEvents.GRAVEL_STEP, SoundSource.HOSTILE, 0.8F, 1.0F);
        }
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        return true;
    }

    private static ItemStack findAmmo(Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()
                    && (stack.is(Items.SUNFLOWER)
                            || stack.is(ModTerrainBlocks.SUNFLOWER_ITEM.get())
                            || stack.is(ModTerrainBlocks.MOONFLOWER_ITEM.get()))) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
