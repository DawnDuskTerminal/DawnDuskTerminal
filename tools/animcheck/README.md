# 动画表达式校验（animcheck）

把 `assets/dawnduskterminal/` 下每个动画文件按**运行时那套绑定表**解析一遍，
再逐条 Molang 表达式求解析。用来兜住「表达式解析失败被静默当成 0」这一类问题：
运行时里 `MolangParser#parseExpression` 捕获异常后返回 `DoubleExpression.ZERO`，
所以某个通道不动、整段物理程序失效，日志之外没有任何提示。

## 跑法

需要 JDK 21（工程本身的工具链版本）与一次 `./gradlew compileJava`。

```bash
./gradlew compileJava

JDK="C:/Program Files/Microsoft/jdk-21.0.8.9-hotspot/bin"
CP="build/animcheck;$(cat build/moddev/clientLegacyClasspath.txt | tr '\n' ';')build/classes/java/main;build/moddev/artifacts/neoforge-21.1.252-merged.jar"

mkdir -p build/animcheck
"$JDK/javac" -nowarn -cp "$CP" -d build/animcheck tools/animcheck/Check.java
"$JDK/java" -cp "$CP" Check \
    src/main/resources/assets/dawnduskterminal/animations/*.json \
    src/main/resources/assets/dawnduskterminal/ysm/tower_ghast/animations/*.json
```

输出的最后两行是总数：

```
clips=65 clipFailures=0
exprs=5640 exprFailures=0
```

`exprFailures` 必须为 0。
不为 0 时先看 `first bad expr`：解析器不认识某种语法（例如块内收尾分号 `{ ...; }`），
或者绑定表里缺某个 `ysm.*` / `fn.*` / `query.*` 名字。

## 覆盖范围

只取真正承载表达式的通道：`bones.*.{rotation,position,scale}` 与 `timeline`，
跳过 `lerp_mode`（它的值是 `catmullrom` / `linear` 这类插值模式名，不是 Molang）。
