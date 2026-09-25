# 开源使用与许可证留痕

`TASKS.md` §3.1 和评分细则要求：**用之前记下项目名、仓库地址、许可证**，写进报告里
「开源使用」那一节（评分细则里占 15%）。这份文件就是那份记录的唯一出处，
引一个新库就在这里加一行，别只在聊天里说。

**为什么单独一份。** 美术那批 AI 生成图的留痕在 [`ART.md`](ART.md) §6，那份管**图**；
这份管**代码**。两份都叫「来源留痕」，但一份进报告的美术节、一份进开源节，
查的时候别走错。

---

## 1. 已经引进工程的（会进 APK）

| 库 | 依赖坐标 | 版本 | 许可证 | 谁在用 | 为什么引它 |
| --- | --- | --- | --- | --- | --- |
| **Room** | `androidx.room:room-runtime`<br>`androidx.room:room-compiler` | 2.8.5 | Apache-2.0 | 数据侧（`data/local/` 三张表） | 去重那条验收标准就是它的 `@Insert(onConflict = IGNORE)`；`@Query` 能直接返回 `LiveData`，正好是五个接口的返回类型 |
| **MPAndroidChart** | `com.github.PhilJay:MPAndroidChart` | v3.1.0 | Apache-2.0 | 数据侧（`ui/dashboard/`） | Compare / Sessions 的柱状图和折线图，自己用 Canvas 画工作量大 |

### 出处（写报告时照抄，别转述）

- **Room** —— 它是 AndroidX 的一部分，没有单独的 `LICENSE`，用的是 AndroidX 仓库
  根目录那一份：<https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt>
  （Apache License, Version 2.0, January 2004）。
- **MPAndroidChart** —— 仓库根目录的 `LICENSE`，首行是 `Copyright 2020 Philipp Jahoda`：
  <https://github.com/PhilJay/MPAndroidChart/blob/master/LICENSE>。
- **版本号出处**：Room 的 2.8.5 取自 Google 官方仓库的
  <https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/maven-metadata.xml>
  （`<release>` 就是它，别照抄博客上的版本）；MPAndroidChart 的 `v3.1.0` 取自它
  README 里的 Gradle 片段。**注意它带 `v` 前缀**，JitPack 的 tag 和 Maven Central
  那种纯数字版本号写法不一样。

两家的许可证都过得了 `TASKS.md` §3.1 那道标准（Apache-2.0 / MIT 这类可用于课程作业，
GPL 要谨慎）。**没有 GPL / AGPL 混进来。**

## 2. 只作参考、没有引入代码的

大纲 §8 的 `Open-source reuse` 那一行提到的项目**比实际引进的多**，报告里要分得开
——「学到做法」和「引入依赖」是两件事，混着写会答不上来「这个库在哪个文件里」。

| 项目 | 大纲怎么说的 | 实际状态 |
| --- | --- | --- |
| [Room with a View](https://github.com/android/codelab-android-room-with-a-view)（Apache-2.0） | *guides storage* | **只参考做法**（Entity / Dao / Database 三段式的分法），一行代码没拷 |
| 开源塔防样例 | *may supply the wave and placement loop* | **一行都没用**，`game/engine/` 八个类全部手写 |
| [dsh-pet](https://github.com/zhu1090093659/dsh-pet/blob/main/README.zh.md) | *inspires the agent's entry point* | **只是灵感**（agent 的入口形态），没引代码 |
| [LiteLLM](https://docs.litellm.ai/docs/simple_proxy)（MIT，`enterprise/` 目录单独授权） | §3 竞品对比表 | **对照物，不引**：它是代理网关，我们不代理流量 |
| [dsh-context](https://github.com/bowenliang123/dsh-context/blob/main/README.md) | §3 竞品对比表 | **对照物，不引** |

## 3. 引擎侧仍然是零第三方

`game/engine/` 八个类全部手写，只 import `java.util` 和 `contract/model/` 里的两个枚举，
**不含任何第三方塔防代码或素材**——所以报告的开源节**不需要给引擎署名**。

这一条有判据，别靠记忆：

```bash
# 应该什么都不输出（engine 必须能脱离 Android 跑测试，也不该有外来代码）
grep -rn "import android\." app/src/main/java/com/mobilegroup20/tokentrail/game/engine/

# 现在应该是零命中（我们只动了构建文件，没有拷任何人的代码进来）
grep -rniE "copyright|licensed under|SPDX" app/src/main/java
```

以后真去借塔防样例的话，**这两条 grep 会同时变**，那时候 §1 那张表要加一行，
报告的开源节也要跟着改。

## 4. 依赖配在哪三个文件里

改依赖只动这三处，别在别处手拼坐标：

| 文件 | 放什么 |
| --- | --- |
| `gradle/libs.versions.toml` | 版本号 + 库别名。**版本号只在这里出现一次**，别的地方用 `libs.xxx.yyy` 引用 |
| `settings.gradle.kts` | 仓库。`google()` + `mavenCentral()` 之外还多了 **JitPack**——MPAndroidChart 只在那一处，删掉那个仓库构建会报 `Could not find com.github.PhilJay:MPAndroidChart`，而报错信息不会告诉你是缺仓库 |
| `app/build.gradle.kts` | `implementation` / `annotationProcessor`。**Room 的编译器必须挂在 `annotationProcessor` 上**——它编译期生成建表语句和查询实现，所以列名写错是编译报错而不是运行时崩；而且它不进 APK，只影响编译时间 |

**为什么 `room.schemaLocation` 要配**：Room 把每一版的建表语句导出成 JSON 放在
`app/schemas/`，用来验证升级迁移有没有写对。不配的话第一个 `@Database` 出现时构建会
警告「Schema export directory is not provided」。**那个目录要跟着提交**——它是迁移
测试的基准，丢了就说不清哪一版表结构长什么样。

## 5. 引一个新库的流程

1. 查许可证，**读仓库根目录的 `LICENSE` 原文**，不要转述二手说法；GPL / AGPL 先停下来问；
2. 在 `libs.versions.toml` 里加版本号和别名（版本号去官方仓库的 `maven-metadata.xml` 查，
   别照抄博客）；
3. 确认它从哪个仓库解析——不在 Maven Central 的话要在 `settings.gradle.kts` 里加仓库，
   **依赖坐标里的 `com.github.*` 前缀就是 JitPack 的标志**；
4. 在 §1 那张表加一行，写清楚**谁在用、为什么引它**；
5. 跑一次 `./gradlew assembleDebug` 和 `./gradlew testDebugUnitTest`，两个都要过。
