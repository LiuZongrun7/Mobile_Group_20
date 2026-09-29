# Insights V7 · 设计与 Android 交接

本文件说明 Insights V7 的页面设计、统计口径和后续 Android 接入条件。页面数字均为设计样例，不代表真实统计服务已经实现。

## 1. 为什么这样丰富

Insights 回答三个问题：用了多少、花在哪里、如何使用助手。借鉴参考图的清晰趋势和活动日历，保留 ModelPilot 的成本管理与 Auto 特色，不照搬编程 Agent 的工作区或本地 / 云端分类。

| 页面层级 | 展示 | 能支持的用户判断 |
| --- | --- | --- |
| 总览 | 估算费用、Token、模型调用数、本月预算 | 本期使用量与预算压力 |
| 每日趋势 | 花费 / Token / 调用数切换、日期明细 | 哪天出现峰值，是否需要查看记录 |
| 模型分布 | 模型 / 供应商、花费 / Token 占比 | 主要成本来自哪个模型 |
| 活动日历 | App 内每日调用强度、活跃天数、范围内最长连续天数 | 使用节奏，而非消费排行榜 |
| Auto 与工具 | Auto / 手动调用占比、PDF / 图片工具次数 | 自动路由和任务工具的实际使用情况 |
| 来源与明细 | App 内 / 外部记录、筛选、导出 | 数据来自哪里、能否核对 |

首屏只有费用、两个简短指标和预算。其他内容纵向滚动，不把图表缩成多个半屏卡片。解释文字集中在信息弹层与明细。日历采用手机适合的月视图，而不是把整年 365 格压在一屏。

不加入没有可靠来源的“全账户数据完整度 94%”、官方钱包剩余天数、节省了多少费用、任务完成率、最长聊天时长、本地 / 云端 Agent 数量。Auto 的花费低不自动等于 Auto 节省了成本；需要可信的对照方案才能计算节省额。活动天数不发放奖励，也不鼓励多花 Token。

## 2. 示例与统计口径

所有数字是固定设计样本，不是用户真实记录，也不是模型官方报价。数据截止 2026-09-28，界面日期按 UTC+8。默认展示 09-01 至 09-28，含 28 个日历日。

| 指标 | 设计样本 |
| --- | --- |
| 费用 | ¥86.53，模型 API ¥82.33 + 工具 ¥4.20 |
| Token | 1,248,320，App 内 901,220 + 外部 347,100 |
| 模型调用 | 432，App 内 284 + 外部 148 |
| App 内模式 | Auto 218 + 手动 66，Auto 77%（四舍五入） |
| 工具 | PDF 提取 44 次，图片编辑 21 次 |
| 活动 | 23 / 28 天活跃，范围内最长连续 8 天 |
| 预算 | 9 月 ¥140，已用 ¥86.53，剩余 ¥53.47 |

### 范围与未知值

- 花费 / Token / 调用趋势和模型分布合并已记录的 App 内、外部数据；活动日历与 Auto / 工具面板只看 App 内。
- 比较使用同长度的日期窗口：9 月 1–28 日对 8 月 1–28 日的 ¥94.05，约低 8%。不把部分本月和完整上月比较。
- “September budget”始终计算 9 月 1–28 日已知花费，与图表选择的窗口分开。切换最近七日不会把预算占用缩成七日花费。
- 样本假设 App 内所选日期记录完整，因此无调用日可显示 0；外部没有记录只表示未知。不能推断未接入渠道的费用，也没有“全账户覆盖率”。
- 无数据的 7 月显示 No recorded data，不渲染假零花费、假下降幅度或假活动。
- 最近用量页按日、模型、来源展示汇总，默认最多七个有记录日期；导出包括当前筛选范围的全部行。
- 日历统计的是模型调用日期，不等于用户签到、消息数或任务完成数。最长连续仅在所选范围内计算，不声称用户历史纪录。

### 调用、Token 与费用

一次问题可能产生路由、摘要、工具后续等多次模型调用。模型调用数不能通过对话条数反推；重试也可能计费。工具执行次数独立计数，PDF 提取不一定收费。示例 ¥4.20 是图片工具费，已包含在 GPT 配置和 App 内费用内，不再次加到 ¥86.53 上。

原型使用“input 包含 cached input，total = input + output”的归一化口径。Cached input 只是输入中的子集。真实 Provider 的缓存写入、读取、思考 Token、图片或音频计费字段可能不同；先按接口语义映射，未知值保留 null，不能靠不存在的字段补零。

本页模型分布代表“归属于该模型请求的总费用”，包含关联工具费；明细拆出模型 API / 工具费用。同一工具请求如已在 Provider 总账中包含，不能重复计入。多模型任务按实际调用分配，不全部归给主回复模型。

费用生产实现使用整数微货币单位或精确十进制，在展示层统一舍入。跨币种要记录原币、汇率来源与日期；没有汇率时不能直接合计。本原型是固定人民币样本，用整数分保持卡片、图表和导出相符；Token / 调用数均用整数。Provider 账单是最终对账依据。

## 3. Android 可行性：现有能力和待补能力分开

读取当前仓库可见，工程使用 Java + XML Views，而非 Compose。本次设计不要求迁移 UI 框架或升级图表库。

| 内容 | 仓库现有基础 | 接入时需要做什么 |
| --- | --- | --- |
| 费用、Token、日趋势 | Room；UsageCallEntity 的日期、provider / model、Token、费用字段；DailyUsageDao 的区间和总量查询 | 按选择窗口聚合，正确处理缺失价格与时区 |
| 模型 / 供应商分布 | DashboardUsage 已计算部分分组；MPAndroidChart 已列为依赖 | 新版单列列表与条形图，统一图表和列表的数据源 |
| App 内 / 外部来源 | 当前 Source 为 IMPORTED / SAMPLE | 增加独立 origin 字段；不能把 IMPORTED / SAMPLE 当成 Assistant / External |
| 调用次数 | UsageCall 允许单次 call 或时间 bucket；并非每行都是一次调用 | 单次请求通过稳定 ID 去重；聚合数据必须有显式 request_count，否则次数显示未知 |
| Auto / 手动 | 当前已检查的用量实体没有对应字段 | 每次实际模型调用保存 mode、request_id、实际 model_id；必要时另存 route_reason |
| 工具次数与费用 | 当前已检查的用量实体没有完整工具日志 | 保存 tool_call_id、request_id、tool_id、时间、状态、费用与币种；约定统计完成执行还是尝试次数 |
| 活动日历 | 已有按日聚合能力 | 仅使用可靠的 App 内调用日志；不要将批量导入时间当成实际活动日期 |

具体源码定位：

- `app/src/main/java/com/mobilegroup20/tokentrail/data/local/UsageCallEntity.java`
- `app/src/main/java/com/mobilegroup20/tokentrail/data/local/DailyUsageDao.java`
- `app/src/main/java/com/mobilegroup20/tokentrail/contract/model/UsageCall.java`
- `app/src/main/java/com/mobilegroup20/tokentrail/ui/dashboard/DashboardUsage.java`
- `app/build.gradle.kts`、`gradle/libs.versions.toml`

当前 DashboardUsage 将 input、cache read、cache write、output 作为四个互斥桶相加。本设计页的输入是含缓存总输入；适配此实体时应构造 `inputTotal = input + cacheRead + cacheWrite`，再加 output。不能把原型字段直接套到现有公式上，否则重复或遗漏。其他供应商也要以其实际字段定义为准。

### 组件方案

用 RecyclerView / 分区 item 展示整页；趋势可复用工程已有 MPAndroidChart，模型占比用普通 View 进度条。月日历可用网格视图或轻量自定义绘制，不需要新增重型图表服务。图表只渲染后端或本地汇总，不在手机上部署模型，也不需要 GPU。

原生 Android 需要至少 48dp 的可触区域、TalkBack 描述、系统字体缩放测试。日历色块不能是唯一读取方式：提供日期选择及文字明细。趋势保留前 / 后一天按钮，避免只能精准点窄柱。颜色用于辅助，金额、日期、百分比均有文字。

这些是后续原生实现建议，不表示 Web 原型的所有像素间距已经满足 Android 无障碍要求。官方参考：[View 自定义绘制](https://developer.android.com/develop/ui/views/layout/custom-views/custom-drawing)、[Android 无障碍原则](https://developer.android.com/guide/topics/ui/accessibility/apps)、[MPAndroidChart 仓库](https://github.com/PhilJay/MPAndroidChart)。

## 4. 对应设计图

- [Insights 首页](screens/13-insights.png)
- [完整长图](screens/13-insights-full.png)
- [趋势与分布](screens/13-insights-trends.png)
- [活动日历](screens/13-insights-activity.png)
- [Auto 与工具](screens/13-insights-routing.png)
- [无数据状态](screens/13-insights-empty.png)
- [用量记录](screens/14-usage.png)

本目录只提供设计图与说明，不包含页面代码或统计程序。真实助手日志、导入渠道和账单接通后，才可用实际数据替换样例，并移除 Sample data 标记。
