package com.dawnduskterminal.item;

import com.dawnduskterminal.registry.ModExperimentItems;
import com.dawnduskterminal.registry.ModFluids;
import com.dawnduskterminal.registry.ModScorchingTwilight;
import net.minecraft.core.BlockPos;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import twilightforest.block.TranslucentBuiltBlock;
import twilightforest.block.VanishingBlock;
import twilightforest.init.TFBlocks;

/**
 * 「苍穹罐」是唯一允许舀起晨昏线维度两种特殊流体（portal_fluid 与 enthu_fluid）的容器
 *
 * 分别产出 {@code portal_bucket} 与 {@code enthu_fluid_bucket}，其它流体一律不动
 *
 * <p>它还多一门用处：拿它点暮色森林那两块「正变绿」的方块，可以把方块整个取走，换来一枚
 * {@code experiment_028}。见 {@link #useOn}
 */
public class CanocanItem extends Item {
    public CanocanItem(Properties properties) {
        super(properties);
    }

    /**
     * 点那两块「正变绿」的方块：取走方块，换来一枚试验物品 028
     *
     * <p>两块方块各自的绿色窗口：
     * <ul>
     *   <li>{@code twilightforest:reappearing_block}（重现方块）：被点过之后缩成小方块，临回来之前那 15 tick
     *       是 {@code vanished && active}，模型换成了发绿的那一份。窗口一过它自己长回原样
     *   <li>{@code twilightforest:built_block}（砷铅铁建造器搭出来的方块）：被激活之后 10 tick 是
     *       {@code active}，也是发绿的那一份。窗口一过方块直接消失
     * </ul>
     *
     * <p>两个窗口里方块都已经不属于「正常世界」了：这边把它整个拿掉，换成 028，方块自己的定时逻辑随之作废
     * （重现方块不会长回来，建造方块也不会自己消失）。
     *
     * <p>不是这两块、或者不在窗口里，一律返回 {@code PASS}，苍穹罐照旧走舀流体那条路（见 {@link #use}）
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        boolean greenReappearing = state.is(TFBlocks.REAPPEARING_BLOCK.get())
                && state.getValue(VanishingBlock.VANISHED)
                && state.getValue(VanishingBlock.ACTIVE);
        boolean greenBuilt = state.is(TFBlocks.BUILT_BLOCK.get()) && state.getValue(TranslucentBuiltBlock.ACTIVE);
        if (!greenReappearing && !greenBuilt) {
            return InteractionResult.PASS;
        }
        if (!level.mayInteract(player, pos)) {
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // 取走那一格：先按原版挖掉那一套炸出碎片与音效，客户端看得见方块是被拿走了
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        level.removeBlock(pos, false);
        player.getInventory()
                .placeItemBackInInventory(
                        new ItemStack(ModExperimentItems.textured().get(28).get()));
        player.awardStat(Stats.ITEM_USED.get(this));
        level.gameEvent(player, GameEvent.BLOCK_DESTROY, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(stack);
        }
        BlockPos pos = hit.getBlockPos();
        if (!level.mayInteract(player, pos)) {
            return InteractionResultHolder.fail(stack);
        }
        BlockState blockState = level.getBlockState(pos);
        if (!(blockState.getBlock() instanceof BucketPickup bucketPickup)) {
            return InteractionResultHolder.pass(stack);
        }
        var fluidState = level.getFluidState(pos);
        if (!fluidState.isSource()
                || (fluidState.getType() != ModFluids.PORTAL_FLUID.get()
                        && fluidState.getType() != ModScorchingTwilight.SOURCE.get())) {
            return InteractionResultHolder.pass(stack);
        }
        ItemStack filled = bucketPickup.pickupBlock(player, level, pos, blockState);
        if (filled.isEmpty()) {
            return InteractionResultHolder.fail(stack);
        }
        player.awardStat(Stats.ITEM_USED.get(this));
        bucketPickup.getPickupSound(blockState).ifPresent(sound -> player.playSound(sound, 1.0F, 1.0F));
        level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
        return InteractionResultHolder.sidedSuccess(
                ItemUtils.createFilledResult(stack, player, filled), level.isClientSide());
    }
}
