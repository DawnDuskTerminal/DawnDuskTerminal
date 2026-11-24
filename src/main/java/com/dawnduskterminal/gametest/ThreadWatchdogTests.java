package com.dawnduskterminal.gametest;

import com.dawnduskterminal.debug.ThreadWatchdog;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 主线程看门狗：阈值解析规则与启动条件
 *
 * <p>卡死本身只能在真实卡死里验证，这里钉住的是可离线断言的那一半：
 * 阈值怎么解析、什么情况下才允许起线程
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class ThreadWatchdogTests {
    private static final String THREAD_NAME = "ddt-thread-watchdog";

    @GameTest(template = "ddt_empty", batch = "ddt_watchdog")
    public static void thresholdParsing(GameTestHelper helper) {
        helper.assertTrue(ThreadWatchdog.parseThreshold(null) == 0L, "没设置时必须是关闭");
        helper.assertTrue(ThreadWatchdog.parseThreshold("") == 0L, "空串必须是关闭");
        helper.assertTrue(ThreadWatchdog.parseThreshold("   ") == 0L, "全空白必须是关闭");
        helper.assertTrue(ThreadWatchdog.parseThreshold("0") == 0L, "0 必须是关闭");
        helper.assertTrue(ThreadWatchdog.parseThreshold("-500") == 0L, "负数必须是关闭");
        helper.assertTrue(ThreadWatchdog.parseThreshold("abc") == 0L, "非法输入必须是关闭");
        helper.assertTrue(ThreadWatchdog.parseThreshold(" 2500 ") == 2500L, "合法数值必须原样保留");
        helper.assertTrue(ThreadWatchdog.parseThreshold("10") == 1000L, "低于下限的必须抬到 1000 毫秒");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ddt_watchdog")
    public static void armingNeedsAThreshold(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String previous = System.getProperty("ddt.watchdogMs");
        try {
            System.clearProperty("ddt.watchdogMs");
            ThreadWatchdog.start(server);
            helper.assertFalse(watchdogIsRunning(), "没设置阈值时不得起线程");
            System.setProperty("ddt.watchdogMs", "1500");
            ThreadWatchdog.start(server);
            helper.assertTrue(watchdogIsRunning(), "设置了阈值后必须起线程");
        } finally {
            ThreadWatchdog.stop();
            if (previous == null) {
                System.clearProperty("ddt.watchdogMs");
            } else {
                System.setProperty("ddt.watchdogMs", previous);
            }
        }
        helper.succeed();
    }

    private static boolean watchdogIsRunning() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> THREAD_NAME.equals(thread.getName()) && thread.isAlive());
    }
}
