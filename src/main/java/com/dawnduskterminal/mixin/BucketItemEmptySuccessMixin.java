package com.dawnduskterminal.mixin;

import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModScorchingTwilight;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 泼掉流体时还给苍穹罐，而不是铁桶
 *
 * <p>合成与酿造那两条走的是物品栈敏感的剩余物钩子（见 {@code item/CanocanBucketItem}），
 * 但右键把流体泼出去那一条不经它们：{@code BucketItem#use} 直接调静态的
 * {@code getEmptySuccessItem}，里面写死 {@code new ItemStack(Items.BUCKET)}。
 * 静态方法没法按物品覆写，只能在这里接住
 *
 * <p>只认本模组那两桶；创造模式本来就不消耗，保持原样
 */
@Mixin(BucketItem.class)
public abstract class BucketItemEmptySuccessMixin {
    @Inject(method = "getEmptySuccessItem", at = @At("HEAD"), cancellable = true)
    private static void ddt$handBackTheCanopyCan(
            ItemStack stack, Player player, CallbackInfoReturnable<ItemStack> cir) {
        if (player != null
                && !player.hasInfiniteMaterials()
                && (stack.is(ModItems.PORTAL_BUCKET.get()) || stack.is(ModScorchingTwilight.BUCKET.get()))) {
            cir.setReturnValue(new ItemStack(ModItems.CANOCAN.get()));
        }
    }
}
