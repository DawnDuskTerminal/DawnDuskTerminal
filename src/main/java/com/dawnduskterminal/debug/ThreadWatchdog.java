package com.dawnduskterminal.debug;

import com.dawnduskterminal.DawnDuskTerminal;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.server.MinecraftServer;

/**
 * 主线程卡死取证：服务端线程一段时间没有推进 tick，就把全部线程栈写进游戏目录
 *
 * <h2>为什么需要它</h2>
 *
 * <p>原版只在单刻超过两秒时打一行 {@code Can't keep up! Is the server overloaded?}，不带栈。
 * 卡在存档、卡在区块加载等待、卡在某个模组的死锁上时，日志里只剩「Saving and pausing game...」这一句，
 * 事后完全看不出卡在谁的帧里。这个看门狗补的就是那一份栈。
 *
 * <h2>怎么判定「卡死」</h2>
 *
 * <p>服务端每 tick 末尾打一个心跳；看门狗每秒读一次心跳，发现超过阈值没有前进就认定卡住。
 * 存盘也在服务端线程上跑，所以卡存盘同样会触发。
 * 每段卡死只写一份文件：写完之后等心跳重新推进，才重新开始计时，避免把磁盘写满。
 *
 * <h2>怎么打开</h2>
 *
 * <p>系统属性 {@code ddt.watchdogMs}，0 表示关闭（默认）。开发客户端用
 * {@code ./gradlew runClient -Pddt_watchdog=3000} 打开，见 {@code docs/development.md}。
 * 阈值不能低于 1000 毫秒：正常存盘偶尔就要几百毫秒，阈值太低会写出一堆没用的文件
 */
public final class ThreadWatchdog {
    /** 阈值下限（毫秒） */
    private static final long MIN_THRESHOLD_MS = 1000L;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 服务端线程最后一次推进 tick 的时刻（{@link System#nanoTime()}） */
    private static volatile long heartbeat;

    private static volatile boolean running;

    private ThreadWatchdog() {}

    /** 在服务端启动时调用；阈值不合法或已启动时什么也不做 */
    public static void start(MinecraftServer server) {
        long threshold = thresholdMs();
        if (threshold <= 0L || running) {
            return;
        }
        running = true;
        heartbeat = System.nanoTime();
        Thread thread = new Thread(() -> watch(server, threshold), "ddt-thread-watchdog");
        thread.setDaemon(true);
        thread.start();
        DawnDuskTerminal.LOGGER.info(
                "DawnDuskTerminal thread watchdog armed, threshold={}ms, dumps land in the game directory", threshold);
    }

    /** 服务端线程每 tick 末尾调用 */
    public static void heartbeat() {
        if (running) {
            heartbeat = System.nanoTime();
        }
    }

    /** 服务端停止时调用 */
    public static void stop() {
        running = false;
    }

    private static long thresholdMs() {
        return parseThreshold(System.getProperty("ddt.watchdogMs"));
    }

    /**
     * 解析阈值：空串与 0 表示关闭，非法输入关闭并告警，小于下限的抬到下限
     *
     * <p>公开是为了让游戏测试直接断言解析规则，不必真的制造一次卡死
     */
    public static long parseThreshold(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            if (parsed <= 0L) {
                return 0L;
            }
            return Math.max(parsed, MIN_THRESHOLD_MS);
        } catch (NumberFormatException problem) {
            DawnDuskTerminal.LOGGER.warn(
                    "DawnDuskTerminal thread watchdog ignored the malformed ddt.watchdogMs={}", raw);
            return 0L;
        }
    }

    private static void watch(MinecraftServer server, long thresholdMs) {
        while (running && !server.isStopped()) {
            if (!sleep()) {
                return;
            }
            long observed = heartbeat;
            long idleMs = (System.nanoTime() - observed) / 1_000_000L;
            if (idleMs < thresholdMs) {
                continue;
            }
            write(server, idleMs);
            // 同一段卡死只写一次：等心跳重新推进再回到常规轮询
            while (running && !server.isStopped() && heartbeat == observed) {
                if (!sleep()) {
                    return;
                }
            }
        }
    }

    private static void write(MinecraftServer server, long idleMs) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        StringBuilder report = new StringBuilder(16384);
        report.append("DawnDuskTerminal thread dump\n")
                .append("time       : ")
                .append(LocalDateTime.now())
                .append("\nserver tick: stalled for ")
                .append(idleMs)
                .append(" ms\nseed       : ")
                .append(server.overworld() == null ? "?" : server.overworld().getSeed())
                .append("\n\n");
        for (ThreadInfo info : bean.getThreadInfo(bean.getAllThreadIds(), true, true)) {
            if (info == null) {
                continue;
            }
            report.append('"')
                    .append(info.getThreadName())
                    .append("\" id=")
                    .append(info.getThreadId())
                    .append(' ')
                    .append(info.getThreadState())
                    .append('\n');
            for (StackTraceElement element : info.getStackTrace()) {
                report.append("    at ").append(element).append('\n');
            }
            report.append('\n');
        }
        Path target = server.getServerDirectory()
                .resolve("ddt-thread-dump-" + LocalDateTime.now().format(STAMP) + ".txt");
        try {
            Files.writeString(target, report.toString(), StandardCharsets.UTF_8);
        } catch (IOException problem) {
            DawnDuskTerminal.LOGGER.error("DawnDuskTerminal thread watchdog could not write {}", target, problem);
        }
        // 日志里只放服务端线程自己那一段：完整栈在文件里，免得日志被几十个线程刷满
        DawnDuskTerminal.LOGGER.error(
                "DawnDuskTerminal server thread stalled for {}ms, full thread dump at {}\n{}",
                idleMs,
                target,
                serverThreadStacks(bean));
    }

    private static String serverThreadStacks(ThreadMXBean bean) {
        ThreadInfo[] infos = bean.getThreadInfo(bean.getAllThreadIds(), true, true);
        StringBuilder server = new StringBuilder();
        for (ThreadInfo info : infos) {
            if (info == null || !"Server thread".equals(info.getThreadName())) {
                continue;
            }
            server.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
            for (StackTraceElement element : info.getStackTrace()) {
                server.append("\n    at ").append(element);
            }
        }
        return server.isEmpty() ? "(Server thread not found)" : server.toString();
    }

    private static boolean sleep() {
        try {
            Thread.sleep(1000L);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
            return false;
        }
    }
}
