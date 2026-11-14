# 动画表达式校验（animcheck）

校验器使用运行时绑定表解析动画片段，并严格检查每条 Molang 表达式。
运行时的 `MolangParser#parseExpression` 会在解析失败时返回零值，不能直接作为失败门禁。
校验器额外调用 `parseExpressionUnsafe`，不改变运行时的容错语义。

## 运行

在仓库根目录执行以下命令；Gradle 会自动编译所需源码。

Linux 与 macOS：

```bash
./gradlew animationCheck
./gradlew animationCheckTest
```

Windows PowerShell：

```powershell
.\gradlew.bat animationCheck
.\gradlew.bat animationCheckTest
```

任务复用工程的 Java 21 toolchain 与 ModDevGradle 提供的客户端编译类路径。
无需指定 JDK 安装路径、NeoForge jar 版本或类路径分隔符，也无需启动客户端。
工具源码独立编译至 `build/classes/java/animcheck/`，不进入玩家 jar。
这两个任务仅作为本地入口，不接入默认 `check` 或 CI。

## 输入与覆盖范围

`animationCheck` 按路径排序，收集主资源集中的下列文件：

- `assets/dawnduskterminal/animations/**/*.json`。
- `assets/dawnduskterminal/ysm/**/animations/**/*.json`。

YSM 包中的兼容动画也纳入检查，不仅限于运行时合并的 `main` 与 `extra`。
文件收集由 Gradle 完成，不依赖 shell 通配符。
每个文件均按运行时方式反序列化动画片段。
严格表达式检查覆盖 `bones.*.{rotation,position,scale}` 与 `timeline`。
`lerp_mode` 的值是插值模式名称，不作为表达式检查。
几何、贴图和实际渲染效果不在本工具的检查范围内。

## 退出码与诊断

`Check` 仅在输入非空、每个文件至少包含一个动画片段且全部检查通过时返回 0。
表达式或片段解析失败、未提供文件、文件没有动画片段时返回 1。
文件读取、JSON 解析或类路径错误同样以非零退出码结束。
仅使用数字通道的动画无需包含字符串表达式。

输出包含逐文件统计，以及文件、片段、表达式三类汇总。
出现 `first bad expr` 时，检查对应语法及 `ysm.*`、`fn.*`、`query.*` 绑定。
未知绑定不能通过排除动画文件或修改模型原件规避，必须先确认处理策略。

`animationCheckTest` 使用真实子进程验证正常、未知绑定、错误表达式与空输入场景。
检查还覆盖 timeline、片段反序列化失败以及错误文件后的正常文件诊断。
样例、启动参数与日志仅保留在 `build/animcheck-test/`，不进入资源目录或产物。
