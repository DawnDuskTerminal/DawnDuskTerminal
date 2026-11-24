package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.HuiArmor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
abstract class HuiMenuMixin {
    /** 光标与背包的物品转移完成后，才可结算玩家死亡与掉落。 */
    @Inject(method = "clicked", at = @At("RETURN"))
    private void ddt$checkArmorAfterClick(int slot, int button, ClickType click, Player player, CallbackInfo ci) {
        if (!player.level().isClientSide) HuiArmor.checkEquipment(player);
    }
}
