# TokenTrail 接口契约

三个人各做一块，靠这份文档对齐。**怎么用**：

- **权威是 Java 代码，不是这份文档。** 字段的类型和含义以
  `app/src/main/java/com/mobilegroup20/tokentrail/contract/` 下的类为准；这份文档解释
  「为什么这么定」和「谁负责」。两者不一致时以代码为准，然后回来把文档改对。
- 改任何契约字段之前，先在群里说一声，再改代码和这份文档，同一个提交里完成。
  悄悄改字段会让另外两个人的模块在你不知道的时候编译不过。
- 标了 **TBD** 的地方是还没定的，不是漏了。

---

## 1. 分工与归属

| 模块 | 负责人 | 你要实现的接口 | 代码放哪 |
| --- | --- | --- | --- |
| 用量导入、定价、预算、面板 | 张莉 | `UsageRepository` `BudgetRepository` | `data/local`、`data/remote`、`data/importer` |
| 论坛帖子与排序 | 汪庭栋 | `ForumRepository` | `data/remote`、`ui/forum` |
| 塔防游戏、建议服务、agent 工具 | 刘宗润 | `SeasonRepository` `AdviceRepository` | `game/engine`、`game/view`、`agent` |

界面（UI/UX）、无障碍和集成三人共担。

**依赖方向只有一个**：界面 → `RepositoryProvider` → 接口 → 实现。
谁都不许 import 别人的实现类，只认接口。这样三个人的模块可以同时开工。

---

## 2. 命名口径

- 提供方统一写 **GLM**（智谱那一侧）。大纲早期写的 “ZCode / Z.ai” 就是它，
  代码和文档里不要再出现 `zhipu`、`zai`、`zcode`。
- 枚举值固定 `OPENAI` / `GLM` / `DEEPSEEK`，见 `Provider`。
- **提供方和模型是两个层级**：定价的最小单位是模型（`glm-4.6`、`glm-4-flash`
  不是一个价）。所以每条记录 `provider` 和 `model` 都要有。
- 界面上显示的字符串一律取 `Provider.displayName`，不要另写。

## 3. 时间口径

| 事项 | 规定 |
| --- | --- |
| 时区 | `Asia/Shanghai`，只在 `TimeUtils.ZONE` 定义一次 |
| 「天」 | 字符串 `yyyy-MM-dd`，跨 DB / JSON / Firestore 都用这个形式 |
| 「月」 | 字符串 `yyyy-MM` |
| 存储的时刻 | UTC 毫秒（`long`）。只在换算成「哪一天」时经过 `TimeUtils` |
| 日边界 | `TimeUtils.today()` / `yesterday()` |

各家 provider 的账单是按 UTC 切天的，所以我们的天和官方账单可能差几个小时。
这是**已知的估算误差**，界面要说明，不要当成 bug 去修。

## 4. 数据流：三层，别搞混

```
日志文件 ──解析┬─→ UsageCall（原始，只读，唯一事实来源）
               │        │
               │        └─去重靠 id 唯一索引，重复导入不新增
               │
               └─→ DailyUsage（按 天×提供方×模型 的派生汇总，可重算）
                        │
                        └─→ 结算 → SeasonState（余额，服务端权威）
```

- **`UsageCall` 是唯一事实来源**，`DailyUsage` 是派生数据，删了能重算。
  所以纠正费率、按会话分析、回答「为什么涨了」都回原始表，不要只存汇总。
- **只有 `Source.IMPORTED` 的记录进 `DailyUsage`**。演示用样例数据
  （`Source.SAMPLE`）只用于界面演示，不进汇总、不进预算、不换游戏资源。
- 费率纠正后重算汇总，`DailyUsage.rateVersion` 要跟着变。

## 5. 存储契约

### Firestore

| 集合 | 可见性 | 谁写 | 谁读 |
| --- | --- | --- | --- |
| `users/{uid}/usageCalls/{callId}` | 仅本人 | 客户端导入 | 客户端 + 建议服务 |
| `users/{uid}/dailyUsage/{day}_{provider}_{model}` | 仅本人 | 客户端导入后派生 | 客户端 + 建议服务 |
| `users/{uid}/seasons/{yyyy-MM}` | 仅本人 | 客户端结算事务 | 客户端 + 建议服务 |
| `users/{uid}/budgets/{yyyy-MM}` | 仅本人 | 客户端 | 客户端 + 建议服务 |
| `forum/posts/{postId}` | **所有登录用户可读** | 作者 | 客户端 + 建议服务 |
| `forum/posts/{postId}/replies/{replyId}` | 同上 | 作者 | 客户端 + 建议服务 |
| `pricing/rates/{provider}_{model}_{effectiveFrom}` | 所有登录用户可读 | 管理端 / 客户端拉取 | 客户端 + 建议服务 |

**用量私有、论坛公开，这两类的规则写法完全不同，集合必须分开。** 把两者混进同一
集合，就会出现「为了读热帖而把所有人的帖子都放开」这种口子。

### Room（本地，只三张表）

| 表 | 主键 / 索引 | 说明 |
| --- | --- | --- |
| `usage_call` | **`id` 唯一索引** | 去重的落点：`OnConflictStrategy.IGNORE`，返回 -1 即重复 |
| `daily_usage` | 主键 `(uid, day, provider, model)` | 索引 `(uid, day)`，游戏和面板读它 |
| `season_state` | 主键 `uid` | 显示缓存，权威在 Firestore |

**不要存论坛帖子**：Firestore 自带磁盘缓存，再存一份只会多一处不一致。

## 6. agent 的五个只读工具

全部只读，名单见 `contract/tool/AgentTool.java`。服务端分发时按白名单匹配，
名单外一律拒绝。

| 工具 | 入参 | 返回 |
| --- | --- | --- |
| `getUsageSummary` | 区间、分组维度 | `UsageSummary`（合计 + 明细 + 覆盖度 + 费率版本） |
| `getBudgetStatus` | 月份 | `BudgetStatus` |
| `compareAgentCosts` | 区间、排序指标 | `CompareResult` |
| `getForumHighlights` | 模型筛、时间窗、条数 | `ForumHighlights`（官方帖 + 热帖） |
| `getMyThreads` | 时间窗、条数 | `MyThreads`（本人的帖子 + 回复） |

**为什么数字类的问题不做「搜索」而是聚合查询。** 让模型自己捞原始记录再算数，
它迟早算错还当成事实讲出来。所以聚合和口径一起交给它，模型只负责解释。
真正需要检索的只有论坛文本。

**`Coverage` 是必填项。** 任何返回数字的工具都要带 `coverage.daysMissing`，
服务端在拼答案时**强制**把缺失的天写进 `AdviceAnswer.missingData`——
这是「agent 承认自己不知道」的机制保证，不靠提示词。

`uid` **不作为任何工具的参数**：由服务端从 Firebase ID token 里取。

## 7. 结算规则（游戏侧）

1. **今天永远不结算**——当天还没结束。
2. **幂等**：同一天算两次不能发两次资源。落点是 `DailyUsage.settled` 标记
   加一个余额更新事务。
3. **补齐**：三天没开应用，下次打开一次补三天。不依赖 WorkManager 准点触发。
4. **已结算的天不可变**：之后导入的纠正记录生成调整项，不回头改历史。
5. **`lastSettledDay` 和余额以服务端为准**，否则重装、换设备、多开都能重复领。

调用入口只有 `SeasonRepository.settleCompletedDays`，而且它**不接受「结算哪一天」
这个参数**——能算哪些天由状态和数据决定，调用方说不了算。

## 8. 三种资源

| token 类别 | 资源 | 每 100 万 token |
| --- | --- | --- |
| `input` | `ResourceType.INPUT` | 100 |
| `cacheRead` | `ResourceType.CACHE` | 100 |
| `output` | `ResourceType.OUTPUT` | 100 |

**三种资源互不通兑**——这是故意的：缓存命中单价只有原价的十分之一左右，如果按
原始 token 数换同一种资源，「多用缓存」就成了刷资源的漏洞。

换算率放实现里，**不要写进 `contract` 包**：它是待调整的平衡参数，不是接口。

**TBD**：各类防御塔和城墙的建造成本（哪种资源、多少）还没定，定之前游戏里
只有一种可造的塔。

## 9. 还没定的事（TBD）

| 事项 | 谁定 | 什么时候 |
| --- | --- | --- |
| 塔和墙的建造成本表 | 刘宗润 | Beta 之前 |
| 热帖排序公式（`rankScore` 怎么算） | 汪庭栋 | 论坛实现时 |
| 微美元 → 人民币的汇率与四舍五入 | 张莉（数据侧算） | 导入实现时 |
| 日志文件的具体格式（三家各要解析什么） | 张莉 | 导入实现时 |
| 建议服务部署在哪（本地 / 云） | 刘宗润 | 接服务时 |

**汇率归数据侧**：微美元 → 人民币的换算写在 `data/local` 或 `contract` 之外的
数据侧代码里，对外只给人民币金额（或直接给一个 `formatCny(long micros)` 之类的
结果）。界面不做乘法，只做显示。理由和大纲 §12 的口径一致——屏幕上出现的金额
必须只有一个来源，否则同一个数字会在两个页面显示成两个值。

汇率是**展示用**的，不影响计价：计价全程用微美元（`long`），只在最后一步换成人民币。

## 10. 和早期大纲/幻灯片的差异

大纲和 PPT **不打算返工**，这里只是记一笔，免得以后有人拿大纲逐字对代码发现对不上：

| 差异 | 说明 |
| --- | --- |
| 工具数量 | 大纲 §5 写 “four read-only tools”，实现里是五个（多了 `getMyThreads`） |
| 提供方命名 | 大纲 §12 与 PPT 里的 “ZCode / Z.ai”，代码里统一叫 **GLM**（定价链接仍用 Z.ai 那份） |
| 游戏 HUD | PPT 上是一个资源数字，实现里是三个计数（输入 / 缓存 / 输出互不通兑） |
| 敌人来向 | 实现是「只从一侧来袭、塔由玩家在网格上自行放置」，比大纲的示意图更具体 |

**判断标准：大概贴切就行，不要求逐字一致。** 大纲是计划书，实现是落地结果，
两者有出入是正常的，答辩时按实现讲。
