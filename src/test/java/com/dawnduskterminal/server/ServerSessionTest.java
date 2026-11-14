package com.dawnduskterminal.server;

import com.dawnduskterminal.entity.GhastElitePopulation;
import com.dawnduskterminal.entity.GhastFruityPopulation;
import com.dawnduskterminal.world.ChronoLine;
import com.dawnduskterminal.world.ChronoLineState;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/** 在独立进程中检查会话清理，避免修改运行中世界生成线程读取的快照。 */
public final class ServerSessionTest {
    private static final ChronoLine A = new ChronoLine(10, 20, 0, 1);
    private static final ChronoLine B = new ChronoLine(-10, -20, -1, 0);

    private ServerSessionTest() {}

    public static void main(String[] args) throws Exception {
        check(!ChronoLineState.hasServerLine(), "初始服务端快照应为空");
        ChronoLineState.setClientLine(B);
        ChronoLineState.setServerLine(A);
        ChronoLineState.resetServerLine();
        check(ChronoLineState.clientLine().equals(B), "服务端清理不得影响客户端快照");
        ChronoLineState.resetClientLine();
        ChronoLineState.resetServerLine();
        check(ChronoLineState.clientLine().equals(ChronoLine.fallback()), "重复清理后应使用兜底值");

        Map<Object, Object> elite = snapshots(GhastElitePopulation.class);
        Map<Object, Object> fruity = snapshots(GhastFruityPopulation.class);
        for (ChronoLine line : new ChronoLine[] {A, B}) {
            DdtServerEvents.onServerAboutToStart(new ServerAboutToStartEvent(null));
            ChronoLineState.setServerLine(line);
            check(ChronoLineState.clientLine().equals(line), "客户端空快照应回退到当前会话");
            // 清理只依赖映射内容，不依赖私有 Snapshot 的布局。
            elite.put(null, null);
            fruity.put(null, null);
            DdtServerEvents.finishServerSession(false);
            check(elite.isEmpty() && fruity.isEmpty(), "停服应清空两个实体容量缓存");
            check(!ChronoLineState.hasServerLine(), "停服应清空服务端快照");
            check(ChronoLineState.clientLine().equals(ChronoLine.fallback()), "客户端不得读取上一会话快照");
        }
        DdtServerEvents.finishServerSession(false);
        System.out.println("PASS: 正常会话 A、B 及重复清理");

        ChronoLineState.setServerLine(A);
        elite.put(null, null);
        fruity.put(null, null);
        if (args.length == 0) {
            DdtServerEvents.finishServerSession(true);
        } else {
            // 无效服务器引用用于确定性注入探测失败，不需要构造或关闭真实世界。
            DdtServerEvents.finishServerSession((MinecraftServer) null);
        }
        check(ChronoLineState.serverLine().equals(A), "无法确认任务结束时必须保留快照");
        check(!elite.isEmpty() && !fruity.isEmpty(), "无法确认任务结束时必须保留容量缓存");
        try {
            DdtServerEvents.onServerAboutToStart(new ServerAboutToStartEvent(null));
            throw new AssertionError("异常停服后必须拒绝新会话");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("Restart"), "拒绝启动时应说明需要重启");
        }
        // 被拒绝的新服务器也会发送停止事件，不能因此解除锁定或清除旧快照。
        DdtServerEvents.finishServerSession(false);
        check(ChronoLineState.serverLine().equals(A), "后续停止事件不得清除仍被旧任务使用的快照");
        try {
            DdtServerEvents.onServerAboutToStart(new ServerAboutToStartEvent(null));
            throw new AssertionError("锁定不得在当前进程内解除");
        } catch (IllegalStateException expected) {
            System.out.println("PASS: 异常停服保留状态并持续拒绝新会话");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> snapshots(Class<?> population) throws ReflectiveOperationException {
        Field field = population.getDeclaredField("SNAPSHOTS");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
