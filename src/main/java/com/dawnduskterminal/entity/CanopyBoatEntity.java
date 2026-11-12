package com.dawnduskterminal.entity;

import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.world.BoatFloatingFluid;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;

/**
 * 「苍穹船」
 *
 * <p>整条船的行为都是原版 {@link Boat} 的：漂浮、划水、上下客、损坏计次、拾取提示全在父类里，
 * 这里只改三处：
 *
 * <ul>
 *   <li><b>外形</b>：变体固定 {@link Boat.Type#BAMBOO}。竹筏用的那一套模型与 128x64 的贴图布局就是
 *       {@code textures/entity/boat/canopy_boat.png} 照着改色的那张，
 *       所以渲染时直接拿竹筏的 {@code RaftModel}，只把贴图换成自己的（见 {@code client/CanopyBoatRenderer}）</li>
 *   <li><b>掉什么</b>：父类按变体掉竹筏，这里换成自己</li>
 *   <li><b>浮在什么上</b>：{@link #canBoatInFluid(FluidState)}，见下面</li>
 * </ul>
 *
 * <p>漂浮判定不用碰那些 {@code private} 方法：NeoForge 的 {@code IBoatExtension} 已经把原版那三处
 * {@code FluidTags.WATER} 抽成了一个 {@code canBoatInFluid(FluidState)} 钩子（默认实现是
 * {@code FluidState#supportsBoating}），覆写它就把水、岩浆与 {@code portal_fluid} 都算进来；
 * 判定本身在 {@link BoatFloatingFluid}
 *
 * <p><b>免火是岩浆那一条的前提</b>：岩浆里的非生物实体照样走 {@code Entity#lavaHurt}——点燃 15 秒、
 * 再挨 4 点 {@code lava} 伤害，而船的耐久只有 40 点，十来个 tick 就被 {@code destroy()} 了。
 * 实体类型的 {@code fireImmune()} 关掉这一整条路，见 {@code ModEntities#CANOPY_BOAT}。
 * 坐在上面的人不用另做保护：船浮在液面上，乘客脚底在液面之上，{@code isInLava} 不会成立
 */
public class CanopyBoatEntity extends Boat {
    public CanopyBoatEntity(EntityType<? extends CanopyBoatEntity> type, Level level) {
        super(type, level);
        // 造型与碰撞箱都跟着竹筏走，见上面的类注释
        this.setVariant(Boat.Type.BAMBOO);
    }

    @Override
    public Item getDropItem() {
        return ModItems.CANOPY_BOAT.get();
    }

    @Override
    public boolean canBoatInFluid(FluidState fluid) {
        return BoatFloatingFluid.floatsBoat(fluid);
    }
}
