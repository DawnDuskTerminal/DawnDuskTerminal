# 开发与构建

## 环境

本项目的开发环境使用 JDK 21。

构建时使用仓库自带的 `gradlew`，并须保持该文件的可执行权限位。

目标游戏版本为 Minecraft 1.21.1。

NeoForge 与 ModDevGradle 的版本分别记录在 `gradle.properties` 与 `settings.gradle` 中。

## 暮色森林依赖

运行环境必须安装暮色森林 1.21.1。

构建期由 CurseMaven 拉取该依赖，坐标为 `curse.maven:the-twilight-forest-227639:7797302`，对应 1.21.1 所使用的 4.8.3345。

暮色森林自带的 modmaven 已于 2024-07-06 停止更新，该源上的最新版本为 4.5.2428。
而 1.21.1 所使用的版本在该源上只有空目录，因此只能使用 CurseMaven 这个第三方代理；
为此 `build.gradle` 使用 `exclusiveContent` 将 `curse.maven` 这一组仓库单独摘出。

暮色森林是必装依赖，Java 代码直接引用其类型：`TFEntities`、`TFBlocks`、`TFSounds`、`TFConfig` 等常量与相应实体类。
签名一旦变化，编译期就会暴露。
构建期需要联网从 CurseMaven 拉包；
只有编译期没有常量的数据 ID 仍是字符串：
`data/` 下的战利品表、配方、标签与群系，以及 Java 里引用的方块标签、群系标签、配置特征与结构 id。

## Bedrock 动画运行时

本模组自带动画与 Molang 运行时，不依赖 GeckoLib，也不要求玩家安装任何前置。

运行时分两块：`geckolib3/` 是几何、关键帧、控制器与渲染，`molang/` 是词法、语法与求值。
两者都来自
TouhouLittleMaid（MIT），与上游的差异、以及为什么这样选，见 `docs/ysm.md`。

模型包按 Yes Steve Model 的原样布局放在 `assets/dawnduskterminal/ysm/<模型包>/`，运行时解析；
新增模型包的步骤同样见 `docs/ysm.md`。

## 开发用的客户端模组

`runClient` 会额外加载这些模组：
JEI、拼音搜索（Just Enough Characters）、Xaero 小地图与 Xaero 世界地图、Jade、Chunky、Zume、
Tweakerge（带前置 MaFgLib）、AppleSkin、BoccHUD 与 OpenYSM。
上述模组只用于开发阶段：查看配方、地图与方块信息，预生成区块，缩放，调整 tweak，查看饱食度与状态条，
以及把 OpenYSM 渲染的原始 Bedrock 模型与本模组的实现做视觉对拍。
这些模组不是本模组的依赖，既不参与编译，也不会进入产物。

JEI 与 Xaero 使用各自的官方 maven；
拼音搜索与 OpenYSM 使用 CurseMaven（坐标为 `curse.maven:just-enough-characters-250702:9163765`，对应 4.5.30；
以及 `curse.maven:openysm-unofficial-neoforge-port-1587175:8384243`，对应 2.6.6）；
Jade、Chunky、Zume、Tweakerge、MaFgLib、AppleSkin 与 BoccHUD 使用 Modrinth maven。
坐标形式为 `maven.modrinth:<项目 slug>:<版本号>`。
各模组的版本写在 `gradle.properties` 中。

Chunky 在 Modrinth 上为 1.4.23 发布了 fabric、forge 与 neoforge 三条版本号相同的文件，
若按版本号取用则会拿到 forge 那一条，FML 只会打印一行 `Skipping jar ... is for Minecraft Forge ...` 并将其静默跳过；
因此 `chunky_version` 填写的是 neoforge 那一条的版本 id `LuFhm4eU`。

上述模组统一放在 `clientDevMods` 这个 configuration 中。
挂载方式为 `tasks.named('runClient') { classpathProvider.from(...) }`，只影响客户端运行的 classpath；
`runServer`、`runData` 与 `runGameTestServer` 的 classpath 中不包含这些模组。

配置过程中曾遇到三个问题。
其一，若在 run 的配置块中写 `dependencies { runtimeOnly ... }`，依赖会落到工程级的 `runtimeOnly` 上，
导致所有 run 都会带上它；
其二，run 的 `additionalRuntimeClasspathConfiguration` 只会写进 `legacyClasspath.txt`。
FML 不会把它当作模组扫描，因此在游戏中根本不可见；
其三，run 的 `classpath` 在任务执行时才从 `classpathProvider` 取值，若直接向 `classpath` 添加条目，该条目会被覆盖。

Xaero 的两个依赖都需要 `xaero.lib`，该依赖由它们的 pom 传递带上；
JEI 所需的 `mezz_config` 以 jar-in-jar 形式自带在 JEI 的 jar 中，`net.mezzdev.config` 这个坐标指向它的库而非模组；
Modrinth 的 pom 只包含元信息，不携带依赖，所以 Tweakerge 的前置 `mafglib` 在 `clientDevMods` 里显式写了一条。
BoccHUD 0.4.1 的前置同样是 MaFgLib，同一条即可同时满足两者。

Voxy 与 MiniHUD、Tweakeroo 在 1.21.1 上都没有 NeoForge 构建。
Voxy 官方只发布 fabric 版本，最早的版本为 1.20.4，此后直接跳到 1.21.6。
如需使用，须自行把 jar 放进 `run/client/mods`。

OpenYSM 在 1.21.1 NeoForge 上没有官方构建。
官方 Modrinth 项目只发布 1.20.1 的 forge 与 fabric 版本，早先的非官方 Modrinth 页面已经下架，
只剩 CurseForge 上的第三方移植 1587175，即此处使用的这条坐标。

OpenYSM 自带一份分叉的 GeckoLib，包名在 `org.openysm.geckolib3` 与 `com/elfmcys/yesstevemodel/geckolib3` 之下。
本模组也自带一份，两者包名不冲突，因此可以同时装在开发环境里做对拍。
它的 mixin 只涉及原版以及 parcool、create、slashblade 这几个可选模组。

## 构建与运行

`./gradlew build` 用于构建，产物位于 `build/libs/`。

`./gradlew runClient` 的运行目录为 `run/client`。

`./gradlew runServer` 的运行目录为 `run/server`。

`./gradlew runData` 的运行目录为 `run/data`，输出到 `src/generated/resources`。

`./gradlew runGameTestServer` 的运行目录为 `run/gametest`。

默认跑本模组全部用例。
`neoforge.enabledGameTestNamespaces` 为 `dawnduskterminal,dawnduskterminal057`。
改了哪一件东西就只跑那一批，不必把整模组的用例全跑一遍：

```bash
./gradlew runGameTestServer -Pgametest_namespaces=dawnduskterminal057
```

分批的依据是**模板命名空间**：NeoForge 只按这个命名空间过滤用例，没有按方法名过滤的口子。
因此把某一批用例的 `@GameTestHolder` 换成自己的命名空间，例如 057 那批用 `dawnduskterminal057`。
再把要用的模板结构同名复制一份到 `data/dawnduskterminal057/structure/` 即可。
博览轮舞那批用 `dawnduskterminalexpo`，同样是把 `ddt_empty.nbt` 复制一份。
模板只是空结构，复制没有维护成本。

有两批不进默认名单，要用上面的参数点名跑：

- `dawnduskterminalexpo`（博览轮舞）：它的第 1 档索敌按需求是不限距离的锥，
  和别的用例同场跑时会被隔壁场地里的僵尸抢走目标，量出来的不是自己的目标；
- 想单独验某一批新改动时同样点名跑，跑完再跑一次默认的全量。

`run/gametest/world` 会在多次运行之间保留，前一版用例摆下的方块、刷出的实体都会留在场地里。
`ddt_empty` 模板里一个方块都没有，重新摆一遍模板清不掉它们。
改完世界生成或测试互相打架时，删掉该目录重跑。

场类用例尤其要注意：`CubeField` 的场是 17×17×17，而模板只有 16×16×16，场一定会探出自己这一格。
用例之间只隔 7 到 8 格，一个没拆掉的场会把隔壁伤害类用例的伤害整片取消，表现为 `runGameTestServer` 里几条不相干的用例一起红。
`gametest/Experiment065Tests` 用 `withField` 把拆场放在 `finally` 里，正常与失败路径都清干净，新增落场用例时照这个写法。

首次构建会下载并反编译 Minecraft。

若仅有资源文件发生改动，任务可能报告 UP-TO-DATE，此时须验证 jar 中的实际内容。

依赖校验方面，仓库自带 `gradle/verification-metadata.xml`，解析依赖时逐个核对 SHA-256。
Twilight Forest 与 c2me 也在覆盖范围之内。
新增或升级依赖后须运行 `./gradlew --write-verification-metadata sha256 compileJava` 更新校验值；
该命令会重写整份文件（含缩进与排序），提交前建议只保留新增的 `component` 块，避免整篇 diff。
由于 NeoForm 运行时的 `neoFormRuntimeDependenciesCompileClasspath` 在不同平台上解析出的模块元数据并不完全一致，
至少还须再运行一次：
`./gradlew --write-verification-metadata sha256 dependencies \
--configuration neoFormRuntimeDependenciesCompileClasspath`；
LWJGL 的 native 包按平台分发。
在 Windows 上生成校验值后，须补上 Linux 与 macOS 的校验值，否则 CI 首次解析 native 时会报告缺少校验。

## 格式化

格式化由 Spotless 提供，规则写在 `build.gradle` 的 `spotless` 块中。
引擎为 palantir-java-format：120 列、4 空格缩进。
它是 google-java-format 的 4 空格分支，与本仓库原有风格最接近，
并会删除未使用的 import。

```bash
./gradlew spotlessApply   # 就地格式化
./gradlew spotlessCheck   # 只检查，CI 与 build 走这一条
```

`spotlessCheck` 由插件自动挂到 `check` 上，因此 `./gradlew build` 在有未格式化的代码时会失败。

`geckolib3/` 与 `molang/` 不参与格式化。
它们是来自 TouhouLittleMaid 的上游代码，重排后将来与上游对拍时满屏都是排版差异，见 `docs/ysm.md`。

### 提交前自动格式化

`.githooks/pre-commit` 在存在暂存的 `.java` 文件时运行 `spotlessApply`，并把改动重新放回暂存区；
格式化失败则提交中止。
hooks 目录不会随 clone 生效，每台机器须安装一次：

```bash
git config core.hooksPath .githooks
```

该钩子会启动一次 Gradle（数秒）。
临时绕过用 `git commit --no-verify`，但 CI 仍会因 `spotlessCheck` 失败，绕过没有意义。

## VS Code 的 Gradle 任务

侧栏的 Gradle 任务按钮使用 Java 扩展自带的 JRE（25）启动 daemon，而 `build.gradle` 中的 `java.toolchain` 指定为 21，
该 JRE 探测不到这一工具链，于是 `createMinecraftArtifacts` 直接报错：
`Cannot find a Java installation ... languageVersion=21`，
导致 `runClient` 之类的任务一经点击即失败。

`gradle.properties` 中的 `org.gradle.java.installations.fromEnv=JAVA_HOME` 使自动探测也读取环境变量中的 JDK；
`settings.gradle` 中的 `foojay-resolver-convention` 会在连一个 21 都找不到时自行下载。
这两条配置均与具体路径无关，命令行的 `gradlew` 与 CI 同样受益。

`.vscode/settings.json` 属于本机配置，`.vscode/` 已列入 `.gitignore`；
该文件通过 `java.import.gradle.java.home` 将 daemon 固定在本机的 JDK 21 上，更换机器时须修改其中的路径。

Java 不读取 `HTTP_PROXY` 环境变量。
要经由加速器访问网络，须在 `~/.gradle/gradle.properties` 中写入这几个键：
`systemProp.http.proxyHost`、`systemProp.http.proxyPort`、`systemProp.https.proxyHost`、
`systemProp.https.proxyPort` 与 `systemProp.http.nonProxyHosts`，
Gradle 的下载才会经过代理；
端口须随加速器调整。

### 点击 runClient 即失败的三种原因

第一种原因是上文所述的 JDK 探测问题，第二种原因是 wrapper 发行版无法下载。

`gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 一旦从 `8.14.3` 改成其他版本，
`gradlew` 会先到 services.gradle.org 下载几十到一百多 MB 的发行版；
本机直连的速率只有 20KB/s 上下，10 秒读超时一到便退出，报 `Downloading ... failed: timeout`。
连配置阶段都无法进入，于是侧栏的 `runClient` 与 `build` 按钮全部失效。
其中的 `retries=0` 使其一次即放弃。

本项目固定在 Gradle 8.14.3，本机 `~/.gradle/wrapper/dists/` 中已有缓存，**不得随意升级 wrapper**；
确需升级时，应先把发行版下载到本机缓存再修改版本，并将 `networkTimeout` 提高到 `60000`、将 `retries` 提高到 3 以上。

第三种原因是 Zig 工具链：`installZig` 与 `buildNative` 位于 `processResources` 的上游，
它们的失败同样表现为「点 runClient 没有反应」。工具链位置见下文「native 网格内核」的说明。

### 客户端被系统提交内存杀掉（不是 Java 异常）

症状：玩一会儿（常常正好在「Saving and pausing game」那一刻）整局直接消失，没有崩溃报告，
只在 `run/client/hs_err_pid*.log` 留下一份 JVM 原生崩溃：

```
There is insufficient memory for the Java Runtime Environment to continue.
Native memory allocation (malloc) failed to allocate ... Error detail: Chunk::new
```

这不是模组抛的异常，是**系统提交内存（commit）用尽**：Windows 的提交上限是「物理内存 + 页面文件」。
不给 `-Xmx` 时 JVM 按物理内存的 1/4 取堆（16G 的机器上约 3.9G），堆涨满之后 JVM 自己的原生分配
（JIT arena、线程栈、mixin）就 `malloc` 失败，进程被直接杀掉。

因此 `build.gradle` 的 `client` 运行显式给了 `-Xmx3G`、`-XX:MaxMetaspaceSize=1G`、
`-XX:ReservedCodeCacheSize=256m` 与 `-XX:MaxDirectMemorySize=1G`：

- 堆按物理内存的 1/4 走的默认值在 16G 机器上太大，钉住它才能给原生内存留余量；
- 直接缓冲加上限是为了让泄漏变成 `OutOfMemoryError: Direct buffer memory`（有崩溃报告可看），
  而不是吃干原生内存后静默消失。本模组的 `NativeMeshBatch` 是复用缓冲的，不在每帧新分配。

内存更大的机器可以用 `-Pddt_client_heap=4G` 覆盖堆上限。
真正根治还是把页面文件交回系统管理（或固定 8~16G）：页面文件只有 833M 时，提交上限只比物理内存高一点点，
浏览器、Gradle daemon 与客户端三者叠在一起很容易顶到天花板。

### native 网格内核

`src/main/native/ddt_native.cpp` 由纯 C++17 与 `std::thread` 实现，不含平台相关 API。
因此五个平台的库都能在同一台机器上编译出来。

内核只完成一件事：将一段顶点按一个 4x4 矩阵与一个 3x3 法线矩阵进行变换，并写入直接缓冲区。
它不识别实体、方块、骨骼或立方体。

每一段顶点称为一个 piece，调用方给出顶点区间、矩阵与法线取反标志。
该标志的低三位依次对应 X、Y、Z 轴，仅在对应法线为负时取反，用于复现 Bedrock 渲染对退化平面的修正。

`NativeGeoMesh` 负责把任意 Bedrock 模型摊平为 piece，`NativeMeshBatch` 负责在一帧内完成收集并一次性提交 JNI。
实体渲染器与方块实体渲染器都可以使用同一套流程。

#### 变换内核与 SIMD 分发

内核共有四种实现，均位于 `ddt_native.cpp` 中。

| 内核 | 平台 | 一次处理的顶点 | 说明 |
|---|---|---|---|
| `scalar` | 全部 | 1 | 基线实现，同时承担尾部余数的处理 |
| `sse2` | x86-64 | 4 | 128 位 4 路，属于 x86-64 基线 |
| `avx2+fma` | x86-64 且 CPU 支持 AVX2 与 FMA、内核保存 YMM 状态 | 8 | 256 位 8 路，乘法与加法合并为 FMA |
| `neon` | aarch64 | 4 | 128 位 4 路，ARMv8 基线即已包含 |

顶点采用 AoS 布局，每个顶点的 8 个 float 连续存放。
内核先将 4 个或 8 个顶点转置为按通道排列的向量，对六个输出通道各计算一次之后再转置回去。
每个输出通道都是一条乘加链，摊到 8 个顶点上即是一条 `_mm256_mul_ps` 加两条 `_mm256_fmadd_ps`。

法线取反不引入分支：将 `值 < 0` 的比较结果与 `0x80000000` 掩码相与后再异或。
其结果与标量实现的 `-x` 逐位一致，`-0` 与 `NaN` 不会被翻转。

运行期只选择一次，选中的函数指针随 job 下发，每个 piece 直接调用该指针。

- x86-64 用 `cpuid` 读 SSE2、AVX、FMA 与 AVX2 的支持情况，再用 `xgetbv` 确认 XCR0 的 SSE 与 YMM 两位都已置位，
  即内核确实会保存 YMM 状态，否则不启用 AVX2。
- aarch64 不做探测，NEON 属于基线。
- 选择之前先用 16 个固定顶点与一个非单位矩阵同标量实现比对一遍，再补 13 个顶点运行一遍尾部路径；
  取反标志按 0 与 X、Y、Z 三个单轴分别运行，若只测一个组合，则有误的轴会被漏过。
- 判定使用 `withinKernelTolerance` 随数值量级放大的 `1e-4` 相对阈值，任一值超出即回退到标量实现。
- 结果写入日志 `DawnDuskTerminal native mesh kernel loaded ... (abi 3, kernel avx2+fma)`。
  代码中也可以通过 `NativeMesh.kernelName()` 读取。

AVX2 实现用 `__attribute__((target("avx2,fma")))` 单独标注。
基线编译不包含 AVX 指令，因此在老 CPU 上加载不会直接触发 `#UD`。

不使用 `target_clones` 与 ifunc：ifunc 属于 ELF 的特性，Windows 与 macOS 均不支持，因此三个平台统一使用函数指针表。

`tools/native/benchmark.cpp` 是数值校验与吞吐对照工具。
它把 `ddt_native.cpp` 整份 include 进去，因此测量到的就是产物中的那份代码。
编译需要 JDK 的 `include` 目录与本仓库的 `src/main/native/win32`（Linux 与 macOS 使用 `posix`）。

```
zig c++ -O3 -std=c++17 -I<JDK>/include -Isrc/main/native/win32 tools/native/benchmark.cpp -o benchmark
```

它与运行时自检共用 `fillKernelProbe` 与 `withinKernelTolerance`，不另行复制一份输入与阈值。

输出内容包括：

- 当前 CPU 选中的内核；
- 每个内核在「三轴全开」与「flags 为 0」两种输入下相对标量实现的最大绝对误差；
- 单个大块顶点的吞吐；
- 按线上形状（712 个 24 顶点 piece 各调用一次）计算的每帧耗时。
其中 piece 那一组才是能够外推到游戏内的数字；
若只运行大块，广播与逐 piece 调用的开销会被摊薄，所得收益偏于乐观。

**不要把测量数字写进 README**，不同机器与不同编译器版本之间的差距很大。

构建固定使用 Zig，`installZig` 任务会自动把它下载到 Gradle 用户目录的 `ddt-zig/<version>`，默认为 `~/.gradle/ddt-zig/`。
此后不再重复下载，也不需要系统中预装编译器。

工具链及其缓存（`~/.gradle/ddt-zig-cache/`）都不放在工程目录中。
工程目录带有 DSH 沙盒留下的目录权限项，Zig 在该处创建编译临时目录会被拒绝，报 `cache: manifest_create AccessDenied`；
将同一份二进制移到用户目录下即恢复正常，该现象与 Zig 版本无关。

ziglang.org 的下载速率只有 20KB/s 上下，79MB 的包中途断流属于常态。
因此 `installZig` 的下载带有断点续传、重试、zip/xz 结构校验与官方 SHA-256 校验。
残缺的包会保留在原处等待下一次续传，不会像旧实现那样直到 tar 解压阶段才报错。
更换 `ddt_zig_version` 时，必须把对应版本的官方 shasum 补进 `build.gradle` 的 `zigKnownSha256`，
或用 `-Pddt_zig_sha256=<hex>` 显式给出，否则 `installZig` 会拒绝下载。
国内网络可以从 PyPI 镜像获取同一份工具链，其速率比 ziglang.org 快两个数量级；
将其解压到 `~/.gradle/ddt-zig/<version>` 后，`installZig` 会直接跳过下载。

| target | triple | 产物 |
|---|---|---|
| `windows-x86_64` | `x86_64-windows-gnu` | `ddt_native.dll` |
| `linux-x86_64` | `x86_64-linux-gnu.2.17` | `libddt_native.so` |
| `linux-aarch64` | `aarch64-linux-gnu.2.17` | `libddt_native.so` |
| `macos-x86_64` | `x86_64-macos` | `libddt_native.dylib` |
| `macos-aarch64` | `aarch64-macos` | `libddt_native.dylib` |

`./gradlew build` 默认编译全部五个目标，因此在任何平台上构建，`build/libs/` 中的 jar 都是全平台通用的。

jar 中的 `native/<target>/` 一次性带齐全部平台，运行时由 `NativeMesh` 按 `os.name` 与 `os.arch` 选择对应的库。

构建期仍然采用硬失败策略：任何一个目标编译失败都会导致 `gradlew build` 失败。
`release.yml` 也会校验五个平台的库都在 jar 中。

运行期分为加载阶段失败与运行阶段失败两条路径。

| 阶段 | 失败情形 | 处理 |
|---|---|---|
| 加载 | 平台无产物、资源缺失、`System.load` 失败 | 打印一次 warn，禁用 native，渲染改走 Java 路径 |
| 加载 | ABI 版本或顶点/矩阵/法线布局不匹配 | 打印 error 并带上两边的常量，禁用 native |
| 加载 | ABI 校验通过后启动探针失败 | 打印 error，禁用 native |
| 运行 | JNI 抛出异常、返回负错误码、句柄失效 | 打印一次 error，整个进程熔断，自本帧起回退到 Java 路径 |
| 硬崩溃 | SIGSEGV 之类 | 进程内无法捕获，由下次启动时的崩溃标记作保底处理 |

`NativeMesh` 不实现纯 Java 的顶点变换。
它的 Java 路径就是 `client/animation/YsmEntityRenderer` 里 `renderCubesOfBone` 沿用的默认实现。

### ABI 协议

Java 侧的 `NativeMesh.ABI_VERSION` 与 native 侧的 `ddt::kAbiVersion` 必须一致，当前均为 3。

native 侧导出四个只读常量：`nativeAbiVersion`、`nativeVertexFloats`、`nativeMatrixFloats` 与 `nativeNormalFloats`。

自版本 3 起新增 `nativeKernelName`，它返回实际选中的变换内核名，仅用于日志，调用时会顺带触发内核自检。

加载后首先比对这四个常量，然后运行一个最小用例。
用例建立一张含两条顶点的网格，用单位矩阵变换一次，再逐 float 比对输入与输出。
该最小用例断言的是逐位相等，因此 AVX2 的 FMA 舍入差异不会影响该探针。

任何 JNI 函数签名、参数含义、顶点布局、piece 布局或返回值语义的变化，都必须同时修改两处版本号。

版本号写在 `gradle.properties` 的 `ddt_native_abi_version` 中，构建时通过 `-DDDT_NATIVE_ABI_VERSION` 传入 C++。

`verifyNativeAbi` 任务会检查 `NativeMesh.ABI_VERSION` 与该属性是否一致，`check` 依赖该任务。

### 为什么 `nativeTransform` 接收直接缓冲区

矩阵、法线与 piece id 这三个入参都是 `ByteBuffer.allocateDirect` 产生的直接缓冲区，而非 Java 数组。

数组入参在 JNI 中会经过 `GetFloatArrayElements`。
每个 piece 包含 16 个 float 的矩阵加 9 个 float 的法线，712 个 piece 每帧约需 73KB 拷贝，实测比直接缓冲区多约 10us。

直接缓冲区由 `NativeMeshBatch` 在构造与扩容时整块分配，此后每帧只修改内容，不再重新分配。

当批次容量超过初始的 1024 段时，`growInputs` 会把已经写入的段搬到新缓冲区中；
不能只做重新分配，否则批次中途扩容会丢失先前收集的矩阵。

不得为简化实现而将这三个参数改回数组，也不得每帧新建缓冲区。

### 为什么没有采用 FFM

Java 21 的 `java.lang.foreign` 仍属于 preview API，编译与运行都需要 `--enable-preview`，
class 文件会带上 preview 标记（minor version 65535），普通 JVM 启动时直接抛出 `UnsupportedClassVersionError`。

Minecraft 1.21.1 固定在 Java 21，模组无法要求玩家修改启动参数，因此 FFM 不能进入发布产物。

实测在相同条件下 FFM 与 JNI 的性能持平：712 piece 为 49.5 与 49.7 us。
数组入参那 10us 的差距来自拷贝而非绑定方式，改用直接缓冲区即可获得该收益，无需引入 FFM。

### native 的失败与安全模式

加载成功时会在 `config/dawnduskterminal-native.crash-marker` 写入一个标记，正常退出时由 `deleteOnExit` 删除。

若上次运行期间进程发生硬崩溃，该标记会被保留，下次启动时直接禁用 native 并打印一条 error，玩家删除该文件即可重试。

手动关闭 native 的方式有三种，优先级从高到低如下。

| 方式 | 写法 |
|---|---|
| 配置 | `render.native_mesh = false` |
| 系统属性 | `-Ddawnduskterminal.disableNative=true` |
| 环境变量 | `DAWNDUSKTERMINAL_DISABLE_NATIVE=true` |

`-Ddawnduskterminal.forceNative=true` 可以单次忽略崩溃标记，用于排查问题。

native 侧所有导出函数都在 `try/catch(...)` 内返回错误码，C++ 异常不会穿过 JNI 边界。

句柄是自增 id 而非裸指针，失效、重复释放与类型错误均返回 `kInvalidHandle`，不会解引用野指针。

`nativeCreateMesh` 校验每一段 `offset/count` 是否都在顶点数组内。
`nativeTransform` 校验三个入参直接缓冲区的容量、输出缓冲区容量与 piece id 范围。

`jni.h` 取自当前构建所用的 JVM，`jni_md.h` 使用仓库中的版本。
`src/main/native/win32` 供 Windows 使用，`src/main/native/posix` 同时供 Linux 与 macOS 使用。

| 属性 | 作用 |
|---|---|
| `-Pddt_zig_version=0.13.0` | 更换 Zig 版本，非内置版本还须配合 `-Pddt_zig_sha256` |
| `-Pddt_zig_sha256=<hex>` | 覆盖当前目标的官方包 SHA-256 |

Zig 的产物先写入 `build/tools/native-work`，随后只把目标库复制到 `build/native/<target>/`。
这样 Windows 上顺带产生的 `.lib` 与 `.pdb` 就不会被打进 jar。

Windows DLL 只依赖 UCRT 与 `kernel32.dll`，不需要 `libwinpthread-1.dll` 之类的运行库。

## 游戏内测试

`./gradlew runGameTestServer` 用于运行游戏内测试。

测试使用命名空间 `dawnduskterminal`，模板为 `ddt_empty`。

### 已知的失败与卡死

整轮测试应当全部通过，退出码为失败数，全部通过时为 0。

**结构所在区块等不到实体刻会导致整轮测试卡死**（该问题已由 `mixin/GameTestChunkWaitMixin` 作保底处理）

原版 `GameTestInfo.tick` 只在两种情况下放置结构并运行测试：
`chunksLoaded` 为真，或结构包围盒覆盖的每个区块都满足 `isPositionEntityTicking`。
实测会出现区块已经强制加载（`hasChunk` 为真）却始终不进入实体刻的情况。
此时该条件恒为假，测试既不开始也不超时——判定超时的 `tickInternal` 只在该条件为真之后才会被调用。
于是进度格子中一直保留着空格。

而 `GameTestServer` 叠加的 `waitUntilNextTick` 不进入休眠。
它以每秒上万 tick 的频率重复打印同一行，日志曾增长到 102 MB，且永不退出。
实测 10 次中约出现 3 次，每次都是排在网格最前面的几个测试（结构网格由 `GameTestServer.startTests` 在随机原点铺开）。

`GameTestChunkWaitMixin` 在等待满 5 秒后即将 `chunksLoaded` 置为真并放行。
在健康的一轮中，几个 tick 之内条件即为真（GameTestServer 每秒运行几万 tick），因此几乎不会触发；
触发时会打印一条 warn。
修复前 21 次中有 3 次卡死，修复后 37 次全部运行完毕，其中 4 次由该保底措施挽回，通过数不变。

### WorldgenTests

`chronoDimensionTypeIsRegistered` 验证维度类型已注册。

`portedDensityFunctionsAreRegistered` 验证移植的密度函数已注册。

`portedNoiseParametersAreRegistered` 验证移植的噪声参数已注册。

`terrainNoiseIsSeedDerived` 验证地形噪声随种子变化。

`paletteBiomeCopiesAreRegistered` 验证群系副本已注册。

`paletteBiomeCopiesCarryOurFeatures` 验证群系副本携带本模组的特征。

`paletteBiomeSourceIsRegistered` 验证调色板群系源已注册。

`chronoDimensionDeclaresThePaletteSource` 验证维度 JSON 解析所得的即为调色板群系源，`extra` 与极带群系齐全。

`polarBandsUseTheExtraBiomes` 验证极带选用的是 `extra` 中的极昼与极夜群系。

`configuredFeaturesAreRegistered` 验证配置特征已注册。

`veinFillersUseThePalette` 验证矿脉填充使用调色板。

`paletteBiomeSourceSubstitutesThePreset` 验证群系源替换原版预设。

### AquiferTests

`terrainAndFluidUseTheModPalette` 验证地形与流体使用模组调色板。

`aquifersAreEnabledLikeVanilla` 验证含水层配置与原版一致。

`oreVeinsAreEnabledLikeVanilla` 验证大矿脉已开启。

### BlockStyleTests

`directionalStonesUseTheMirroredVariants` 验证有向随机那一组是 4 条变体：模型与镜像模型各转 0/180，
镜像模型父在 `cube_mirrored_all` 上且复用同一张贴图。

`directionlessBlocksMirrorTheirQuarterTurns` 验证无向随机那一组是 8 条：四个 90° 旋转各配一份镜像，
同样带镜像模型。

`rosyDirtKeepsTheQuarterTurns` 验证霞土是 4 条纯旋转（只转 y 四档），且没有镜像模型。

`charnockiteFamilySoundsLikeDeepslate` 验证紫苏岩一档（本体、赤钼矿、狄拉克矿与六块矿石）与深板岩同音效，
苍穹石一档仍是石头音效。

### OverworldOreTests

`vanillaOresBecomeThePaletteOres` 验证矿脉里那十二块原版矿石经 `ChronoVeinPalette` 换成两档副本（频率不动）。

`paletteOresKeepTheirHostStoneHardness` 验证两档副本的硬度分别等于苍穹石与紫苏岩。

`overworldOresStayOutOfTheCreativeTabs` 验证这十二块不在创造栏里。

`paletteOresShipTheirOwnLootTables` 验证十二份战利品表都能加载到。

`oreFeaturesPlaceOnlyThePaletteOres` 验证六条矿石特征只放调色板方块、上下两档各一个目标、且不区分空气暴露。

`oreFeaturesKeepTheReducedCountsAndTheirOwnHeightBands` 验证六条的次数与各自的高度带，并断言六个上界互不相等。

`wasteStoneFeaturesKeepTheReducedCounts` 验证赭石、绿片岩、榴辉岩与暮光石四套特征的次数。

### PortalShapeTests

`plantedPoolIsPortal` 验证带植物的水池判定为传送门。

`barrenPoolIsNotPortal` 验证荒水池不判定为传送门。

`poolWithoutPlantsIsNotPortal` 验证没有植物的水池不判定为传送门。

`loneWaterBlockIsNotPortal` 验证单个水方块不判定为传送门。

`buildFillsPoolWithPortalFluid` 验证建造时将水池替换为传送门流体。

`portalBlockSurvivesWithNeighbour` 验证有邻居的传送门方块可以存活。

`isolatedPortalBlockSelfHeals` 验证孤立的传送门方块可以自愈。

`portalFluidIsCompleteWithTwoNeighbours` 验证存在两个邻居即视为完整。

### FluidReactionTests

测试项 `portalFluidWithWater`。

测试项 `portalFluidWithLava`。

测试项 `portalFluidWithScorchingTwilight`。

测试项 `scorchingTwilightWithWater`。

测试项 `scorchingTwilightWithLava`。

测试项 `portalFluidWithFlowingWater`。

测试项 `flowingPortalFluidWithWater`。

### CanopyFrostMeltTests

`canopyFrostSurvivesTheLightThatMeltsVanillaSnow` 验证苍穹霜不会像原版雪那样在明亮光照下融化。

### TranslucentFaceCullingTests

`translucentCanopyCullsTheFaceBetweenTwoOfItself` 验证半透明方块之间会剔除面。

`cullingAgreesWithTheVanillaReference` 验证与原版参照一致。

`differentBlocksAreNotCulledAgainstEachOther` 验证不同方块之间不剔除面。

`portalFluidCullsTheFaceBetweenTwoOfItself` 验证传送门流体之间会剔除面。

### WroughtIronGlassTests

`wroughtIronGlassIsVanillaGlassClass` 验证该方块与原版玻璃属于同一个类（`TransparentBlock`）。
它带 `noOcclusion` 特性，不造成窒息，不遮挡视线，天光可直接穿过，硬度为 0.3。

`stackedGlassCullsTheFaceBetweenThem` 验证方块竖直叠放时中间的面被剔除、最上面那一块的顶面保留。
并排两块之间的侧面同样被剔除；
竖直连接纹理即依赖这一行为。

`wroughtIronGlassIsInTheCommonGlassTag` 验证该方块位于 `#c:glass_blocks` 标签中。

`wroughtIronGlassCraftsShapelesslyFromGlassAndWroughtIron` 验证无序配方占满九格。
配方由 8 个原版玻璃与 1 个 `twilightforest:wrought_iron_bar` 组成，一次产出 8 个。

### Experiment076Tests

`blockReplacesThePlaceholder` 验证 076 不再是占位的空壳。
它的物品就是 `dawnduskterminal:experiment_076` 那个方块的 `BlockItem`。

`placingItCreatesItsBlockEntity` 验证方块放置后即拥有自己的方块实体（渲染落点）。
服务端与客户端都不挂 ticker，因为外观由渲染器按世界时间实时计算。

`itLightsTheRoom` 验证该方块是 15 级光源方块；
在外观一侧，六个面也一律按满亮度绘制（`Experiment076Cube` 中不使用光照图），因此放置在洞穴中或夜间同样明亮。

`miningItRunsTheReactorReactionWithoutFakeBlocks` 验证只在方块被玩家<b>挖掉</b>时才执行一场砷铅铁反应堆的反应。
替换为其他方块、被爆炸破坏都不计入，所挂的事件为 `playerWillDestroy`。当计数推进过 5 时，
3x3x3 范围内不存在任何一个假金块或假钻石块；
当计数推进到 120 时，塌陷已将 `twilightforest:reactor_debris` 灌入壳内。
该反应是全局的，运行结束后必须调用 `clear()` 并清理现场。

`assetsMatchTheBlock` 验证这几件事：方块状态只有一个变体；方块模型为 cube_all，回退贴图是候选贴图之一（netherrack）；
物品模型使用 `builtin/entity`，并完整写出方块所用的那套 `display` 姿势；战利品表掉落自身，
以及该方块位于 `#minecraft:mineable/pickaxe` 标签中。

> 外观本身完全属于客户端实现，服务端无法测试。
> 六个面每 tick 各抽一张贴图；世界中的渲染由 `client/Experiment076Renderer` 负责，
> 物品栏与手持由 `client/Experiment076ItemRenderer` 负责，几何部分共用 `client/Experiment076Cube`。

### PaintingTests

十幅画照同一张表（`PaintingTests.SPECS`）逐条验，新增一幅加一行即可。

`variantsMatchTheirTextures` 验证每幅画的宽高与 JSON 一致，`asset_id` 指向同名的贴图。

`vanillaPaintingItemCannotPlaceThem` 验证这些变体都不在 `#minecraft:painting_variant/placeable` 中。
原版画物品因此无法选中它们。

`itemsAreBoundToOnePainting` 验证一件物品只对应一幅画，几件物品两两不同。

`entitiesAreLockedToTheirVariants` 验证本模组的画实体强制使用自己的变体，包围盒取自变体的宽高。
通过 `setVariant` 写入其他变体也无法替换，中键获取的是该实体自身的物品。

`vanillaPaintingRefusesTheVariants` 验证原版画实体拒绝这些变体（`PaintingVariantGuardMixin`）。

`eachItemPlacesItsOwnPainting` 验证贴着墙面放置后，实体类型、朝向、变体与包围盒均正确，物品数量减一。
十幅画轮流用同一处墙面各放一次，验完清掉再放下一幅。

### StructureTemplateTests

`everyPieceTemplateLoads` 验证 `data/dawnduskterminal/structure/` 下的每一件模板都读得出来（含家族目录里的）。

读不出来的件是静默的：`StructureTemplateManager.get` 把异常吞成空的 `Optional`，
`GridStructure.place` 只记一行日志就跳过那一件，症状是某类件在世界里根本不出现。
踩过的一次是 `air/notch.nbt`（现在是 `grid_rooms/air/notch.nbt`）存成了未压缩的 NBT：
`readStructure` 走 `NbtIo.readCompressed`，于是抛 `ZipException: Not in GZIP format`，凹角的凹口再也没有被擦过。
结构方块的 SAVE 写出来的本来就是 gzip，用脚本另存件时必须一样压缩。

`notchIsAOneBySevenColumn` 钉住 `grid_rooms/air/notch` 仍是 1×7×1 的纯空气：尺寸变了擦不到女儿墙顶，或者盖掉了楼面。

`afterPlaceStaysInsideTheWritableArea` 钉住 `GridStructure.afterPlace` 只碰当前区块那一截。
它把一件绳梯件摆到跨过区块边界的位置，再用一个只在可写区之内放行的动态代理包住真的 `level`：
真扫到区块外，代理当场抛异常，测试失败，而不是等到玩家跑图时区块生成被打断。
先把 `afterPlace` 改回扫整件包围盒跑一遍，这条测试确实会失败（代理报出越界的那一格），改完再通过。

`bandProcessorDecaysAndWebs` 钉住破损：缺失率给满时每个实体方块都被处理掉、约四分之一变蜘蛛网；
带 nbt 的宝箱不参与；缺失率为 0 时一块都不动。

`everyGridStructureHasItsRoomRoles` 用固定种子直接调 `Structure#generate` 生成 12 座房子，断言宝箱房
（`room/rect/chest`，箱子是件里自带的方块，不是另外摆的件）、空地房、家具房三种都出现过——
这里数的是 12 座的总和，「每座都有宝箱房」由 `everyBuildingHasAChestRoom` 逐座钉。
顺带钉住上下两段高度各自的落点窗口，以及两个结构 id 都在 `StructureExclusions` 的白名单里——
漏登记会让上层结构在晨昏线维度里一次都不生成。

`everyBuildingHasAChestRoom` 对 `grid_rooms` 与 `towerwood_rooms` 各生成 6 座，逐座要求至少一间宝箱房。
宝箱房按权重只占 `rect` 的 3/88，保底靠挑格子的次序：一层里先挑宝箱房（`pickChest`），
再挑楼梯，最后挑门；一座只占一次，全楼都没挑到时 `ensureChestRoom` 再拿一间房换掉。

`towerwoodRoomsAssemble` 生成 6 座塔木屋群，要求每座都有房间件与墙件、楼梯房配得上带洞的楼板，
且不出现 `post/column`（塔木那族写的是 `"posts": false`，悬空的格子不往下接柱子）。

`borderFurnitureIsJudgedInsideThePiece` 钉住 `hasBorderFurniture` 只查到件顶：
房间件 4 行，查到件外那一行会把这件当成「边框上有家具」，`placeBorder` 就把整套房件重落一遍。
拿 `grid_rooms/room/rect/empty`（应当没有）与 `room/rect/furn_04`（应当有）当样本。

`furnRoomKeepsItsMinecarts` 直接读件文件，要求塔木那间机关房里的两辆宝箱矿车还在，
且不带存档里的 `UUID` 与开发期模组的 `neoforge:attachments`——结构方块重新导出时实体会跟着丢，
丢了不会报错，只表现为机关上空一片。

`furnRoomPlacesNothingThatPops` 把机关房真摆进世界、跑 5 tick：箱子要带着 loot 表、
件里两辆矿车世界里也只能是两辆、地上不许出现掉落物。

`roomsPlaceExactlyOnce` 数件列表：每格的房间件只该落一条。房间件落两遍有两个代价——
箱子那类带 nbt 的方块会被「清掉再盖 barrier」，第一遍的内容物掉到地上；件里的实体会 spawn 两次。
楼梯件是例外（`room/rect/stair` 不计重）：它整间落一遍，末尾 `placeStairs` 还要只重写自己那一行天花板，
那一行里既没有方块实体也没有实体。

`stairPieceCarriesAHoledCeiling` 钉住楼梯件的最后一行：件必须 5 高，天花板那一行中间是空气、
洞口不碰件的边框列。洞口原来单独成一件（`floor/rect/stairtop`，落在上一层的地板上），
现在并进楼梯件，由 `placeStairs` 在最后一遍用 `ALL` 重写那一行才切得开——件矮一行、
洞里没空气，楼梯就永远上不去。

`specialRoomsCarryTheirFloor` 钉住掉落口 / 绳梯的房间楼板行：件必须 5 高，
最低那一行里要有下去的路（空气、结构空位、塔木那族的 `reappearing_block`，
以及洞里挂着的那一格绳 / 梯子都算）。
这两类的楼板原来按照房间路径另成一件（`floor/large/drop` 等），现在并进房间件，
装配时不再单摆平铺楼板——少一件就少一处会不同步的命名。
内角格不并：它的楼板与天花板本来就按体型共用（`floor/inner_corner`、`ceiling/inner_corner`）。

井段件那三条：

- `wellSitsUnderTheFloorRow`：井段件（`post/well*`）的顶行必须比绳梯房件的底行低一行，
  而且整个落在房间件的 x/z 范围里——摆错一行就会盖掉地板行里的原木或台阶。
- `wellFallsBackToTheBaseLayer`：落差超过柱子的上限（`SUPPORT_MAX_DROP`）时只铺 3 行
  （`WELL_ROWS`，正好是地基那一层），不在虚空里吊一根到基岩的绳子。
- `afterPlaceStaysInsideTheWritableArea`：井段件整体跨出区块时，区块外那一列的读要被跳过，
  区块里那一列照样往下接一格、撞石头就停。

`wallFamilyKeepsItsColumnCount` 钉住墙族件的列数（墙与女儿墙两列、外挑一列、墙脚三列），
`wallPiecesSitOnTheFrameLine` 从房间件的包围盒取出框架线，要求每件墙的框列正好落在其中一条上——
落笔角挪错一格时，偏得很像「本来就这样」，这条会先红。

`catalogueOrdersPiecesBySection` 钉住 `/ddt pieces` 的摆位：特殊件排最前，接着地板、天花板、地基、外挑、
女儿墙各占一行，房间按体型分组（组内 空地 → 宝箱 → 楼梯 → 掉落口 → 绳梯 → 家具），墙按短边 / 长边两行。
`catalogueIdsFollowTheFamilyAndCategory` 钉住筛件：指定家族只出那个家族目录下的件，再指定目录只出那个目录的件，
不认识的目录挑不出任何件。两条都只算坐标与路径，不往世界里摆方块——摆一整套要铺上千格，会污染隔壁用例的场地。
### CampCarminiteFireTests

`blockLooksAndLightsLikeACampfire` 验证三个状态位、点着时 15 级光、碰撞箱高 7 像素，
以及方块在 `#minecraft:campfires` 中（打火石就靠这条标签）。

`flintAndSteelLightsItAndShovelDousesIt` 验证打火石能点着、铲子能熄灭，两条都走 NeoForge 的工具动作。

`waterExtinguishesIt` 验证灌水同时写下含水与熄灭，不是只记一个含水。

`contactHurtsExactlyLikeCarminiteFire` 验证接触掉 2 点血、伤害类型是 `carminite_fire`、不点火；熄灭的篝火不掉血。

`miningItDropsItself` 验证战利品表只掉一颗自身。

`itCooksLikeAVanillaCampfire` 验证方块实体带着篝火那套四格食材：生猪肉能查得出生熟配方、
放得进火堆、点着时挂着烧烤 ticker。

`smallIncrementsAreInvertedWithinSixteenBlocks` 验证 16 格内 0.5 与 1.0 被取反、1.0001 仍算增加、16 格外照旧算增加。

`largeIncrementsConsumeExactlyLikeVanilla` 验证 6.0 这样的大增量照旧算增加，
原版那一 tick 也只扣 4 点、只掉一点饱和度。

`healthStillRegeneratesNextToTheFire` 验证篝火边上受伤的玩家照样回血（60 tick 内实打实涨血）。

`drainedExhaustionGrantsOneHungerPointThenRefillsToFour` 验证见底先补饥饿值（饥饿值满了才补饱和度），
消耗度压回 4、走完这 4 点才补下一次；取反当场与每 tick 两条入口都验一遍。

`unlitCampfireHasNoAura` 验证熄灭的篝火不生效。

`assetsWiringIsComplete` 验证方块状态八格、两张方块模型与方块贴图、物品图标与自己那 16×16 贴图都在包里。

## 指令

`/ddt` 需要权限等级 2。

`/ddt line` 打印当前位置的原点、法向、距离、参数、天光与维度信息。

`/ddt tp <param>` 按投影参数在 -1 到 1 之间传送到维度内。

## 移植脚本

`tools/port_overworld_worldgen.py` 从 Gradle 缓存中的反编译客户端 jar 抽取原版世界生成，改写命名空间后写入本模组。

`tools/port_tf_biomes.py` 将暮色森林的群系内容填入对应维度的群系副本，保留洞穴雕刻，并重映射特征引用。

`tools/check_tf_biomes.py` 校验移植后的群系：检查 JSON 合法性、是否残留 `minecraft:*` 特征，
以及 `twilightforest:*` 特征能否在 TF jar 内解析。

`tools/check_worldgen_refs.py` 校验配置特征的引用。
它报告两类问题：「模组已有同名副本却仍指向 `minecraft:`」的漏改，以及无人引用的副本。

漏改不会报错，只会静默生效：群系副本把土替换成了霞土，而原版那一份不会替换，于是同一片林地中会同时生长两种土。

## CI

`build.yml` 在 push main 与 v 标签以及 PR 时触发，运行 `./gradlew build` 并上传 jar。

`gametest.yml` 在 PR 时触发，运行两遍 `./gradlew runGameTestServer`：
一遍用原版环境，一遍把 c2me 1.21.1 NeoForge 构建放进 `run/gametest/mods`。

`release.yml` 只在 push `main` 与推送 `v*` 标签时触发。
对 `main` 的推送会自动把 `mod_version` 的补丁号加一并提交回 `main`。
随后用 Zig 交叉编译全平台 jar，校验五个 native 与嵌套的 c2me 都在 jar 中，并发布 GitHub Release。
其他分支不再自动发版，需要时手动运行 `workflow_dispatch`。
标签必须匹配 `gradle.properties` 的 `mod_version`。

c2me 本体采用 MIT 许可证，只有 `c2me-opts-accel-opencl` 为 All Rights Reserved，且没有打进产物。
1.21.1 NeoForge 目前有官方单独发布的 OpenCL Acceleration Module devbuild。
但它同样是 ARR，要求 Java 25，只支持服务端，而且 c2me 会依据驱动把设备列入黑名单；
本机 gfx90c 实测被标记为 `BROKEN_DRIVER_AMD_GCN`，强行绕过会在 `amdocl64.dll` 编译阶段导致 JVM 崩溃。
不得内置该模块，也不得承诺开箱可用。

c2me 由 `patchC2me` 从 `c2me_version` 解析后以嵌套 jar 的形式打进产物，保留官方全部模块。
Manifest 手写在 `META-INF/jarjar/metadata.json` 中，不得换成 ModDevGradle 的 `jarJar` 配置。
原因是本地补丁 jar 在配置期解析不到。

专用服固定种子 12345 的对照已经执行过：官方 c2me 与不安装 c2me 的 3072 列地表高度、群系与流体指纹逐字节一致。
开发环境中曾出现 15 列的高度差，属于 dev classpath 组合导致的假阳性，不得据此删除 c2me 模块。

「内置 c2me 加外部同版本 c2me」的组合也实测过。
经 JarJar 协商后可正常启动、正常生成地形、正常停服，地形指纹仍与生产基线一致。

地形指纹探针 `ChronoParityProbe` 只在 `DDT_CHRONO_PARITY=1` 或 `-Dddt.chronoParity=true` 时运行。
固定种子的专用服会写出 `ddt-chrono-fingerprint.txt` 并停服。

concurrency 只在 PR 上启用 cancel-in-progress。

## 常见问题与注意事项

`noise_settings/chrono.json` 的 `default_fluid` 必须是 `portal_fluid`，多个 mixin 依赖该字段识别维度。

`ore_veins_enabled` 须保持为 true，矿脉填充由 `NoiseChunkVeinPaletteMixin` 替换为调色板方块。

feature 引用与 `neoforge:remove_features` 的 ID 必须真实存在，否则注册表在加载期抛出错误。

1.21.1 中密度函数的字段是 `argument`，而非 `input`。

`gradlew` 的权限位必须是 100755，若权限位丢失，在 Linux 上会直接无法运行。

天光会烘焙进区块，修改天光后旧存档需要重新计算。

修改世界生成后，建议删除 `run/<端>/world` 并重新生成。

## 新增内容的放置位置

新增方块放置在 `block/`、`registry/ModTerrainBlocks` 或 `registry/ModBlocks` 中。

新增世界生成数据放置在 `src/main/resources/data/dawnduskterminal/worldgen/` 中。

新增自定义 Feature 放置在 `world/` 中，并注册在 `registry/ModFeatures` 中。

新增矿石放置在 `registry/ModChronoOres` 中，方块状态、模型与战利品表由
`tools/generate_overworld_ore_assets.py` 生成，见 `docs/worldgen.md` 的「主世界矿石」。

新增物品放置在 `registry/ModItems` 中。

新增画放置在 `data/dawnduskterminal/painting_variant/`、`assets/dawnduskterminal/textures/painting/`、
`assets/dawnduskterminal/textures/item/` 与 `assets/dawnduskterminal/models/item/` 中，
一幅画对应 `entity/GhastPaintingEntity` 的一个实体类型、`item/GhastPaintingItem` 的一件物品，
三者的配对写在 `registry/ModPaintingVariants` 的 `Kind` 上，实体与物品都从它取值。
宽高只写在变体 JSON 里，贴图像素与格数的比例是 32 像素一格。
物品图标另放一份到 `textures/item/`，按长边等比缩进 96x96 的透明画布。

新增流体放置在 `registry/ModFluids` 或 `registry/ModScorchingTwilight` 中。

新增配置放置在 `config/DdtConfig` 中。

新增指令放置在 `command/DdtCommands` 中。

新增进度放置在 `data/dawnduskterminal/advancement/` 中。
