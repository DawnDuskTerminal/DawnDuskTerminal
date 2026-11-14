import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 使用真实子进程检查退出码；样例与日志只写入构建目录。 */
public final class CheckTest {
    private static final String ANIMATION =
            """
            {"format_version":"1.8.0","animations":{"test":{
              "loop":true,"bones":{"root":{"rotation":[0,"%s",0]}},
              "timeline":{"0.0":"%s"}
            }}}
            """;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createDirectories(Path.of(args[0]));
        // 参数文件避免 Windows 命令行长度限制，也覆盖包含空格的路径。
        Path launcher = dir.resolve("launcher args.txt");
        String classpath =
                System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"");
        Files.writeString(launcher, "--class-path\n\"" + classpath + "\"\nCheck\n", StandardCharsets.UTF_8);

        Path valid = sample(dir, "valid animation", "math.sin(query.life_time)", "v.test=ysm.head_yaw;");
        Path unknown = sample(dir, "unknown", "ysm.animcheck_missing_binding", "v.test=1;");
        Path malformed = sample(dir, "malformed", "1 +", "v.test=1;");
        Path timeline = sample(dir, "timeline", "1", "fn.animcheck_missing_binding();");
        Path badClip = dir.resolve("bad-clip.json");
        Files.writeString(badClip, "{\"animations\":{\"test\":{\"animation_length\":\"invalid\"}}}");
        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, "{\"animations\":{}}");

        List<String> failures = new ArrayList<>();
        check(dir, launcher, "valid", true, "exprFailures=0", failures, valid);
        check(dir, launcher, "unknown", false, "first bad expr", failures, unknown);
        check(dir, launcher, "malformed", false, "first bad expr", failures, malformed);
        check(dir, launcher, "timeline", false, "first bad expr", failures, timeline);
        check(dir, launcher, "clip", false, "clipFailures=1", failures, badClip);
        check(dir, launcher, "no-files", false, "No animation files", failures);
        check(dir, launcher, "empty-file", false, "No animation clips", failures, empty);
        check(dir, launcher, "mixed", false, valid.getFileName().toString(), failures, unknown, valid);
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n", failures));
        }
    }

    private static Path sample(Path dir, String name, String rotation, String timeline) throws Exception {
        Path file = dir.resolve(name + ".json");
        Files.writeString(file, ANIMATION.formatted(rotation, timeline), StandardCharsets.UTF_8);
        return file;
    }

    private static void check(
            Path dir,
            Path launcher,
            String name,
            boolean success,
            String diagnostic,
            List<String> failures,
            Path... files)
            throws Exception {
        String executable = ProcessHandle.current().info().command().orElseThrow();
        List<String> command = new ArrayList<>(List.of(executable, "@" + launcher));
        for (Path file : files) {
            command.add(file.toString());
        }
        Path log = dir.resolve(name + ".log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(name + ": 校验进程超时，日志：" + log);
        }
        int exit = process.exitValue();
        String output = Files.readString(log, StandardCharsets.UTF_8);
        if ((exit == 0) != success || !output.contains(diagnostic)) {
            failures.add(name + ": exit=" + exit + ", expectedSuccess=" + success + "\n" + output);
        } else {
            System.out.println("PASS " + name + ": exit=" + exit);
        }
    }
}
