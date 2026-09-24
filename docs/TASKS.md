# 待办与验收标准

三个人各做一块。这份文档回答三个问题：**我该做什么、怎么算做完、卡住了找谁。**

> 给用 AI 读代码的人：契约字段的含义以 `app/src/main/java/com/mobilegroup20/tokentrail/contract/`
> 下的类注释为准，本文只讲「要做什么」。每个包还有一份 `package-info.java` 写该放什么。
> 接口的完整方法列表见 `docs/CONTRACTS.md`，或直接看 `data/repository/` 下的五个接口。

## 0. 所有人先看这四条

1. **只用接口，不用实现。** 拿对象走 `RepositoryProvider.usage()` 这样的调用，
   不要在界面里 `new` 别人的实现类。原因见 [`README.md` §2](README.md#2-依赖方向只有一个)。
2. **改 `contract/` 里的字段要先在群里说。** 那是三个人共用的，悄悄改会让别人的代码
   编译不过。改代码和改 `CONTRACTS.md` 放同一个提交里。
3. **每个新类上面写清楚：归谁、干什么、有什么坑。** 注释是写给队友（和他的 AI）看的，
   不是写给自己回忆的。
4. **金额一律用微美元（`long`）算，`double` 一次都不要出现。** 换成人民币只在最后
   显示的那一步做，而且只在数据侧做（见 `CONTRACTS.md` §9）。

---

## 1. 张莉 —— 数据侧

大纲里的分工：认证、用量日志导入、Room/Firestore、版本化定价、预算、面板。
**这一块是整条链路的地基**，另外两个人的界面全部依赖它给出的数字。

### 1.1 要实现的接口

`data/repository/UsageRepository.java`（7 个方法）和
`data/repository/BudgetRepository.java`（3 个方法）。签名照抄，别改。

| 方法 | 干什么 | 关键点 |
| --- | --- | --- |
| `importCalls(uid, calls)` | 导入一批原始记录 | **按 `UsageCall.id` 去重**，靠 Room 唯一索引 + `OnConflictStrategy.IGNORE`，返回 -1 即重复。`ImportResult` 三个计数要如实填：新增 / 重复 / 拒绝 |
| `dailyUsageFor(uid, day)` | 某天的汇总 | 只返回 `Source.IMPORTED` 的记录 |
| `dailyUsageIn(uid, from, to)` | 区间的汇总 | 区间**两端都含** |
| `monthTokens(uid, month)` | 某月四类 token 合计 | 游戏侧结算读它 |
| `summary(uid, from, to, groupBy)` | 面板用的汇总 | `Coverage` 必须填对：有数据的天数和缺失的天数 |
| `compare(uid, from, to, metric)` | 三家对比 | 只比算得出来的量（token、费用、单位成本），**不比「哪个模型更聪明」** |
| `rateFor(provider, model, day)` | 查某天的费率 | 按 `effectiveFrom` 取「不晚于该天的最近一版」，见 `PricingRate` |
| `budgetOf` / `saveBudget` / `status` | 预算上限 | 只存上限，**花销从来现算**，不存「已花」 |

### 1.2 放哪

`data/local/`（Room 三张表，见 `CONTRACTS.md` §5）、`data/remote/`（Firestore 同步）、
`data/importer/`（日志解析）。实现类写完后在 `RepositoryProvider` 里接上。

### 1.3 汇率

微美元 → 人民币的汇率、四舍五入由**你这边**定，写在数据侧；界面只显示结果，
不自己做乘法（`CONTRACTS.md` §9）。计价本身全程用微美元，汇率只影响显示。

### 1.4 验收标准

- [ ] 同一个日志文件导入两次，`inserted` 第二次是 0，`skippedDuplicates` 等于第一次的条数，
      总额不变。
- [ ] 一笔 3 美元/百万 token 的记录，300 万 token 算出来正好是 9 美元，
      不出现 8.999999。
- [ ] 改一版费率后重算，历史那几天的金额**不变**，`DailyUsage.rateVersion` 跟着变。
- [ ] 查询只返回本人的记录：登 A 账号看不到 B 的任何数字。
- [ ] `Coverage.daysMissing` 在缺数据的日子里真的会涨（不是永远 0）。
- [ ] 空区间、反向区间、只有一天，三种情况都不崩。

`ContractMathTest` 里已经有 12 个测试盯着这些口径，实现时对照着看。
**卡住了找谁**：费率区间和 `Coverage` 的口径找刘宗润（他写的 `contract/tool/`）。

---

## 2. 汪庭栋 —— 论坛

大纲里的分工：论坛的帖子数据与排序。

### 2.1 要实现的接口

`data/repository/ForumRepository.java`（8 个方法）。**三个桶的可见性不一样，别合并查询**：

| 桶 | 方法 | 可见性 |
| --- | --- | --- |
| 官方帖 | `officialPosts(limit)` | 所有人可读，只有官方账号能发 |
| 热帖 | `hotPosts(since, limit)` | 所有人可读，按热度排序 |
| 我的帖子 | `myPosts(uid)` / `myThreads(uid, since, limit)` | **只有本人可读**，含收到的回复 |

前两个喂给 agent 的 `getForumHighlights`，第三个喂给 `getMyThreads`。
`highlights(modelFilter, since, limit)` 是前两个的合并结果，给界面和 agent 共用。

### 2.2 排序公式（`rankScore`）

**这个由你定**（`CONTRACTS.md` §9 的 TBD）。要求：

- 只看**算得出来的量**——回复数、时间衰减、是否官方。**不要**引入点赞数这种
  需要额外交互的数据，界面上没有点赞按钮。
- 公式写成一个纯函数，能脱离网络跑单元测试，并写清楚各项权重和为什么这么定。
- 排序结果稳定：同样的一批帖子，两次调用顺序一致（时间相同时要有第二排序键）。

### 2.3 放哪

`data/remote/`（Firestore 读写）+ `ui/forum/`（界面）。
`ui/forum/package-info.java` 里已写好这个包归你。

### 2.4 验收标准

- [ ] `hotPosts` 在数据不变时两次调用顺序一致。
- [ ] `myThreads` 只返回本人的帖子；换账号之后看到的是另一个人的（且互相看不到）。
- [ ] 帖子下能取到回复，回复按时间正序。
- [ ] 发帖 / 回帖后，对应列表**自动刷新**（`LiveData` 该有的行为，不用手动重新加载）。
- [ ] 排序函数有单元测试：官方帖排在前、越新的权重越高、回复多的靠前。

**卡住了找谁**：`ForumPost` / `ForumReply` 的字段含义找刘宗润（`contract/model/`）。

---

## 3. 刘宗润 —— 游戏与 agent

大纲里的分工：塔防游戏、服务端建议服务、五个只读工具、建议效果评估。

### 3.1 塔防引擎（`game/engine/`）

**这个包不许 import 任何 `android.*`。** 玩法逻辑（网格、放塔、敌人路径、波次、
伤害结算）写成纯 Java，这样能脱离模拟器跑单元测试，也方便调平衡。

- 敌人**只从一个方向来**，玩家在网格上**自由放塔**（不是固定炮位）。
- 资源来自结算：`SeasonRepository.settleCompletedDays(uid)` 发资源，
  三种资源互不通兑（`CONTRACTS.md` §8）。
- 波次由玩家点开始，跑起来不能卡住界面（放后台线程或按帧推进）。

**开源**：大纲 §8 写了「an open-source tower-defence sample may supply the wave and
placement loop」——波次与放置循环可以基于现成的开源塔防样例改。用之前记下
**项目名、仓库地址、许可证**，写进报告的开源使用那一节（评分细则里占 15%）。
许可证要能用于课程作业（Apache-2.0 / MIT 这类可以，GPL 要谨慎）。

### 3.2 结算（`SeasonRepository`）

四个方法：`currentSeason`、`settleCompletedDays`、`spend`。
规则见 `CONTRACTS.md` §7 和 `SeasonState` 的类注释，五条都不能绕：

1. 今天永远不结算；2. 幂等；3. 补齐（三天没开一次补三天）；4. 已结算的天不可变；
5. `lastSettledDay` 和余额以服务端为准。

`settleCompletedDays` **故意不接受「结算哪一天」这个参数**——调用方说不了算。

### 3.3 agent（`agent/` + `AdviceRepository`）

- 服务端跑模型调用和函数调用循环，客户端**不持有任何模型 API key**（大纲 §8）。
- 五个工具全部只读，按 `AgentTool` 的白名单分发，名单外一律拒绝。
- 每个返回数字的工具都要带 `Coverage`，缺失的天**强制**写进 `AdviceAnswer.missingData`。
- agent 自己的 token 单独记账（`ownCostThisMonth`），不和被追踪的编码 agent 混在一起。
- 只比费用和 token，**不比输出质量**。

### 3.4 验收标准

- [ ] `settleCompletedDays` 连调两次，第二天余额不变（幂等），返回值第二次的
      `settledDays` 为空。
- [ ] 中间隔三天没打开，一次调用补齐三天，`settledDays` 有三项。
- [ ] 当天的用量**不会**被结算。
- [ ] 资源买塔之后余额真的减少，且余额减不成负数。
- [ ] 一波敌人能开始并打完，过程中界面不卡（能滚动、能点别的东西）。
- [ ] agent 问「为什么这周变贵了」，答案里有具体数字和缺失数据的说明；
      问一个没有数据支撑的问题时，它会承认不知道而不是编。
- [ ] `game/engine/` 下没有 `import android.`。

---

## 4. 三人共担

- 界面风格、无障碍（字号、对比度、TalkBack 标签）、三个模块的联调。
- 联调时的口径问题（两边对同一个数字的理解不一样）当场记进 `CONTRACTS.md`，
  别只在聊天里说。
- 每人都要往 GitHub 提交（评分要看提交记录）。

## 5. 里程碑（照大纲 §9 的周次）

| 周次 | 目标 | 检查点 |
| --- | --- | --- |
| 4--7 | 用量导入、Room/Firestore、版本化定价、人民币显示、面板 | 三家的样例调用都按正确的日期费率计价，重复导入不增加 |
| 8--9 Alpha | 登录 → 加 agent → 导入日志 → 估算费用 → 面板这条链路能跑通 | 真实日志和样例数据能区分开 |
| 10--12 Beta | 预算、会话；塔防循环（赛季、token→资源、玩家触发波次）；agent 面板接一个模型三个工具 | agent 能带证据回答用量问题并承认缺失数据；一波敌人能开始并打完且不卡界面 |
| 13--15 Final | 建议效果与 API 成本的评估、赛季平衡、无障碍、账号隔离验证 | 端到端演示、报告、视频；答案不编造用量、不控制编码 agent |
