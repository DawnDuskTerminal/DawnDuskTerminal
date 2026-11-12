package com.dawnduskterminal.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

/**
 * 火箭机弩每发出弹那一下的「物品被使用」动画
 *
 * <p>就是原版每次成功使用物品时 {@code Minecraft#startUseItem} 调的那一句
 * {@code gameRenderer.itemInHandRenderer.itemUsed(hand)}：把 {@code mainHandHeight} 打回 0，
 * 物品先往下沉一下再由装备回弹那套逻辑升回去。项目里那些按一下就走完的物品都是这条动画，
 * 与 {@code Player#swing} 的挥手不是一回事
 *
 * <p>只在客户端调用，{@code FireworkMachineBowItem#onUseTick} 用 {@code level.isClientSide} 挡住服务端
 */
public final class FireworkMachineBowBob {
    private FireworkMachineBowBob() {}

    public static void play(InteractionHand hand) {
        Minecraft.getInstance().gameRenderer.itemInHandRenderer.itemUsed(hand);
    }
}
