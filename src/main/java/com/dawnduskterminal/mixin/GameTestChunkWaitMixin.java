package com.dawnduskterminal.mixin;

import com.dawnduskterminal.DawnDuskTerminal;
import java.util.Collection;
import net.minecraft.Util;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestTimeoutException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 测试区块等待实体刻资格超时后明确失败，避免跳过准备条件或无限等待。
 *
 * <p>原版只在区块就绪后推进测试超时，因此此处另设墙钟期限。
 * 注入点位于原版刻的末尾，使刚刚就绪的区块优先进入正常执行路径。
 * 等待超时必须通知监听器，否则后续测试批次仍无法启动。
 */
@Mixin(GameTestInfo.class)
public abstract class GameTestChunkWaitMixin {
    /** 区块等待期限使用墙钟毫秒，不受测试服务器的刻速率影响。 */
    @Unique
    private static final long DDT$GRACE_MILLIS = 5000L;

    @Shadow
    private boolean chunksLoaded;

    @Shadow
    @Final
    private Collection<GameTestListener> listeners;

    @Unique
    private long ddt$firstTickMillis = -1L;

    @Inject(method = "tick", at = @At("RETURN"))
    private void ddt$failWaitingForEntityTicking(GameTestRunner runner, CallbackInfo ci) {
        GameTestInfo self = (GameTestInfo) (Object) this;
        if (this.chunksLoaded || self.isDone()) {
            return;
        }
        long now = Util.getMillis();
        if (this.ddt$firstTickMillis < 0L) {
            this.ddt$firstTickMillis = now;
            return;
        }
        if (now - this.ddt$firstTickMillis < DDT$GRACE_MILLIS) {
            return;
        }
        long waited = now - this.ddt$firstTickMillis;
        self.fail(new GameTestTimeoutException(
                "Chunks did not enter entity ticking within " + waited + " ms; refusing to run " + self.getTestName()));
        DawnDuskTerminal.LOGGER.error(
                "DawnDuskTerminal game test {} failed after waiting {} ms for entity-ticking chunks",
                self.getTestName(),
                waited);
        this.listeners.forEach(listener -> listener.testFailed(self, runner));
    }
}
