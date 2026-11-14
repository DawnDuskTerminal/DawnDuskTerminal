# 开发与构建

## 环境

项目使用 JDK 21 与 Minecraft 1.21.1，NeoForge 和 ModDevGradle 版本由构建配置管理。
构建必须使用仓库的 `gradlew`，保留其 100755 可执行权限。
依赖下载需要联网；不得将本机缓存、JDK 或代理的绝对路径写入公共文档。

## 暮色森林依赖

运行时必须安装暮色森林 1.21.1。
构建期由 CurseMaven 解析 `curse.maven:the-twilight-forest-227639:7797302`，对应 4.8.3345。
暮色森林的 modmaven 不提供该版本的有效文件，`exclusiveContent` 将 CurseMaven 限定于 `curse.maven` 组。
Java 直接引用 `TFEntities`、`TFBlocks`、`TFSounds`、`TFConfig` 等类型和常量，签名变化须在编译期暴露。
只有数据包定义、没有编译期常量的 ID 使用字符串。

## Bedrock 动画运行时

模组自带来自 TouhouLittleMaid（MIT）的 `geckolib3/` 与 `molang/`，不要求外部 GeckoLib 前置。
模型布局、作者原件保护、绑定表、资源重载与新增模型步骤统一见 [动画运行时](ysm.md)。
离线表达式检查执行 `./gradlew animationCheck`，不能替代客户端的模型和贴图验收。

## 开发用的客户端模组

`runClient` 通过 `clientDevMods` 加载 JEI、拼音搜索、Xaero 两种地图、Jade、Chunky、Zume。
其他开发模组为 Tweakerge、MaFgLib、AppleSkin、BoccHUD 与 OpenYSM。
它们不参与编译、不进入玩家产物，也不进入服务端、数据生成或 GameTest 的类路径。
版本统一在 `gradle.properties` 中维护。

| 来源 | 模组 |
|---|---|
| 官方 Maven | JEI、Xaero |
| CurseMaven | 拼音搜索、OpenYSM 非官方 NeoForge 移植 |
| Modrinth Maven | 其余开发模组 |

挂载使用 `tasks.named('runClient') { classpathProvider.from(...) }`。
不能改为工程级 `runtimeOnly`，否则会影响其他运行目标。
`additionalRuntimeClasspathConfiguration` 仅写入 legacy classpath，不能保证 FML 识别模组。
直接修改 run 的 `classpath` 会被执行时的 provider 覆盖。

Chunky 的 `chunky_version` 使用 NeoForge 文件 ID，不能仅按多个加载器共用的版本号解析。
Xaero 的 `xaero.lib` 由 POM 传递提供，JEI 的 `mezz_config` 是内嵌模组，不是同名库坐标。
Modrinth POM 不携带依赖，Tweakerge 与 BoccHUD 的 MaFgLib 前置须在 `clientDevMods` 显式声明。
Voxy、MiniHUD 与 Tweakeroo 没有适用的 1.21.1 NeoForge 构建，不由项目自动挂载。

OpenYSM 使用 CurseForge 的第三方移植，不能当作官方 NeoForge 产物。
其分叉 GeckoLib 与本项目包名不同，可同时加载以比较原始模型效果。
它的可选模组 mixin 不构成本项目的兼容承诺。

## 构建与运行

在仓库根目录执行命令。
首次构建需要下载并反编译 Minecraft。

| 命令 | 作用与目录 |
|---|---|
| `./gradlew build` | 构建单个全平台 jar，输出 `build/libs/` |
| `./gradlew runClient` | 开发客户端，目录 `run/client` |
| `./gradlew runServer` | 开发服务端，目录 `run/server` |
| `./gradlew runData` | 数据生成，目录 `run/data`，产出 `src/generated/resources` |
| `./gradlew runGameTestServer` | 默认游戏测试，目录 `run/gametest` |

资源改动若被判为 `UP-TO-DATE`，须检查最终 jar 内的实际内容，不能只依据任务状态验收。
测试命名空间、模板和场地清理见 [游戏内测试](#游戏内测试)。

### 依赖校验

`gradle/verification-metadata.xml` 校验依赖 SHA-256，包含暮色森林与 c2me。
升级或新增依赖后执行以下命令。

```bash
./gradlew --write-verification-metadata sha256 compileJava
./gradlew --write-verification-metadata sha256 dependencies --configuration neoFormRuntimeDependenciesCompileClasspath
```

命令会重写元数据，提交时只保留必要的 component 变更，避免无关排序或缩进差异。
NeoForm 依赖元数据及 LWJGL native 包按平台变化，须补齐 Linux、Windows 与 macOS 所需校验值。
不能以关闭依赖校验绕过缺失的哈希。

## 服务器会话隔离

正常停服并排空区块任务后，清除服务端晨昏线快照与两类恶魂的容量缓存。
若仍有待处理任务，或无法确认任务状态，则保留快照并拒绝启动下一会话。
此时必须重启游戏或服务端进程，不得通过清空快照继续使用同一进程。

`./gradlew serverSessionCheck serverSessionFailureCheck` 在独立 JVM 中检查清理与异常保护。
这些检查已纳入 `build`，预期的故障注入会输出错误日志，但检查本身应正常退出。
同一客户端的跨世界切换仍须单独验证，不能以这些检查代替。

## 格式化

Spotless 使用 palantir-java-format，规则为 120 列、4 空格缩进，并移除未使用的 import。
`geckolib3/` 与 `molang/` 是上游代码，不参与格式化，不得实施纯排版修改。

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
```

`spotlessCheck` 已挂入 `check`，未格式化的代码会阻止 `build`。
Markdown 不由 Spotless 处理，规范见 [文档书写规范](README.md#书写规范)。

### 提交前自动格式化

每个工作副本安装一次仓库钩子。

```bash
git config core.hooksPath .githooks
```

`.githooks/pre-commit` 仅在暂存 Java 文件时执行 `spotlessApply`，并重新暂存相关文件。
格式化失败时中止提交；不应通过绕过钩子替代 `spotlessCheck`。
提交使用中文约定式说明，格式为 `type(scope): 中文说明`，subject 单行且无句末句号。
推送前执行 `git fetch origin`，将当前分支 rebase 到最新 `origin/main` 并解决冲突，不创建同步用 merge 提交。

## VS Code 的 Gradle 任务

Gradle 扩展使用的 daemon JRE 与编译用 Java toolchain 是两项配置。
若提示 `Cannot find a Java installation ... languageVersion=21`，先确认 `JAVA_HOME` 指向 JDK 21。
`org.gradle.java.installations.fromEnv=JAVA_HOME` 允许 Gradle 探测该位置。
`foojay-resolver-convention` 在找不到工具链时尝试下载。
本机可通过 `java.import.gradle.java.home` 配置扩展 JRE，`.vscode/` 不进入版本库。

Java 不读取 `HTTP_PROXY`。
代理须在用户级 `~/.gradle/gradle.properties` 配置 `systemProp.http.proxyHost`、`systemProp.http.proxyPort`。
HTTPS 使用对应的 `systemProp.https.proxyHost` 与 `systemProp.https.proxyPort`。
排除地址使用 `systemProp.http.nonProxyHosts`，端口按实际代理填写。

### runClient 启动失败

先读取 Gradle 错误，区分工具链、wrapper 下载与 native 构建失败。
当前 wrapper 为 Gradle 8.14.3；升级时须确认发行版可下载、缓存可用，并保留下载超时与重试配置。
`installZig` 与 `buildNative` 位于 `processResources` 上游，失败会阻止客户端任务启动。
Zig 下载恢复与交叉编译约束见 [native 网格内核](#native-网格内核)。

### 客户端原生内存不足

若客户端直接退出、没有 Minecraft 崩溃报告，检查 `run/client/hs_err_pid*.log`。
以下诊断表示 JVM 原生内存分配失败，不应当作模组 Java 异常处理。

```text
There is insufficient memory for the Java Runtime Environment to continue.
Native memory allocation (malloc) failed to allocate ... Error detail: Chunk::new
```

Windows 提交上限受物理内存与页面文件共同限制，Java 堆之外还需要 JIT、线程栈和直接缓冲空间。
开发客户端默认限制为 `-Xmx3G`、`-XX:MaxMetaspaceSize=1G`。
其余限制为 `-XX:ReservedCodeCacheSize=256m` 与 `-XX:MaxDirectMemorySize=1G`。
堆上限可用 `-Pddt_client_heap=4G` 调整，不能只扩大堆而耗尽系统提交余量。
页面文件宜交给系统管理，或按工作负载提供充足空间，例如 8 至 16 GB。
直接缓冲上限使相关耗尽表现为可诊断的 `OutOfMemoryError`，`NativeMeshBatch` 本身复用缓冲。

### native 网格内核

`src/main/native/ddt_native.cpp` 是 C++17 与 `std::thread` 实现的通用变换内核，不识别实体、方块或骨骼。
每个 piece 输入顶点区间、4×4 矩阵、3×3 法线矩阵与法线取反标志。
标志低三位依次对应 X、Y、Z，只对负法线取反，保留 Bedrock 退化平面语义。
`NativeGeoMesh` 摊平 Bedrock 几何，`NativeMeshBatch` 每帧收集后一次提交 JNI。
实体与方块实体共用该流程。

#### SIMD 分发与数值检查

| 内核 | 平台 | 每批顶点 |
|---|---|---|
| `scalar` | 全平台，兼作余数处理 | 1 |
| `sse2` | x86-64 基线 | 4 |
| `avx2+fma` | CPU 与操作系统均支持的 x86-64 | 8 |
| `neon` | aarch64 基线 | 4 |

顶点采用 AoS，每个顶点连续 8 个 float；SIMD 在转置后计算六个通道，再转回原布局。
负法线取反使用比较掩码与符号位异或，`-0` 与 `NaN` 不翻转。
x86-64 通过 CPUID 检查 SSE2、AVX、FMA 与 AVX2，再用 XGETBV 确认 XCR0 的 SSE、YMM 位均已设置。
AVX2 函数单独使用 `target("avx2,fma")`，基线编译不得含 AVX 指令。
运行期仅选择一次函数指针，不使用 Windows、macOS 不支持的 ELF ifunc 或 `target_clones`。

选择前以 16 个固定顶点及非单位矩阵同标量比对，再用 13 个顶点检查尾部路径。
分别检查无取反与 X、Y、Z 单轴取反，容差由 `withinKernelTolerance` 按量级采用 `1e-4` 相对阈值。
超差时回退标量，选中内核可在加载日志或 `NativeMesh.kernelName()` 查询。

`tools/native/benchmark.cpp` 直接包含产物内核，共用 `fillKernelProbe` 与同一容差。
编译需要 JDK 的 `include` 与仓库平台头文件；Windows 示例为：

```text
zig c++ -O3 -std=c++17 -I<JDK>/include -Isrc/main/native/win32 tools/native/benchmark.cpp -o benchmark
```

Linux 与 macOS 将头文件目录改为 `src/main/native/posix`。
基准同时报告各内核误差、大块吞吐和 712 个 24 顶点 piece 的逐帧耗时。
无取反与三轴取反均须检查；评估游戏收益应以多 piece 路径为准，不能只测大块吞吐。
性能结果依赖机器和编译器，不写入面向玩家的说明。

#### 五平台单文件构建

`installZig` 自动下载工具链至 Gradle 用户目录 `ddt-zig/<version>`，无需预装编译器。
工具链及 `ddt-zig-cache/` 缓存须位于工程目录之外，避免受目录权限限制而出现 `manifest_create AccessDenied`。
下载支持断点续传、重试、zip/xz 结构检查及官方 SHA-256 验证。
中断后保留包以便续传；不能跳过哈希验证或将残缺包当作可用缓存。
使用 `-Pddt_zig_version=0.13.0` 指定版本，非内置版本须补充 `zigKnownSha256`。
也可显式传入 `-Pddt_zig_sha256=<hex>`，不得无校验下载。
必要时可从可信镜像取得同一工具链并核验官方哈希，解压到 `~/.gradle/ddt-zig/<version>` 后复用。

| target | triple | 产物 |
|---|---|---|
| `windows-x86_64` | `x86_64-windows-gnu` | `ddt_native.dll` |
| `linux-x86_64` | `x86_64-linux-gnu.2.17` | `libddt_native.so` |
| `linux-aarch64` | `aarch64-linux-gnu.2.17` | `libddt_native.so` |
| `macos-x86_64` | `x86_64-macos` | `libddt_native.dylib` |
| `macos-aarch64` | `aarch64-macos` | `libddt_native.dylib` |

`./gradlew build` 在任意宿主编译全部五个平台，并将 `build/native/<target>/` 打入 jar 的 `native/<target>/`。
临时产物放在 `build/tools/native-work`，只复制目标库，不能把 Windows `.lib` 或 `.pdb` 打包。
`jni.h` 来自构建 JDK，`jni_md.h` 使用仓库 `win32` 或 `posix` 版本。
Windows DLL 只依赖 UCRT 与 `kernel32.dll`，不得依赖 `libwinpthread-1.dll`。
任一目标失败即构建失败，发布流程再次检查五个平台库，玩家只需要一个 jar。

### ABI 协议

`NativeMesh.ABI_VERSION`、native 的 `ddt::kAbiVersion` 与 `ddt_native_abi_version` 必须一致，当前为 3。
Gradle 属性以 `-DDDT_NATIVE_ABI_VERSION` 传给 C++，`verifyNativeAbi` 已挂入 `check`。
加载后比对 `nativeAbiVersion`、`nativeVertexFloats`、`nativeMatrixFloats` 与 `nativeNormalFloats`。
随后以两条顶点及单位矩阵作真实调用探针，逐 float 比较输入与输出，`nativeKernelName` 返回选中内核。
JNI 签名、参数含义、顶点或 piece 布局、返回值语义变化时，必须同步升级协议版本。

矩阵、法线与 piece ID 使用直接缓冲，不能改回每 piece 拷贝的 Java 数组。
`NativeMeshBatch` 只在构造或扩容时分配；容量超过初始 1024 段时，`growInputs` 须搬运已收集数据。
不得每帧分配缓冲或在中途扩容时丢弃先前矩阵。
Java 21 的 FFM 仍为 preview，玩家启动不能要求 `--enable-preview`，发布产物继续使用 JNI。

### native 的失败与安全模式

| 阶段 | 失败 | 处理 |
|---|---|---|
| 加载 | 平台或资源缺失、`System.load` 失败 | 记录警告并禁用 native |
| 校验 | ABI、布局或真实调用探针失败 | 记录错误并禁用 native |
| 运行 | JNI 异常、负错误码或失效句柄 | 整个进程熔断，当帧回退 Java |
| 硬崩溃 | SIGSEGV 等无法在进程内捕获的错误 | 下次启动依据崩溃标记禁用 native |

Java 回退使用 `YsmEntityRenderer#renderCubesOfBone`，不是 `NativeMesh` 内另写一套变换。
native 仅重试几何输出，不能重复实体事件、动画推进、牵引绳、图层或名称栏。
关闭开关、启动参数和崩溃标记的恢复步骤统一见 [render 配置](configuration.md#render)。
崩溃标记须在接触 native 库前建立，正常退出时由 `deleteOnExit` 删除。
排查硬崩溃时先保留标记并使用 Java 路径，确认原因和稳定性后再按配置专题恢复。

所有 native 导出函数在 `try/catch(...)` 内返回错误码，C++ 异常不能穿过 JNI。
句柄采用自增 ID 而非裸指针，失效、重复释放与类型错误返回 `kInvalidHandle`，不解引用失效地址。
`nativeCreateMesh` 验证每段 `offset/count` 不越过顶点数组。
`nativeTransform` 验证直接缓冲容量、输出容量与 piece ID 范围，不得为精简删除这些检查。

故障注入检查使用独立 native 命名空间，见下一节。
它必须加载真实内核并检查失效句柄触发熔断且不写出部分顶点。
服务端检查不能替代客户端对事件次数、动画连续性、画面与重载资源释放的验收。

## 游戏内测试

默认命令为 `./gradlew runGameTestServer`。
按模板命名空间选取批次，不能按 Java 方法名过滤。

| 命名空间 | 内容 | 执行方式 |
|---|---|---|
| `dawnduskterminal` | 默认主批次 | 默认启用 |
| `dawnduskterminal057` | 057 飞刃 | 默认启用，也可单独指定 |
| `dawnduskterminalexpo` | 血肉之书 | 独立进程，避免不限距离索敌受其他场地影响 |
| `dawnduskterminalnative` | native 故障注入 | 独立进程，避免熔断影响其他测试 |

```bash
./gradlew runGameTestServer -Pgametest_namespaces=dawnduskterminal057
./gradlew runGameTestServer -Pgametest_namespaces=dawnduskterminalexpo
./gradlew runGameTestServer -Pgametest_namespaces=dawnduskterminalnative
```

指定批次通过后仍须运行默认套件，但默认套件不代表已覆盖 expo、native 或客户端验收。
新增命名空间须同步 `@GameTestHolder`、模板副本及运行参数。
四个命名空间的 `ddt_empty` 均为 16×16×16。
主命名空间另有 32×32×32 的 `ddt_large`，用于大实体或长距离建造。
模板 `size` 必须是三个整数的 NBT 列表，不能是会被误读为零尺寸的整数数组。
`GameTestInfrastructureTests` 检查原始标签、运行时尺寸与真实测试边界。
需要露天条件的用例须显式声明 `skyAccess=true`，否则生成屏障顶面。

`run/gametest/world` 在多轮运行间保留，不能自动删除既有测试世界。
隔离验证使用新的运行目录，保留原目录与存档。
框架只按模板真实边界清理，越界方块、实体与静态过程必须由测试自行恢复。
`CubeField` 边长 17，会超出默认模板；场地之间还存在水平 5 格、纵向 6 格的预留间隔。
不能据此假定场不会影响相邻测试，须使用独立 batch 并在失败路径同样清理。
`Experiment065Tests#withField` 的 `finally` 清理可供参考。

### 已知的失败与卡死

整轮应全部通过，以汇总与退出码判断结果，成功时退出码为 0，失败时为失败数。
故障注入产生的预期错误日志不能单独作为整轮失败或通过依据。

区块等待超时必须失败，不得绕过实体刻资格。
`GameTestInfo.tick` 仅在结构覆盖区块均能执行实体刻后推进，普通测试超时也从此时开始。
`hasChunk` 只证明已加载，不能证明实体会执行 tick。
`GameTestChunkWaitMixin` 在原版刻末尾检查 5 秒墙钟期限，刚就绪的区块优先走正常路径。
超时将测试标记为失败、只通知一次监听器并推进后续批次，不强行设置 `chunksLoaded`。
装备更新与弹体飞行依赖实际实体刻，不能假定所有断言在 t=0 完成。

先检查模板尺寸和加载范围，不用延长等待或反复执行掩盖基础设施错误。
`GameTestInfrastructureTests` 注入过期等待检查失败路径，并检查已就绪区块不误报。

### 测试入口

规格由对应专题说明，详细断言以 `src/main/java/com/dawnduskterminal/gametest/` 为准。
此处只保留定位表，不复制每个测试方法。

| 范围 | 测试类 | 规格 |
|---|---|---|
| 世界生成与流体调色板 | `WorldgenTests`、`AquiferTests`、`OverworldOreTests` | [世界生成](worldgen.md) |
| 传送门与反应 | `PortalShapeTests`、`PortalTeleporterTests`、`FluidReactionTests` | [传送门与流体](portal.md) |
| 霜层与面剔除 | `CanopyFrostMeltTests`、`TranslucentFaceCullingTests`、`WroughtIronGlassTests` | [世界生成](worldgen.md)、[合成与标签](crafting.md) |
| 方块外观与装饰 | `BlockStyleTests`、`PaintingTests`、`CampCarminiteFireTests` | [架构总览](architecture.md) |
| 试验物品 | `Experiment*Tests`、`ExpowheelTests`、`E115RegeneratorTests` | [试验物品与衍生机器](experiments.md) |
| 模板与屋群 | `StructureTemplateTests` | [模板与结构检查](#模板与结构检查) |

### 模板与结构检查

`StructureTemplateTests` 检查模板可读性、固定种子布局、资源生命周期及跨区块写入边界。
模板必须是 gzip 压缩 NBT；导出与修改流程见 [件](worldgen.md#件)。
`StructureTemplateManager#get` 可能将读取错误转为空结果，不能把缺件日志当作可接受的验证通过。

`afterPlace` 只可读写当前区块的可写范围，逐件异常隔离不得删除。
支撑、井段上限与回退规则见 [装配](worldgen.md#装配)，不能为了测试通过扩大生成期访问边界。
固定种子比较保留件 ID、顺序、位置、旋转和处理器参数。
`BandProcessor` 的高度窗口与 phase 必须经原版 codec 往返，旧存档字段缺省规则见同一专题。

模板实体需保留预期矿车数量，但不得带开发存档的 `UUID` 或 `neoforge:attachments`。
房间实体与含 NBT 的方块不得重复放置，以免复制实体或产生额外掉落物。
测试须真实放置并等待相关实体刻，不能仅以文件可解析代替行为检查。
凹口、楼梯洞口、特殊房楼板、井段、墙框与目录摆位的尺寸规则见世界生成专题。
目录类测试只检查路径和坐标，不在默认场地摆放整套模板而干扰相邻测试。

## 指令

`/ddt` 管理命令需要权限等级 2。
`/ddt line` 与 `/ddt tp <param>` 的参数和传送行为见 [传送门命令](portal.md#命令)。
结构件目录 `/ddt pieces` 的参数及修改流程见 [改件流程](worldgen.md#改件流程)。
模型包命令见 [动画运行时](ysm.md)。

## 移植脚本

| 脚本 | 职责 |
|---|---|
| `tools/port_overworld_worldgen.py` | 从 Gradle 缓存客户端 jar 抽取主世界生成并改写命名空间 |
| `tools/port_tf_biomes.py` | 移植暮色森林群系、保留洞穴雕刻并重映射特征 |
| `tools/check_tf_biomes.py` | 检查 JSON、原版特征残留及暮色森林引用是否存在 |
| `tools/check_worldgen_refs.py` | 报告已有模组副本却仍引用原版的特征及未使用副本 |

移植后仍须检查实际引用，JSON 合法不能证明方块调色板已生效。

## CI

`build.yml` 在 main、版本标签及 PR 上执行 `./gradlew build` 并上传 jar。
`gametest.yml` 在 PR 上分别运行无外部 c2me 与安装外部 c2me 的默认 GameTest。
独立命名空间未纳入默认名单，不能将该流程称为全部测试覆盖。

`release.yml` 在 main 与 `v*` 标签推送时发布，也支持手动触发。
main 推送会自动递增 `mod_version` 补丁号并提交，标签版本必须与该属性一致。
发布检查五个平台 native、嵌套 c2me 与 JarJar 元数据后上传单文件 jar。
PR 的 concurrency 启用 `cancel-in-progress`，其他触发不采用该取消规则。

### c2me 分发约束

`patchC2me` 从 `c2me_version` 解析并嵌入官方 jar，保留官方全部模块。
手写 `META-INF/jarjar/metadata.json`，嵌套 jar 位于 `META-INF/jars/`。
不能换成配置期无法解析本地补丁 jar 的 ModDevGradle `jarJar` 配置。
玩家额外安装同版本 c2me 由 JarJar 协商去重，该组合已验证能启动、生成和正常停服。

固定种子专用服务器对照确认地形高度、群系与流体指纹一致。
开发 classpath 的差异不能作为移除官方模块的依据。
需要重跑生产对照时，在临时专用服务器设置 `DDT_CHRONO_PARITY=1` 或 `-Dddt.chronoParity=true`。
`ChronoParityProbe` 写出 `ddt-chrono-fingerprint.txt` 后停服，不对既有世界执行破坏性清理。

c2me 本体采用 MIT，嵌套 jar 保留许可证。
`c2me-opts-accel-opencl` 及官方 1.21.1 OpenCL devbuild 为 ARR，不得内置或随产物分发。
官方 devbuild 还要求 Java 25、只支持服务端，并有驱动黑名单，不承诺开箱可用。
不得强行绕过黑名单，驱动编译阶段可能导致 JVM 原生崩溃。

## 常见问题与注意事项

世界生成硬约束见 [修改地形的注意事项](worldgen.md#修改地形的注意事项)。
`default_fluid=portal_fluid`、真实存在的特征引用、`ore_veins_enabled=true` 与密度函数字段 `argument` 均须保留。
修改烘焙天光后旧区块需要重新计算。
需要重新生成世界时，先取得备份与删除确认；不得自动删除 `run/<端>/world`。

## 新增内容的放置位置

模块与注册入口见 [架构总览](architecture.md#模块职责)。
世界生成数据放在 `src/main/resources/data/dawnduskterminal/worldgen/`。
新 Feature 注册于 `ModFeatures`；矿石注册于 `ModChronoOres`，资源生成脚本见 [主世界矿石](worldgen.md#主世界矿石)。
新画的变体、实体、物品与贴图配对见 [装饰画](architecture.md#装饰画)，不要在 Java 重复声明尺寸。
配置位于 `DdtConfig`，指令位于 `DdtCommands`，进度数据位于 `data/dawnduskterminal/advancement/`。
