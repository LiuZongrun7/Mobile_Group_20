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

### 论坛首版新增（2026-09-26）

| 库 | 依赖坐标 | 固定版本 | 许可证 | 用途 |
|---|---|---|---|---|
| Retrofit / Gson converter | `com.squareup.retrofit2:retrofit` / `converter-gson` | 3.0.0 | Apache-2.0 | 类型化 HTTP API 与 JSON 转换 |
| Glide | `com.github.bumptech.glide:glide` | 4.16.0 | BSD-2-Clause；仓库 LICENSE 含其他组件 notices | 缩略图、预览与图片缓存 |
| RecyclerView | `androidx.recyclerview:recyclerview` | 1.4.0 | Apache-2.0 | 新闻与社区列表 |
| SwipeRefreshLayout | `androidx.swiperefreshlayout:swiperefreshlayout` | 1.2.0 | Apache-2.0 | 下拉刷新 |
| Arch core-testing（仅测试） | `androidx.arch.core:core-testing` | 2.2.0 | Apache-2.0 | LiveData 异步测试 |
| AndroidX test（仅测试） | `androidx.test.ext:junit` / `espresso-core` | 1.3.0 / 3.7.0 | Apache-2.0 | 页面切换、草稿恢复与失败反馈；旧版本不能在当前 Android 17 模拟器注入点击 |
| MockWebServer（仅测试） | `com.squareup.okhttp3:mockwebserver` | 4.12.0 | Apache-2.0 | 会话、游标、图片、幂等 key 的 HTTP 契约测试 |

出处：[Retrofit LICENSE](https://github.com/square/retrofit/blob/3.0.0/LICENSE.txt)、[Glide LICENSE](https://github.com/bumptech/glide/blob/v4.16.0/LICENSE)、[AndroidX LICENSE](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt)、[OkHttp LICENSE](https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt)。版本在 Maven Central / Google Maven 元数据核实；Glide 最新 5.0.9 要求 SDK 37，本工程 SDK 36.1 固定使用兼容的 4.16.0。

Gson converter 的传递依赖 Gson（Apache-2.0）与 Retrofit 的 OkHttp（Apache-2.0）由 Gradle 解析。

新闻采集工具为本项目标准库实现，不含外部项目代码。NewsNow、Miniflux 和 AI News Brief 只作为采集、RSS/API 和新闻源设计的参考；RSSHub 未接入。

### 服务器论坛服务新增（2026-09-27）

以下版本及许可证已从安装包 metadata 核实，固定直接依赖见 `backend/requirements.txt`：

| 库 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| FastAPI | 0.141.1 | MIT | 论坛 HTTP API |
| Uvicorn | 0.54.0 | BSD-3-Clause | ASGI 进程 |
| HTTPX | 0.28.1 | BSD-3-Clause | 调用已有账号验证接口 |
| Pillow | 12.3.0 | MIT-CMU | 检查上传的真实图片格式 |
| python-multipart | 0.0.32 | Apache-2.0 | multipart 图片上传解析 |
| pytest（仅测试） | 9.1.1 | MIT | 服务端契约、并发和持久化测试 |

服务器使用系统 Nginx 反向代理及独立环境的 Certbot 5.8.0 管理 IP 证书；SQLite 来自 Python 标准库。没有复制外部论坛或 RSS 项目源码。App 的会话加密使用 Android Keystore API。

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
| [LiteLLM](https://docs.litellm.ai/docs/simple_proxy)（MIT，`enterprise/` 目录单独授权） | §3 竞品对比表 | **对照物，不引**：它是完整网关；我们的中转只透传不转换（见下） |
| [dsh-context](https://github.com/bowenliang123/dsh-context/blob/main/README.md) | §3 竞品对比表 | **对照物，不引** |

> **2026-09-27 更正：** 这一节原来写的是「LiteLLM 是代理网关，**我们不代理流量**」。
> 那句话**不再准确**——我们加了 API 中转（[`RELAY_API.md`](RELAY_API.md)）。
> 但**仍然不是同一层东西**，报告里要这么区分，别写成「我们也做了个 LiteLLM」：
>
> | | LiteLLM | TokenTrail 中转 |
> |---|---|---|
> | 协议转换 | 有（多 provider 归一、Anthropic↔OpenAI 等） | **没有**，请求体和响应体原样穿过 |
> | 路由 / 负载均衡 / 多 key 轮换 / 故障转移 | 有 | **没有**，一个 relay key 对应一个上游 |
> | 目的 | 做网关 | **只为拿到逐次调用的 usage**（`cacheWrite` 这桶账单文件里没有） |
> | 是否默认开启 | — | **默认关闭**，不装 env 文件时 `/api/relay/*` 就是 404 |
>
> 也就是说：**代码一行没引 LiteLLM**，定位上我们也不是网关——中转是
> 一个**可选的采集通道**，用户不开启时产品仍然完全不代理流量。

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
