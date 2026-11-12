package com.dawnduskterminal.mixin;

import com.dawnduskterminal.entity.CanopyBoatEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 坐在苍穹船上的人不吃岩浆那一套
 *
 * <p>岩浆对<em>所有</em>实体都是同一条路：{@code Entity#baseTick} 里「在岩浆里」就调 {@code lavaHurt}，
 * 而 {@code lavaHurt} 只问「免疫不免疫火」——不免疫就点燃 15 秒、再挨 4 点 {@code lava} 伤害。
 * 船本身靠实体类型的 {@code fireImmune()} 挡住了这一条，乘客可没有：船浮在液面上时乘客脚底只比液面高
 * 零点零几格，船一晃、或者液体在流动格上液面更低，乘客就泡进去了
 *
 * <p>所以这里直接按「坐在谁身上」判：驾驶的是 {@link CanopyBoatEntity} 就整个跳过 {@code lavaHurt}，
 * 既不点燃也不掉血。别的船、别的交通工具一概照旧
 *
 * <p>不稳定液体那一路不在 {@code lavaHurt} 里，它在 {@code PortalBlock#entityInside}，那边自己有判据
 */
@Mixin(Entity.class)
public abstract class CanopyBoatRiderMixin {
    @Inject(method = "lavaHurt", at = @At("HEAD"), cancellable = true)
    private void ddt$boatProtectsItsRider(CallbackInfo ci) {
        if (((Entity) (Object) this).getVehicle() instanceof CanopyBoatEntity) {
            ci.cancel();
        }
    }
}
