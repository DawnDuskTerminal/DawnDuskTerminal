package com.dawnduskterminal.mixin;

import com.dawnduskterminal.world.FluidReactionGate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 转换粉批量改方块期间，流体接触反应一律判否
 *
 * <p>{@code FluidInteractionRegistry#canInteract} 是所有流体接触反应唯一的判定口：{@code LiquidBlock#onPlace}
 * 与 {@code LiquidBlock#neighborChanged} 都调它，反应本身（换成黑曜石、脆弱的苍穹之类）也在它里面做完。
 * 于是批量改方块时在 HEAD 处直接回 {@code false}，就能让「新流体挨着旧流体」的那一瞬间不算数；
 * 改完之后由 {@code world/TransformationBlast} 只对球壳那几格显式再判一次，范围外的液体照常反应
 *
 * <p>为什么不能靠 {@code Level#setBlock} 的 flags 绕开：{@code LevelChunk#setBlockState} 在写完方块之后
 * 无条件调 {@code BlockState#onPlace}，与 flags 无关，所以任何「把方块写进去」的路径都会触发一次判定
 */
@Mixin(FluidInteractionRegistry.class)
public abstract class FluidReactionSuppressMixin {
    @Inject(method = "canInteract", at = @At("HEAD"), cancellable = true)
    private static void ddt$suppressDuringBatchEdit(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FluidReactionGate.suppressed()) {
            cir.setReturnValue(false);
        }
    }
}
