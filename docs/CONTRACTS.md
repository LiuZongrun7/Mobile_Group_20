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
| ~~塔防游戏~~（已移出）、建议服务、agent 工具 | 刘宗润 | `SeasonRepository` `AdviceRepository` | ~~`game/engine`、`game/view`~~（在 `../../TokenTrail_Game/`）、`agent`、`backend/` |

界面（UI/UX）、无障碍和集成三人共担。

**依赖方向只有一个**：界面 → `RepositoryProvider` → 接口 → 实现。
谁都不许 import 别人的实现类，只认接口。这样三个人的模块可以同时开工。

---

## 2. 命名口径

- 提供方统一写 **MiMo**（小米那一侧），界面字符串是 `"Xiaomi MiMo"`。
  大纲早期这里写的是智谱 GLM，跟 MiMo 不是同一家，2026-09-26 按新大纲改掉了；
  作为**提供方名**，代码和文档里不要再出现 `glm`、`zhipu`、`z.ai`、`zai`。
- **`ZCode` 是客户端，不是提供方，继续用。** 大纲 §2 的 “ZCode supports
  Xiaomi MiMo as a model provider” 就是这个层级关系：ZCode 装在用户机器上，
  MiMo 是它背后计费的提供方。`https://zcode.z.ai/...` 是 ZCode 自己的配置文档，
  照旧引用，别因为域名里有 `z.ai` 就删。
- 枚举值固定 `OPENAI` / `MIMO` / `DEEPSEEK`，见 `Provider`。
- **提供方和模型是两个层级**：定价的最小单位是模型（`mimo-v2.6-pro`、
  `mimo-v2.6-flash` 不是一个价）。所以每条记录 `provider` 和 `model` 都要有。
- 界面上显示的字符串一律取 `Provider.displayName`，不要另写。
- **枚举改名要配一次清库或者迁移。** Room 存的是 `provider.name()`（见
  `LocalConverters`），所以 `GLM` → `MIMO` 之后，老库里 `provider='GLM'` 的行
  读出来会抛 `IllegalArgumentException`。目前还没有装出去的版本，卸载重装即可；
  等有真实用户数据了，这种改名必须配一次 Room 迁移把值一起改掉。

## 3. 时间口径

| 事项 | 规定 |
| --- | --- |
| 时区 | `Asia/Shanghai`，只在 `TimeUtils.ZONE` 定义一次 |
| 「天」 | 字符串 `yyyy-MM-dd`，跨 DB / JSON / 接口都用这个形式 |
| 「月」 | 字符串 `yyyy-MM` |
| 存储的时刻 | UTC 毫秒（`long`）。只在换算成「哪一天」时经过 `TimeUtils` |
| 日边界 | `TimeUtils.today()` / `yesterday()` |

**各家账单的切天口径不一样，我们统一按 `Asia/Shanghai`。** 这句话原来写的是
「各家都按 UTC 切」，那是错的，2026-09-26 拿 DeepSeek 的真实用量导出验过之后改掉：

| 来源 | 它自己的天怎么切 | 和我们的差 |
| --- | --- | --- |
| DeepSeek 用量导出 | `+08:00`（已验证） | 0 |
| OpenAI Usage API | UTC（`bucket_width=1d` 时按 UTC 收口） | 最多 8 小时 |

差的那部分对外**统一说成「估算误差」**，界面要说明，不要当成 bug 去修。但内部
必须一律走 `TimeUtils.ZONE`——两套天混在一个 `daily_usage` 里，才会真的对不上账。

**金额有一模一样的问题。** DeepSeek 导出的金额是人民币、按 `+08:00` 的天汇总的，
而项目内部计价用微美元（§9）。换算和原始金额都留在 `UsageCall` 上
（`costMicros` / `costCurrency` / `nativeCostMicros`），所以对账时能一行一行对回账单，
不需要重算。

## 4. 数据流：三层，别搞混

```
provider API ──拉取─┐
                    ├─→ UsageCall（原始，只读，唯一事实来源）
本地日志 ────解析───┘         │        ↑ 这两条产在**客户端**
                             ├─去重靠 id 唯一索引，重复导入不新增
                             │
                             └─→ DailyUsage（按 天×提供方×模型 的派生汇总，可重算）
                                      │
                                      └─→ 结算 → SeasonState（余额，服务端权威）

编码 agent ──转发──→ API 中转（服务端）──→ 上游 provider
（cc-switch）              │              ↑ 这一条产在**服务端**
                           └─→ usage 从响应里旁路抽，同样是 UsageCall 粒度的原始记录
```

- **入口有三条，产物只有一个形状，但落点不同。** 「拉取」和「导入」在**客户端**
  产出 `UsageCall`（落 `data/remote/` / `data/importer/`），**「中转」在服务端**产出
  （请求经我们的服务器原样转发，usage 从响应里旁路抽取，直接落服务端的库）。
  三条路的下游——去重、汇总、结算、游戏资源——**规则完全一样**，
  这正是 `UsageCall` 这个形状存在的意义：**谁在哪儿解析不影响口径**。
  哪家能走哪条路见 [`DATA_SOURCES.md`](DATA_SOURCES.md)，中转的契约见
  [`RELAY_API.md`](RELAY_API.md)。
- **中转路径上客户端是只读的**：日汇总由服务端派生（`GET /usage/daily`）、
  余额和结算也在服务端（见 §7）。客户端不再自己拿 `UsageCall` 滚日汇总。
- **中转只多一件事，但那一件是必需的：`cacheWrite` 这个桶第一次有了真实来源。**
  拉取和导入两条路都拿不到它（OpenAI 不单列、DeepSeek 不计缓存写入），
  而 `TokenBundle` 要求四个桶都能计。这是引入中转的主要理由，不是「顺便做了个代理」。
  代价是用户的上游 key 落在服务端，理由和风险见 [`DATA_SOURCES.md`](DATA_SOURCES.md) §5。
- **`UsageCall` 是唯一事实来源**，`DailyUsage` 是派生数据，删了能重算。
  所以纠正费率、按会话分析、回答「为什么涨了」都回原始表，不要只存汇总。
  （例外：桶粒度的行没有会话信息，做不了按会话分析，见 `DATA_SOURCES.md` §2。）
- **只有 `Source.IMPORTED` 的记录进 `DailyUsage`**。演示用样例数据
  （`Source.SAMPLE`）只用于界面演示，不进汇总、不进预算、不换游戏资源。
- 费率纠正后重算汇总，`DailyUsage.rateVersion` 要跟着变。
- **成本有两条来路，来源自带金额优先。** 来源给了钱数（DeepSeek 导出、OpenAI 的
  `/v1/organization/costs`）就用它，并打上 `DailyUsage.RATE_VERSION_FROM_SOURCE`；
  没给才按那一组所在那天生效的费率现算。一整组都自带金额才走来源，一半有一半没有
  就整组回落价目表——不把两种口径加进同一个数，那样对账时两边都对不上。
- **「算不出价」和「花了 0 元」必须分得开。** 两条路都拿不到金额时
  `DailyUsage.rateVersion` 留 `null`，界面显示「价格未知」，**不许显示 ¥0**。
  这条是整个计价层的底线：数字看着没错、结论是错的，是最难发现的一类 bug。

## 5. 存储契约

### 服务端（自建）

> **2026-09-26 起不再是 Firebase。** 原来这节写的是 Firestore + Firebase Auth，
> 现在改成团队自己的服务器（VPS）。**架构上有一处实质变化，别按老写法接**：
> Firebase 是客户端直连数据库、靠安全规则拦；自建之后**客户端只跟我们自己的
> HTTP 接口说话**，不再直连任何数据库。所以「安全规则」这个概念没有了，
> 取而代之的是**服务端每个请求都要验身份、每次读都要按调用者的账号过滤**。
> 下面的集合名和字段沿用原来的设计（数据形状没变），但**它们是服务端的表/文档，
> 不是客户端能直接摸到的东西**。
>
> ~~服务器的具体形态（语言、数据库、部署方式）还没定~~ —— **2026-09-27 已定并部署**：
> FastAPI + SQLite(WAL) + Nginx + systemd，代码在 `backend/`，见
> [`../backend/README.md`](../backend/README.md)。中转与结算的接口契约见
> [`RELAY_API.md`](RELAY_API.md)。
>
> **下面表里的「集合名」是目标形状，不是实际表名。** 已落地的实际表名和字段
> 以 `RELAY_API.md` 为准（`relay_keys` / `relay_secrets` / `relay_usage` /
> `season_balances` / `season_settlements`；论坛那几张见 `FORUM_API.md`）。
> 哪些数据已经真的在服务端、哪些还在客户端，见本节末尾那张状态表——
> **别照着这张表假设它已经全部实现了**。

| 资源 | 可见性 | 谁写 | 谁读 |
| --- | --- | --- | --- |
| `users/{uid}/usageCalls/{callId}` | 仅本人 | 服务端（客户端导入时提交，**或中转转发时旁路写入**） | 客户端（经接口）+ 建议服务 |
| `users/{uid}/dailyUsage/{day}_{provider}_{model}` | 仅本人 | 服务端派生 | 客户端（经接口）+ 建议服务 |
| `users/{uid}/seasons/{yyyy-MM}` | 仅本人 | 服务端结算事务 | 客户端（经接口）+ 建议服务 |
| `users/{uid}/budgets/{yyyy-MM}` | 仅本人 | 客户端（经接口） | 客户端（经接口）+ 建议服务 |
| `forum/posts/{postId}` | **所有登录用户可读** | 作者 | 客户端（经接口）+ 建议服务 |
| **官方帖**（`source=OFFICIAL`） | **所有登录用户可读** | 服务端的采集任务 | 客户端（经接口）+ agent |
| `forum/posts/{postId}/replies/{replyId}` | 同上 | 作者 | 客户端（经接口）+ 建议服务 |
| ~~`pricing/rates/{provider}_{model}_{effectiveFrom}`~~ | **已实现**（2026-09-27）：表名 `pricing_rates`，接口见 [`RELAY_API.md`](RELAY_API.md)。**种子只含 DeepSeek**（实测过的那组），另外两家空着 = 界面显示「价格未知」 | 管理端 / 服务端拉取 | 客户端（经接口）+ 建议服务 |

新版论坛的 HTTP 接口、图片、点赞和 RSS 新闻契约见 [`FORUM_API.md`](FORUM_API.md)。所有社区帖子（包括自己的帖子）公共可读；“我的帖子”只是作者筛选。

#### 中转已落地的表（2026-09-27）

上表是**目标形状**；中转这一块已经实现，实际表名和字段见
[`RELAY_API.md`](RELAY_API.md)。三张表，**分开是刻意的**：

| 表 | 主键 | 说明 |
| --- | --- | --- |
| `accounts` | `user_id` | **身份。**APP 账号（用户名 + scrypt 密码哈希）。所有东西都挂在它下面 |
| `account_sessions` | `token_hash` | 会话 token（只存 sha256 与到期时间，明文只在签发那一刻出现） |
| `relay_keys` | `key_hash` | **一条通道，不是身份。**它挂在 `accounts.user_id` 下面；`key_hash = sha256(relay key)`，明文不落库 |
| `relay_secrets` | `key_hash` | 上游地址 + 上游密钥（**明文，转发要注入**）。单独一张表，这样「查身份」那条路径永远不碰密钥 |
| `relay_usage` | — | 逐次转发的四桶用量，归属列是 **`user_id`**，索引 `(user_id, created)` |
| `season_balances` / `season_settlements` / `budgets` | — | 余额、结算流水、预算上限。归属列同样是 **`user_id`** |

> **2026-02 改：身份只有一个，就是账号；relay key 只是「怎么把用量交上来」的一条通道。**
>
> 这一节原来写的是「中转的身份和论坛的身份必须是两套」（论坛 uid 来自团队账号 token，
> 中转 uid 来自 relay key 的 sha256）。**那个结论当时是对的、现在是错的**，原因不是
> 当时判断失误，而是**账号从哪来**这件事变了：那时 App 里没有自己的账号，
> 只好让 relay key 自带身份；现在 `accounts` 表就是 App 用户的账号，
> 用户注册一次，登录论坛、读用量、玩游戏结算都是同一个 `user_id`。
>
> 旧模型的实际代价（这是要改的真正理由，不是「整齐」）：
> 换个 relay key 就等于换个人，用量、余额、赛季跟着清零——而换 key 本来只是
> 换一条提交用量的路。修法不是加映射表，而是**把归属列改成 `user_id`**：
> `relay_store.owner_of(secret)` 把任意凭据（账号 token **或** relay key）
> 解成账号的 `user_id`，之后所有读写都按它。原来那个「relay key ↔ 账号绑定表」
> 的问题因此不存在了——**没有两个身份，就不需要绑定**。
>
> 认不出账号的老行（「自带身份」时代注册的 key）退回它自己的 `key_hash` 当身份，
> 自成一户：**宁可让它孤立，也不并到别人账上**。

**用量私有、论坛公开，这两类的校验写法完全不同，资源必须分开。** 把两者混进同一
处，就会出现「为了读热帖而把所有人的帖子都放开」这种口子。
中转的用量属于前者（仅本人），所以它和 `forum/*` 是两张不同的表。

**为什么这个改动反而简单了**：Firebase 那套要求客户端自己算「谁能读什么」，
错一条规则就是一起漏；自建之后客户端**没有任何直接读库的能力**，过滤只发生在
服务端一处，漏也漏不到客户端去。

### Room（本地）

| 表 | 主键 / 索引 | 说明 |
| --- | --- | --- |
| `usage_call` | **`id` 唯一索引** | 去重的落点：`OnConflictStrategy.IGNORE`，返回 -1 即重复 |
| `daily_usage` | 主键 `(uid, day, provider, model)` | 索引 `(uid, day)`，游戏和面板读它 |
| `season_state` | 主键 `uid` | 显示缓存，权威在服务端 |

**不要存论坛帖子**：论坛是读多写少的公共数据，本来就该由服务端按请求返回，
在本地再存一份只会多一处不一致。

### 2026-09-27：哪些已经搬到服务端，哪些还没有

**方向是「服务端是唯一数据源，App 不再持有业务数据」**，但这件事是**分步做的**，
所以下面这张表是当前的真实状态——写文档时别把没做的说成做了：

| 数据 | 权威在哪 | 状态 |
| --- | --- | --- |
| 转发来的用量（逐次调用、四桶） | **服务端** `relay_usage` | ✅ 已实现并部署 |
| 逐次调用记录的读接口（`UsageCall` 形状） | **服务端** | ✅ `GET /api/relay/usage/calls`，`call:` 前缀的 id 在写入时生成 |
| 按天的用量汇总（`DailyUsage` 形状） | **服务端派生** | ✅ `GET /api/relay/usage/daily` |
| 资源余额、`lastSettledDay`、已结算流水 | **服务端** | ✅ `season_balances` / `season_settlements`，见 §7 |
| 结算的**判断**（哪些天该算） | **服务端** | ✅ 客户端一行都没有 |
| App 侧读余额的接口 | 服务端（`HttpSeasonRepository`） | ✅ 已实现，12 个 HTTP 契约测试 |
| 日志**导入**出来的用量（`data/importer/`） | 本地 Room | ⛔ **还没搬**，`RoomUsageRepository` 仍是 `USE_STUBS=false` 时的实现 |
| `budgets` | **服务端**（2026-09-27 已建） | ✅ 表 + 接口 + App 侧 `HttpBudgetRepository`（12 个 HTTP 契约测试）都齐了 |
| 论坛帖子与评论 | 服务端 | ✅ 一直如此（论坛从来不在本地存） |

**所以「App 内为主数据源」这句话现在只对「导入路径 + 预算」成立。**
中转路径上的用量、日汇总、余额、结算都已经在服务端，客户端只读。

**搬家还没有一步到位的原因**：`UsageRepository` 的七个方法里既有读（`dailyUsageIn`）
又有写（`importCalls`），而 `importCalls` 的产物是**客户端解析出来的** `UsageCall`。
把读改成服务端、写留在本地，会得到一个「读一天的数据要打两次不同来源」的中间态。
所以这一步要等导入路径也改成「客户端只上传原始文件、服务端解析」之后再做，
那时 `UsageRepository` 才会整体变成 HTTP 实现，Room 退成离线缓存。

**已落地的服务端表**（表名和字段见 [`RELAY_API.md`](RELAY_API.md)）：
`relay_keys` / `relay_secrets` / `relay_usage` / `season_balances` / `season_settlements`。

**一处将来要改的前缀**：赛季接口现在挂在 `/api/relay/season*` 下面，
但**结算不是中转专属的**（导入来的用量也要结算）——挂在那里只是因为身份用的是
relay key。等 `UsageRepository` 也搬上去之后，这个前缀应该提到 `/api/season`。
**要改的是前缀，不是内部逻辑**（`Seasons` 类只认 uid，不知道 relay 的存在）。

## 6. agent 的五个只读工具

> **2026-09-27：官方帖不再是空的。** 论坛的官方帖**就是 `news` 表里的资讯**
> 以 `ForumPost` 的形状返回（`source=OFFICIAL`）——**不另存一份进 `posts`**，
> 所以没有双份存储和同步问题。代价是官方帖**不能被评论**（它们在 `news` 表里，
> 没有 `post_id` 可挂评论），这是有意的：官方帖是公告，讨论去对应的社区帖。
>
> 它**不是「拿别人的文章冒充官方帖」**：那些文章是我们自己配的采集任务为这个
> 项目采的，`source_name` / `original_url` 跟着帖子一起返回，界面上看得出原始出处。

> **2026-09-27 现状：工具循环已接上。** 服务端有 `/api/relay/agent/ask`
> （用**我们自己的** DeepSeek key、账本独立），模型能调下面这五个工具，
> 服务端执行完把结果喂回去。实测能正确回答「某区间用了多少 token、花了多少」
> 并**主动说出哪些天没有记录**。见 [`RELAY_API.md`](RELAY_API.md)。
>
> **`getMyThreads` 现在能查了（2026-02）。** 原来它查不了，原因写在这里：
> 「智能体的身份是 relay key，而帖子的 `author_uid` 是团队账号 uid，
> 没有映射就答不了」。**那个映射现在不需要了**——身份统一成账号之后，
> 智能体从账号 token 解出的 `user_id` 就是 `posts.author_uid`，直接 join。
>
> 顺带一条边界：智能体的凭据也换成了**账号 token**，不再是 relay key。
> 理由是同一个方位问题——用户可能压根没配过中转，但他一定登录过 App；
> 而 relay key 只该出现在中转那条路上。

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

`uid` **不作为任何工具的参数**：由服务端从请求携带的**账号 token** 里解出来，不信客户端传的。

## 7. 结算规则（游戏侧）

**2026-09-27：这一节已经实现，而且整套规则跑在服务端。**
实现见 `backend/tokentrail_forum/seasons.py`，接口见
[`RELAY_API.md`](RELAY_API.md)，五条规则各有对应测试
（`backend/tests/test_seasons.py`，20 个用例）。

1. **今天永远不结算**——当天还没结束。
2. **幂等**：同一天算两次不能发两次资源。落点是 `season_settlements` 的主键
   `(uid, day)` 加一个余额更新事务（**同一事务**，不是先查再写）。
3. **补齐**：三天没开应用，下次打开一次补三天。不依赖 WorkManager 准点触发。
4. **已结算的天不可变**：之后落进来的纠正记录不回头改历史。
5. **`lastSettledDay` 和余额以服务端为准**，否则重装、换设备、多开都能重复领。

调用入口只有 `SeasonRepository.settleCompletedDays`，而且它**不接受「结算哪一天」
这个参数**——能算哪些天由状态和数据决定，调用方说不了算。
服务端的 `POST /season/settle` 保持同一个签名（**请求体是空的**）。

### 为什么判断逻辑必须在服务端

客户端算的话，**重装一次就能把同一批 token 再领一遍资源**——这不是理论风险。
所以 `HttpSeasonRepository` 里**没有一行「哪些天该结算」的判断**，
它只把服务端的结果翻成 `SettlementResult`：「今天不算」这类规则
**不可能在客户端被绕过，因为那段代码不在客户端**。

### 换算与取整（服务端实现）

- 每 100 万 token 换 100 个资源 → 1 个资源 = **10000 token**
  （服务端 `TOKENS_PER_UNIT`，和 App 侧 `MainActivity.TOKENS_PER_UNIT` 同口径，两处必须一起改）。
- **先按天加总再取整**，余数丢弃：一天里多个 `(provider, 模型)` 各自取整会把零头丢很多次
  （三行各 5000 token，分着算是 0，加总是 1）。
- **跨天不累加余数**——攒余数会让「换个顺序结算」得到不同的余额。
- 新账号第一次结算**上限 `MAX_BACKFILL_DAYS = 400` 天**：没有上限的话，
  一个刚注册的账号会把全部历史一次算完、一次造出一个巨大的余额。

### 结算不需要价格

资源是从 **token 数**换来的，和花多少钱无关。所以价目表（`pricing/rates`）
还没建**不挡结算**——`costMicros` 继续是 `null`，界面显示「价格未知」，余额照算。

## 8. 三种资源

| token 类别 | 资源 | 每 100 万 token |
| --- | --- | --- |
| `input` | `ResourceType.INPUT` | 100 |
| `cacheRead` | `ResourceType.CACHE` | 100 |
| `cacheWrite` | `ResourceType.CACHE`（和 `cacheRead` 合并） | 100 |
| `output` | `ResourceType.OUTPUT` | 100 |

**`Bundle.input` 是「未命中缓存的那部分输入」，不是输入总量。** 一个字面量口径
写清楚在这里，因为两种读法都讲得通，而差别很大：

- 提示词在账单上分三段：**未命中缓存的 + 命中缓存的 + 写入缓存的**。
  `input` 只装第一段（`TokenBundle.input` 的注释："按原价计费的那部分提示词"）。
- 所以 `input + cacheRead + cacheWrite` 才是"输入总量"；
  `input` 本身就等于 `cacheRead + cacheWrite` 是**错的**。
- 读错的代价：`TokenBundle.total()` 会多算三成左右（桩数据里 `input` 是四个桶中
  最大的一块，占一半以上），结算发出去的 INPUT 资源也跟着多。
- 判据在计价里：四个桶是**分开定价**的（$3 / $0.30 / $3.75 / $15 每 1M）。
  如果 `input` 已经含了 `cacheRead`，那 `cacheRead` 就会被算两遍钱。

**三种资源互不通兑**——这是故意的：缓存命中单价只有原价的十分之一左右，如果按
原始 token 数换同一种资源，「多用缓存」就成了刷资源的漏洞。
`cacheRead` 和 `cacheWrite` 合成一种资源，是因为它们都是"缓存"这件事的两面
（且 `ResourceType` 只有三个值）。**界面上也只有三个桶**，和这三行一一对应：
`Input (uncached)` / `Cache (read + write)` / `Output`。
四个 token 字段 vs 三个显示格，差的正是这一次合并——**合并只发生在显示层**，
数据里仍然分开记、分开计价。

换算率放实现里，**不要写进 `contract` 包**：它是待调整的平衡参数，不是接口。

**建造成本**已经定了，放在 `game/engine/ShopCatalog`（实现里，不进契约）：
箭塔 2×2 吃 CACHE 25 + OUTPUT 10、城墙 1×1 吃 INPUT 5、核心免费（只用来挪）。
改价格只动那个文件。**已知的不平衡**：OUTPUT 是最小的一个桶（约 10%），
按现在的价它是塔的瓶颈，等于"输出 token 最贵 → 塔最难得"——这是有意的，
但真机上玩两局之后再调。

## 9. 还没定的事（TBD）

| 事项 | 谁定 | 什么时候 |
| --- | --- | --- |
| ~~塔和墙的建造成本表~~ | 刘宗润 | **已定**，见 §8，数值在 `game/engine/ShopCatalog` |
| 热帖排序公式（`rankScore` 怎么算） | 汪庭栋 | 论坛实现时 |
| 微美元 ⇄ 人民币的汇率与四舍五入 | 数据侧 | **代码就位、数值还是占位**：`data/Money`。`CNY_PER_USD_MICROS = 7_100_000` 现在**服务端也用了同一个值**（`pricing.py`），两处必须一起改；`RATE_AS_OF` 交之前仍要换成真实日期 |
| ~~成本算不出来（没有价目表）~~ | 数据侧 | **已解决**（2026-09-27）：`pricing_rates` 表 + 接口 + DeepSeek 实测种子。没实测过的两家**故意空着**——编数据会让成本看起来能用、实际全错 |
| 日志文件的具体格式（三家各要解析什么） | 张莉 | 导入实现时 |
| **每家的用量取数方式**（拉取 vs 导入，官方 vs 私有接口） | 数据侧 | **现状已查清**，见 [`DATA_SOURCES.md`](DATA_SOURCES.md)；只差实测那三条 |
| ~~`UsageCall` 装不装得下「时间桶」粒度的行~~ | 组里 | **已定：装**（方案 A）。表结构不动，`id` 用 `call:` / `bucket:` 前缀区分粒度，见 [`DATA_SOURCES.md`](DATA_SOURCES.md) §2 |
| ~~用户 provider 凭据在客户端怎么存~~ | 数据侧 | **已定：设备本机明文**。但 **2026-09-27 反转了一半**：转发用的上游 key 改为存服务端，理由和风险见 `DATA_SOURCES.md` §5；「查用量」那类凭据仍然不出设备 |
| ~~服务器的具体形态（语言、数据库、部署方式）~~ | 组里 | **已定**：FastAPI + SQLite(WAL) + Nginx + systemd，已部署，见 [`../backend/README.md`](../backend/README.md) 和 [`RELAY_API.md`](RELAY_API.md) |
| ~~结算的判断放在哪一侧~~ | 游戏侧 | **已定：全在服务端**（2026-09-27）。客户端 `HttpSeasonRepository` 里一行判断都没有，§7 |
| **服务端的 `budgets` 表** | 数据侧 | **做完了**（2026-09-27）：表 + 接口 + App 侧 `HttpBudgetRepository`。**花销仍算不出来**（没有价目表），所以 `spentMicros` 是 0 而 `pricingAvailable` 是 false，界面要显示「价格未知」而不是「花了 0 元」 |
| **逐次记录的读接口**（`GET /usage/calls`，`UsageCall` 形状） | 汪庭栋 | **已做**（2026-09-27）：`id` 在写入时生成（`call:` 前缀），读取时不拼——同一毫秒的两个请求会拼出同一个去重键。迁移前的无 id 老行会被跳过 |
| **游戏接服务端余额的方式** | 游戏侧 | **做完了**（2026-09-27）：读——服务端优先，**只在「没配置中转」时**回落本地演示换算（判据不是「余额为 0」，那样会把「确实没攒到」和「查不到」混成一种），HUD 上标明来源（`SeasonWallet`）。写——本地乐观扣 + `SpendSync` 对账，**服务端没确认时重新拉余额而不是自己回滚**（自己加回去在「服务端扣了但响应丢了」时会双重记账） |
| ~~`UsageRepository` 整体搬去服务端~~ | 数据侧 | **拆分着做**：读和写要一起搬（`importCalls` 的产物是客户端解析的），所以等导入路径也改成「客户端只传原始文件、服务端解析」之后再做，见 §5 |
| ~~建议服务部署在哪（本地 / 云）~~ | 刘宗润 | **已定**（2026-09-27）：**没有「建议服务」这个东西，只有一个 AI 智能体**（之前文档这么叫是我的用词问题）。接 DeepSeek，key 放服务端（`FORUM_AGENT_KEY`），跑在同一个 FastAPI 进程里。**它和中转完全分开，账本也分开**（`agent_usage` vs `relay_usage`） |

**汇率归数据侧**：换算写在 `data/Money`，**全项目只有那一处**。对外只给人民币金额
（`Money.formatCny(long usdMicros)`），界面不做乘法、只做显示。理由和大纲 §12 的
口径一致——屏幕上出现的金额必须只有一个来源，否则同一个数字会在两个页面显示成两个值。

**换算是双向的，这是被真实数据改掉的一半**。原话是「计价全程用微美元，只在最后一步
换成人民币」，前提是各家账单都以美元计价。DeepSeek 的导出推翻了它：账单金额是
**人民币**（`currency` 列写死 `CNY`）。所以多了一个反方向——导入时人民币 → 美元
（`Money.toUsdMicros`）。两个方向必须共用同一个汇率常量，否则同一笔钱在
「导入」和「展示」两次换算后对不上，而且没人知道该信哪个。

计价本身仍然是微美元（`long`），`double` 一律不许出现在金额计算里，
理由见 `PricingRate` 的类注释。

## 10. 和早期大纲/幻灯片的差异

大纲和 PPT **不打算返工**，这里只是记一笔，免得以后有人拿大纲逐字对代码发现对不上：

| 差异 | 说明 |
| --- | --- |
| ~~工具数量~~ | **已消失**（2026-09-26）：大纲 §5 第 96 行现在自己写的就是 “five read-only tools”，把 `getMyThreads` 也算进去了，和实现一致 |
| ~~提供方命名~~ | **已消失**（2026-09-26）：大纲改版后第三家统一叫 **Xiaomi MiMo**（`Provider.MIMO`），代码不再是 `GLM`。`ZCode` 还在，但它是客户端，见 §2 |
| 游戏 HUD | PPT 上是一个资源数字，实现里是三个计数（输入 / 缓存 / 输出互不通兑） |
| 敌人来向 | 实现是「只从一侧来袭、塔由玩家在网格上自行放置」，比大纲的示意图更具体 |
| 战场网格 | PPT 的战场是按手机画的**示意图**（约 10×9 格、塔画成 1 格、一屏看得完）；实现是固定 **40 列 × 18 行**的网格，塔占 **2×2**、核心 **3×3**、城墙 **1×1**，**横向要拖**（手机一屏约 16 列）。核心在示意图里画的正好也是 3 格，这条是一致的 |
| 战场一屏 | PPT 和大纲都默认「一屏看得完一个战场」；实现是**纵向一屏、横向要拖**。原因：40 列比任何手机都宽，而纵向 18 条路必须一眼看全（漏看一路等于漏掉整局）。取舍写在 `BoardGeometry.COLS` 的注释里 |
| 地面分区 | 大纲和 PPT 都没有提地面分区；实现把 40 列切成三段——左 4 格敌人通道、中间 33 格可放置区、右 3 格山区（`game/engine/Terrain.java`，画面上一区一个颜色）。这是 40 格宽的世界**必然带出来的**：地图变长之后，"哪段能建"必须一眼看得出，不然玩家要一格格试 |
| 核心位置 | 大纲只说"守住核心"；实现里核心**开局摆在靠右、紧挨着山区，而且玩家可以随时挪**（`Battlefield.moveCore`）。理由是核心摆哪儿是开局第一个决定，固定死就少了一层玩法 |
| **用量怎么进系统** | 大纲 §8 写的是「用户把 Codex / ZCode / DeepSeek 侧工具加为 tracked profile，只填一个 **non-secret key label**」（`.tex` 第 90 行），并明确「**No agent password is requested**」「coding-agent passwords and keys are **not required**」（第 169 行）。实现里多了一条**可选的**拉取路径：用户愿意的话可以填一个**只读用途**的 key / token，我们替他拉用量，省掉手动导出。**按这个口径读两者不冲突**：必填的仍然只有那个非机密的 label，凭据是可选项，不给也能用（走导入）；而且我们**始终不要密码**。但报告里必须主动说清楚，别等着被问 |
| 客户端持有 key | `data/remote/package-info.java` 写「客户端**不持有任何模型 API key**」。这句讲的是**建议服务**调模型用的那个 key，**仍然成立、不要删**。用户自己用来查用量的凭据是**新增的另一类**，两类要分开写，否则读起来像矛盾。存法见 §9 的 TBD |

**判断标准：大概贴切就行，不要求逐字一致。** 大纲是计划书，实现是落地结果，
两者有出入是正常的，答辩时按实现讲。
