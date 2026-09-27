# 中转服务接口契约

> **2026-09-27 新增。** 这一层让 TokenTrail 站在编码 agent 和上游 provider 之间。
> 它推翻了 `CONTRACTS.md` §5 原来「客户端只跟我们自己的 HTTP 接口说话、不接受代理流量」
> 的定性，改动理由和影响面记在 `DATA_SOURCES.md` §5。

## 为什么要有中转

原来只有两条取数路径（`DATA_SOURCES.md` §1 的「拉取」和「导入」），两条都要解析
provider 的账单导出文件。代理路径把这件事换掉了：

| | 拉取 / 导入 | 中转 |
|---|---|---|
| 数据粒度 | 时间桶（OpenAI）或 (天,模型,类型) 聚合（DeepSeek） | **逐次调用** |
| 需要解析账单文件 | 是（BOM、列名、人民币换算、峰谷价） | **否** |
| `cacheWrite` 这个桶 | 两家都拿不到 | **响应里就有** |
| 会话信息 | 桶行没有，做不了按会话分析 | 有 request id |
| 价目表 | 要自己维护 | 不需要（响应自带 token 数） |

## 两个前缀，两套认证

```
账号 token ──┬──► /api/account/*            账号（注册/登录/查我/退出）
（App 用）    ├──► /api/relay/keys           注册 relay key（**要账号 token**）
             ├──► /api/relay/usage|season|budgets|pricing|agent   用量、余额、预算、智能体
             └──► /api/forum/*              论坛

relay key ───────► /api/relay/<上游路径>      转发（cc-switch 走这条，**只有这条**）
（cc-switch 用）
```

**身份是账号，relay key 是一条通道。** §2026-02 改§

- 账号：用户名 + 密码 → `tt_app_` 前缀的会话 token。`user_id` 形如 `u_<32 hex>`。
  密码用 `hashlib.scrypt` 存（每用户独立盐，`scrypt$n$r$p$salt$hash`）。
- relay key：`tt_` 前缀。`key_hash = sha256(relay key)`，十六进制 64 字符，
  **明文不落库**。它挂在某个账号的 `user_id` 下面。
- **用量、赛季余额、预算、智能体记账的归属列是 `user_id`，不是 `key_hash`。**
  一个账号可以有多条 key（换上游、轮换凭据），账不会跟着断。
- 上游密钥（用户的真 key）**必须存明文**——转发时要注入它，这是「key 存服务端」
  这个决定的必然代价，和上面的 hash 分开两列存放。

> **改之前是什么样，为什么改。** 原来 relay key 自带身份（`uid = sha256(relay key)`），
> 用量和余额都挂在它上面。代价是**换个 key 就换个人**：余额清零、赛季重来，
> 而换 key 本来只是换一条提交用量的路。而且没配中转的用户在服务端根本没有身份，
> 读不了自己的用量、也用不了智能体。
>
> 现在有了 App 自己的账号表（`accounts`），身份就有了唯一的落点。
> 那个改动**没有引入任何映射表**——`relay_store.owner_of(secret)` 把任意凭据
> （账号 token **或** relay key）解成账号的 `user_id`，两条路都走同一个身份，
> 于是「relay key ↔ 账号绑定」这个问题不存在了。

## 身份隔离（每个方向都不许回退）

| 拿什么打哪里 | 结果 |
|---|---|
| 账号 token → 用量/赛季/预算/智能体/论坛 | ✅ 200 |
| 账号 token → `GET/PATCH/DELETE /keys/all`、`/keys/{uid}` | ✅ 200（**管名下的 key 要账号 token**） |
| relay key → `GET /keys/all` | ❌ 401（它只能看自己那一条，`GET /keys`） |
| relay key → 用量/赛季/预算 | ✅ 200（解成它所属账号的 `user_id`） |
| **relay key → 智能体** | ❌ 401 `Sign in to your account first`（智能体只认账号 token） |
| **relay key → 论坛** | ❌ 401（`relay_keys` 和 `account_sessions` 是两张表） |
| 账号 token → 转发（`/api/relay/v1/...`） | ❌ 401，**故意不回退**：转发只认 relay key，否则账号 token 泄露就等于上游 key 泄露 |
| 上游 key 当 relay key 用 | ❌ 401（必须先在 `relay_keys` 里注册过） |
| 用别的账号的 token 查用量 | ❌ 看不到（查询条件就是解出来的 `user_id`，不是参数） |

`tt_` 前缀让 relay key 一眼能和 `tt_app_`（账号）、`tt_test_`（论坛测试身份）分开。
**同一个账号换一条 key，余额和赛季不变**——这正是「身份是账号」要保证的事。

## 管理接口

### `POST /api/relay/keys` → 201

注册。**请求自己带 key，所以不需要先登录**——这就是「只用 key」这个口径的意思。

```json
{
  "upstreamUrl": "https://api.deepseek.com",
  "upstreamKey": "sk-...",
  "relayKey": "tt_my_own_key_0123456789",
  "displayName": "我的 DeepSeek"
}
```

- `relayKey` 省略 → **自动生成**一个 `tt_` + 32 字节随机数，**在响应里返回唯一一次**。
- `relayKey` 给了 → 按自定义规则校验（见下）。
- `upstreamKey` 里带不带 `Bearer ` 都行，服务端归一成裸 key 存储，
  注入上游时统一补 `Bearer `。
- **认证是账号 token**（`Authorization: Bearer tt_app_...`）；没带或失效返回 401。
  服务端从它解出 `user_id`，新 key 就挂在这个账号下面。
- 响应含 `relayKey`（仅在自动生成时是新值）、`uid`（**这条 key 的 sha256**）、
  `upstreamUrl`、`provider`、`hasUpstreamSecret`。**任何响应都不返回上游密钥。**

错误：`400` 输入非法（含 SSRF 校验失败）、`401` 没登录、`409` relay key 已被注册、
`429` 注册限流（按 IP 限，和账号无关）。

#### 自定义 relay key 的规则

| 规则 | 值 | 为什么 |
|---|---|---|
| 前缀 | 必须 `tt_` | 一眼区分身份类型 |
| 长度 | **≥ 24 字符** | **它本身就是凭据**：拿到它就能借你的上游 key 转发。太短等于把这个能力送出去（它**不是** App 的登录凭据——那个是账号密码换来的 token） |
| 字符 | 不含空白，≤ 200 | |
| 唯一 | 撞了 409，绝不覆盖 | 覆盖等于把前一个人的用量送给后一个人 |

### `GET /api/relay/keys`

**当前这一条** key + 上游地址（**不含上游密钥**）。认证：`Authorization: Bearer <relay key>`
（账号 token 也认——它会解成同一个账号）。

### `GET /api/relay/keys/all` → 200（2026-02 加）

**我名下所有的 key。** 认证：`Authorization: Bearer <账号 token>`（relay key 不行——
它只能看自己那一条）。

```json
{"uid": "u_9f2…", "items": [
  {"uid": "<sha256>", "displayName": "家里的电脑",
   "upstreamUrl": "https://api.deepseek.com", "upstreamHost": "api.deepseek.com",
   "hasUpstreamSecret": true, "requestCount": 12,
   "createdAtEpochMillis": 1790500000000, "lastUsedAtEpochMillis": 1790600000000,
   "disabled": false}
]}
```

**为什么要这个接口。** 一个账号可以注册多条 key（换上游、轮换凭据），而
`GET /keys` 只有「当前这一条」的视角——用户没法回答「我到底录了什么」。
App 的中转设置页就把这份清单显示出来。

**`upstreamHost` 由服务端算**，不是让客户端从 `upstreamUrl` 里解析：各平台对端口、
IPv6、末尾斜杠的解析边界不一样，而这是用户分辨多条 key 的**唯一依据**。

> **`uid` 是 key 的 sha256，永远没有明文。** 库里只有 hash，`GET` 不可能把它变回来。
> 所以「哪条是哪条」只能靠注册时填的 `displayName` 认——那个字段因此值得填。
> 认不出来就重新注册一条，旧的停用即可：**历史用量挂在账号上，不会丢**。

### `PATCH /api/relay/keys/{uid}` / `DELETE /api/relay/keys/{uid}`（2026-02 加）

按 `uid` 改/停用**指定那一条**。认证：账号 token；`uid` 必须属于这个账号，
否则**404**（不是 403——403 等于承认「这条存在，只是不属于你」）。

**为什么要按 uid。** 以前只有「拿账号 token 打 `/keys`，服务端自己挑一条」。
只有一条 key 时看着没问题，**多了就会悄悄改错人**。

### `PATCH /api/relay/keys`

换上游地址或上游密钥。**不动 key_hash**，所以这条 key 本身的历史不会断——
这正是 relay key 和上游凭据要分两张表的原因。换地址会重新过一遍 SSRF 校验。

### `DELETE /api/relay/keys`

停用。**不删行**：这条 key 是审计线索（哪天注册的、用过多少次），删了就说不出口
过去了。停用后转发返回 `403`。

### `GET /api/relay/usage?since=<epochMillis>`

按模型聚合的用量。**账算在账号上**：账号 token 或这个账号的任意一条 relay key
都读到同一份（2026-02 改；`uid` 字段现在回的是**账号的 `user_id`**，`u_<hex>` 形状）：

```json
{"uid": "<sha256>", "items": [
  {"model": "deepseek-chat", "serviceTier": null, "provider": "DEEPSEEK",
   "input": 300, "cacheRead": 900, "cacheWrite": 0, "output": 340, "calls": 1,
   "firstAtEpochMillis": 1790500000000, "lastAtEpochMillis": 1790500000000}
]}
```

`provider` 由上游域名推出来（`DEEPSEEK` / `OPENAI` / `MIMO`），**认不出来是 `null`，
不猜**——猜错会让用量挂到错误的提供方上，比留空难查得多。

**四桶口径和 `CONTRACTS.md` §8 一致**：`input` 只装**未命中缓存**的那部分。
OpenAI / DeepSeek 的 `prompt_tokens` 是输入总量，所以已经减掉了缓存那两段；
Anthropic 的 `input_tokens` 本身就是未命中部分，不再减（减了会变负）。

### `GET /api/relay/usage/daily?since=&until=&limit=` → `DailyUsage` 形状

按 `(天, provider, 模型)` 滚出来的日汇总，**这是服务端成为唯一数据源的那一层**：
客户端不再自己拿 `UsageCall` 滚日汇总，只读这里的结果。

```json
{"uid": "<sha256>", "pricingAvailable": false, "timezone": "Asia/Shanghai",
 "items": [
   {"uid": "<sha256>", "day": "2026-09-27", "provider": "DEEPSEEK", "model": "deepseek-chat",
    "input": 300, "cacheRead": 900, "cacheWrite": 0, "output": 340, "calls": 1,
    "costMicros": null, "rateVersion": null, "settled": false}
]}
```

三个必须说清的口径：

1. **「天」是 `Asia/Shanghai` 的日历天**，和 App 侧 `util/TimeUtils.ZONE` 是同一个。
   分组在 SQL 里做（`date(created/1000 + 偏移, 'unixepoch')`），偏移从 `zoneinfo` 推导，
   **不写死 28800**。用 UTC 分组的话，北京时间 23:30 和次日 00:30 会被合并成一天，
   日曲线会少一根柱子，而且不报错。
2. **`costMicros` / `rateVersion` 现在恒为 `null`**，并且响应里显式带
   `pricingAvailable: false`。理由不是没做完，是口径：服务端还没有价目表
   （`pricing/rates` 那张表还没建），所以**算不出价**。按 `CONTRACTS.md` §4 那条底线，
   算不出价必须留 null 让界面显示「价格未知」，**不许填 0**——填 0 会把
   「不知道花了多少」显示成「花了 0 元」，正是那一节点名的「最难发现的一类 bug」。
3. **`uid` 由服务端填**，客户端传不了。

`since` / `until` 是 UTC 毫秒，`since` 含、`until` 不含。


## 赛季与结算（服务端是权威）

`CONTRACTS.md` §7 第 5 条：「`lastSettledDay` 和余额**以服务端为准**，否则重装、换设备、
多开都能重复领」。这一组接口就是那条规则的落点——客户端算的话，重装一次就能把同一批
token 再领一遍资源，这不是理论风险，是必然发生的事。

四个接口，都在 `/api/relay/season*`：

| 接口 | 作用 |
|---|---|
| `GET /season` | 只读：余额 + `lastSettledDay` + 本月 token + 换算率。**不触发结算** |
| `POST /season/settle` | 结算所有「过完但还没结算」的天 |
| `POST /season/spend` | 扣资源（三种资源分开传，互不通兑） |
| `GET /season/days` | 已结算的天流水，用来看「资源是哪儿来的」 |

```
GET /api/relay/season
{"uid": "<sha256>", "lastSettledDay": "2026-09-26",
 "balance": {"input": 100, "cache": 100, "output": 200},
 "updatedAtEpochMillis": 1790500000000, "tokensPerUnit": 10000,
 "monthTokens": {"input": 1200, "cacheRead": 900, "cacheWrite": 0, "output": 340,
                 "calls": 1, "isCurrentMonth": true}}

POST /api/relay/season/settle
{"uid": "<sha256>", "settledDays": ["2026-09-26"],
 "gained": {"input": 100, "cache": 100, "output": 200},
 "tokensCounted": {"input": 1000000, "cacheRead": 600000, "cacheWrite": 400000, "output": 2000000},
 "balanceAfter": {"input": 100, "cache": 100, "output": 200}, "lastSettledDay": "2026-09-26"}

POST /api/relay/season/spend   {"input": 40, "cache": 25, "output": 10}
→ {"uid": "...", "balance": {"input": 60, "cache": 75, "output": 190}}
```

### `POST /season/settle` 没有「结算哪一天」这个参数

和客户端 `SeasonRepository.settleCompletedDays` 的签名一致（`TASKS.md` §3.2 点名了
这条）：能算哪些天**只由数据本身决定**（`lastSettledDay` 之后、今天之前），
调用方说不了算。所以它天然幂等——连调两次，第二次的 `settledDays` 是空的。

五条规则都有对应的测试（`backend/tests/test_seasons.py`）：

| 规则 | 怎么保证 | 怎么测的 |
|---|---|---|
| 1. 今天永远不结算 | 上界是「昨天」，代码里再挡一次 | 注入固定的「今天」，断言今天的用量不进结算 |
| 2. 幂等 | `season_settlements` 主键 `(uid, day)` + 余额同事务 | 连调两次，第二次 `settledDays` 为空；并发两个请求只有一个真的发 |
| 3. 补齐 | 起点是 `lastSettledDay + 1` | 隔三天一次补三天，`settledDays` 三项 |
| 4. 已结算的天不可变 | 晚到的记录不再结算 | 往已结算的天补插一笔，余额不变 |
| 5. 余额在服务端 | 存在 `season_balances` 表 | 重启进程后余额和 `lastSettledDay` 还在 |

### 换算率与取整

`CONTRACTS.md` §8：每 100 万 token 换 100 个资源，也就是 **1 个资源 = 10000 token**
（`TOKENS_PER_UNIT`，和 App 侧 `MainActivity.TOKENS_PER_UNIT = 1_000_000L / 100L` 是
同一个口径，两处必须一起改）。`cacheRead` 和 `cacheWrite` **合成一种**资源。

**先加总再取整，余数丢弃**：一天里可能有多个 `(provider, 模型)`，各自取整的话零头会被
丢掉很多次（三行各 5000 token，分着算是 0，加总是 1）；而跨天不累加余数——
攒余数会让「换个顺序结算」得到不同的余额。

三种资源**互不通兑**：缓存命中单价只有原价十分之一左右，按原始 token 数换同一种资源的话，
「多用缓存」就成了刷资源的漏洞。所以 `spend` 的三个字段是分开的，
而且余额**不能变负**（余额检查在写事务里做，不是先读再写——先读再写在并发下会双双通过
检查然后一起扣成负数）。

### 新账号第一次结算有上限

`lastSettledDay` 为空时往回最多 `MAX_BACKFILL_DAYS = 400` 天。没有上限的话，
一个刚注册的账号会把账号存在的全部历史一次算完、一次造出一个巨大的余额。

### `costMicros` 还是 null

结算**不需要**价格：游戏资源是从 **token 数**换来的，和花多少钱无关。
所以价目表（`pricing/rates`）没建并不挡结算——`costMicros` 继续是 `null`，
界面显示「价格未知」，不影响余额。

### `GET /api/relay/usage/calls?since=&until=&limit=` → `UsageCall` 形状

逐次调用记录，给 agent 的按会话分析和明细用。

```json
{"uid": "<sha256>", "pricingAvailable": false, "items": [
  {"id": "call:DEEPSEEK:5300b75a05ca:1790500000000:00a1b2",
   "uid": "<sha256>", "provider": "DEEPSEEK", "model": "deepseek-chat",
   "startedAtEpochMillis": 1790500000000,
   "input": 300, "cacheRead": 900, "cacheWrite": 0, "output": 340,
   "source": "IMPORTED",
   "costMicros": null, "costCurrency": null, "nativeCostMicros": null}
]}
```

三个映射是契约里的硬口径，都不是风格问题：

1. **`id` 的形状是 `call:<provider>:<uid 前缀>:<毫秒>:<随机>`**，前缀 `call:` 是**必需的**
   （`UsageCall.id` 的注释：本类没有别的字段能说明这行是逐次调用还是时间桶，
   而「能不能做按会话分析」必须能算出来）。
   **id 在写入时生成，不在读取时拼**——同一毫秒里的两个请求会拼出同一个 id，
   而那个 id 是客户端去重用的键，撞了就丢数据。
2. **`source` 恒为 `"IMPORTED"`。** `UsageCall.Source` 只有 `IMPORTED` 和 `SAMPLE`，
   而 `CONTRACTS.md` §4 规定只有 `IMPORTED` 进日汇总、预算和游戏资源。
   中转看到的是真实流量，属于前者。语义上有点勉强（这个名字也覆盖「拉取来的」），
   但 `DATA_SOURCES.md` §2 已拍板不改枚举名，报告里说明即可。
3. **`costMicros` / `nativeCostMicros` 恒为 `null`**（没有价目表就算不出来）。
   契约里这两个字段本来就是可空类型，null 表示「价格未知」。**不许填 0**——
   `CONTRACTS.md` §4 那条底线。

**`provider` 认不出来时是 `null`，不是 `"unknown"`。** `Provider` 是枚举，
值域只有 `OPENAI` / `MIMO` / `DEEPSEEK`，塞一个枚举外的字符串进去，
客户端反序列化时会**直接抛异常**而不是拿到一个「未知」。

**迁移之前写下的行会被跳过**：`call_id` 是后加的列，那些老行没有可用的 id，
而给一个拼出来的假 id 会让两条不同的记录撞成一条。

## 预算（只存上限，花销现算）

| 接口 | 作用 |
|---|---|
| `GET /budgets/{month}` | 某月的预算 + 覆盖度。`month` 是 `yyyy-MM` |
| `PUT /budgets/{month}` | 设置或覆盖上限（`capMicros` + 可选 `warnAtRatio`） |

```
GET /api/relay/budgets/2026-09
{"uid": "<sha256>", "month": "2026-09", "configured": true,
 "capMicros": 20000000, "warnAtRatio": 0.5,
 "spentMicros": 0, "pricingAvailable": false,
 "coverage": {"from": "2026-09-01", "to": "2026-09-27",
              "daysWithData": 2, "daysMissing": ["2026-09-01", "…"]}}

PUT /api/relay/budgets/2026-09   {"capMicros": 20000000, "warnAtRatio": 0.5}
```

### 只存上限，从来不存「已花」

`TASKS.md` §1.1 的要求：存「已花」的话，它和用量就是两份数据，任何一次导入、纠正、
延迟落库都会让两者不一致，而对账时不知道该信哪个。**花销每次都从用量现算。**

### ⚠️ `spentMicros` 现在是 0，但 `pricingAvailable` 是 false

没有价目表（`pricing/rates` 没建）就算不出花销。所以：

- `spentMicros` 返回 **0**，同时 **`pricingAvailable: false`**；
- **客户端不能因为看到 0 就说「你花了 0 元」**——那是 `CONTRACTS.md` §4 点名的
  「数字看着没错、结论是错的」。要显示「价格未知」。
- 等 `pricing/rates` 建好，这里改成按天取当天生效的费率现算，**表的形状不用动**。

### `configured` 和 `capMicros` 是两件事

没设过预算时 `configured: false` 且 `capMicros: null`——**不是 `capMicros: 0`**。
「你还没有设预算」和「你已经超了」在界面上要说两句不同的话
（`BudgetStatus.configured` 的注释点名了这条）。

### `coverage` 的口径

按 `Coverage` 的类注释：**只有「一条记录都没有」的天才进 `daysMissing`**，
「用量为 0」不进——前者是不知道，后者是确定没花钱。

- **本月只算到今天。** 把未来的天算进去会让覆盖度永远填不满，那是假的「缺数据」。
- 已经过完的月按整月算（8 月 31 天、2026 年 2 月 28 天）。
- 非法月份返回 **400 而不是 500**（手写校验，不走 `date.fromisoformat` 的内部异常）。

## 价目表（`pricing_rates`）

**2026-09-27 新增。** 它是「成本算不出来」的总根因：`costMicros`、`spentMicros`、
`rateVersion` 三处都等着它。

| 接口 | 作用 |
|---|---|
| `GET /pricing?provider=&model=` | 列出已录入的费率 |
| `PUT /pricing/{provider}/{model}` | 录一版费率（值是**微美元 / 1M**） |
| `POST /pricing/convert` | 人民币元 / 1M → 微美元 / 1M |

### 费率按「天」查，不按「现在」查

`effectiveFrom` 是日期，`rate_for` 取**「不晚于该天的最近一版」**。
**改了价目表不能让历史账单变**——所以日汇总的每一行用它**自己那一天**的费率，
批量查询的键是 `(provider, 模型, 天)` 而不是 `(provider, 模型)`。

### 查不到价 → `null`，不是 0

`CONTRACTS.md` §4 那条底线。四个桶里**缺任何一个就整笔返回 `null`**——
逐项跳过缺失的那一项会得到一个「部分成本」，它和真实成本的差额没有任何地方体现，
看起来却像个正常数字。

响应里 `pricingAvailable` 是对**整体**的概括，逐行的真相看每行的 `rateVersion`
（null 就是这一行没价）；`unpricedModels` 列出是哪些模型没价。

### 币种只在服务端换算一次

来源价目是**人民币**（DeepSeek 官方文档和导出都是元/1M），项目内部一律微美元
（`CONTRACTS.md` §9）。换算是**两步**，少乘一个 1e6 是这里最容易犯的错：

```
1 元/1M → 1 × 1_000_000 微元 → × 1_000_000 / 7_100_000 = 140845 微美元/1M
```

`CNY_PER_USD_MICROS = 7_100_000` 和 App 侧 `data/Money` **必须同值**——
不同的话同一笔钱会在两个地方显示成两个数。所以录入时用
`POST /pricing/convert`，**别手算**：手算等于多一份实现，而且错了不会报错。

### 内置种子只含 DeepSeek，而且默认不装

`FORUM_PRICING_SEED=1` 才在启动时录入。依据是 `DATA_SOURCES.md` §4 的
**实测记录**（真实账号导出、逐行核对过）：低谷档 cacheRead 0.02 / input 1 /
output 4 元/1M，cacheWrite 记 0（DeepSeek 不计缓存写入）。

三个刻意的取舍：

1. **只录有一个档位。** 导出里没有任何小时信息，还原不出哪笔是峰价哪笔是谷价。
   选一个档统一算，而不是把两档混进同一个数（§4 要求「不把两种口径加进同一个数」）。
   于是这是**偏低的估算，误差方向已知**。
2. **只录 DeepSeek。** OpenAI 和 MiMo 的价目**没实测过**（§1 里 MiMo 那一整节
   标着「社区资料拼出来的，我们一条都没验过」）。编一组看起来合理的数字会让成本
   功能看起来能用、实际全错，**比空着更糟**。空着 = 界面显示「价格未知」。
3. **不覆盖已存在的记录。** 生产库里可能有人后来录了更准的一版，
   启动时无条件覆盖会在每次重启时把它悄悄冲掉。

### `GET /usage/summary?from=&to=&groupBy=` → `UsageSummary` 形状

按维度滚一段区间的用量。**这是 agent 最常用的那个工具**（`getUsageSummary`）。
`from` / `to` 是 `yyyy-MM-dd`，**两端都含**；`groupBy` 取 `DAY` / `MODEL` / `PROVIDER`。

```
GET /api/relay/usage/summary?from=2026-09-01&to=2026-09-27&groupBy=MODEL
{"uid": "…", "totals": {"input": 10, "cacheRead": 0, "cacheWrite": 0, "output": 0},
 "rows": [{"key": "DEEPSEEK/deepseek-chat", "provider": "DEEPSEEK",
           "model": "deepseek-chat", "tokens": {...}, "calls": 1,
           "costMicros": 140845, "rateVersion": "2026-09-deepseek-offpeak"}],
 "groupBy": "MODEL", "from": "2026-09-01", "to": "2026-09-27",
 "costMicros": 140845, "pricingComplete": true, "unpricedModels": [],
 "rateVersions": ["2026-09-deepseek-offpeak"],
 "coverage": {"from": "2026-09-01", "to": "2026-09-27",
              "daysWithData": 1, "daysMissing": ["2026-09-02", "…"]}}
```

四条口径：

1. **`uid` 不是参数。** 由服务端从凭据解出来（`CONTRACTS.md` §6：不信客户端传的）。
   传了也不影响算谁的账。
2. **`coverage.daysMissing` 只装「一条记录都没有」的天**，「用量为 0」不进。
   这是「agent 承认自己不知道」的**机制保证**，不靠提示词祈祷模型老实。
3. **算不出价的行不加进 `costMicros`**，并且 `pricingComplete` 变 `false`、
   `unpricedModels` 列出是哪些。加 0 会得到一个偏低的总额而看不出偏低。
   `totals` 里的 **token 数照旧全算**——token 不需要价格就知道。
4. **反向区间返回 400，不返回空。** 静默的空区间会让 agent 说
   「这段时间没有用量」，而实际上它把参数写反了。区间上限 366 天。

`groupBy=PROVIDER` 时，认不出上游的那一组**分组键是 `"unknown"`，但 `provider`
字段仍是 `null`**——`Provider` 是枚举，塞枚举外的值客户端反序列化会直接抛异常。

### `GET /usage/compare?from=&to=&metric=` → `CompareResult` 形状

按 `(provider, 模型)` 比一段区间，agent 的 `compareAgentCosts` 用这个。
`metric` 取 `COST` / `TOKENS` / `COST_PER_1M`。

每行**三个数都返回**（成本、token、单价），所以 agent 一个响应能同时回答
「哪个贵」和「哪个单价高」，不用发两次请求。

**不比「哪个模型更聪明」——这条是结构上成立的，不靠自觉**：这个接口里
根本没有质量数据（`CompareResult` 的类注释）。

#### ⚠️ 没价的行不参与排名

算不出价的模型 `costMicros` 是 0，而那个 0 是「不知道」**不是「最便宜」**。
让它混进排名就等于把「不知道」当成一个结论讲出来。所以：

- 没价的行**永远排最后**，不参与指标排序；
- 逐行给 `priced` 标记，让调用方能把它排除在外；
- 整体的 `pricingComplete` / `unpricedModels` 照旧带上。

（`CompareResult.Row` 没有 `priced` 字段，多出来的键客户端反序列化时会忽略，
但 agent 读得到。）

**它和 `/usage/summary` 共用同一套聚合**，不另写一遍——两处各算一次的话，
「同一个问题两个答案」是迟早的事。

### ⚠️ 天界的方向（这里错过两次）

「天」按 `Asia/Shanghai`，而存储是 UTC 毫秒。北京 09-20 00:00 = UTC **09-19** 16:00，
所以日期区间的起点要比 UTC 的 09-20 00:00 **早** 8 小时。

| 写法 | 后果 |
|---|---|
| 两处都用 `+偏移` | 等于加了两次，**整天的用量被跳过** |
| 只减一半 | 北京**凌晨 0–8 点**的用量落在区间外，那半天丢 |
| `- 偏移`（现在） | ✅ 覆盖北京 00:00–24:00，右端开 |

**为什么长期没发现**：测试都用中午 12:00 造数据，那时候怎么错都在窗口里。
现在 `tests/test_summary.py` 有两条专门用**凌晨**时刻的边界测试。

## 应用内 AI 智能体（和中转完全分开）

**2026-09-27 新增。** 它是「home-screen AI assistant」（大纲 §1），
之前文档里我把它写成「建议服务」——那是我的用词问题，**只有一个 AI 智能体**。

### ⚠️ 它和中转是两套东西，**账本也必须是两套**

| | 中转 | 智能体 |
|---|---|---|
| 用谁的 key | **用户的**上游 key | **我们自己的**（`FORUM_AGENT_KEY`） |
| 用量表 | `relay_usage`（**按账号**） | **`agent_usage`**（也按账号，**独立的表**） |
| 进不进用户的日汇总/预算/结算 | 进 | **不进** |
| 凭据 | relay key **或**账号 token | **只认账号 token**（智能体是用户的客服，不是中转的一部分） |

**为什么这是硬要求。** 用户问「我这周怎么花了这么多」——如果把智能体自己调模型的
消耗算进他的编码用量，**答案里的数字就被问题本身污染了**：问得越多、账越大。
而且游戏资源是从编码用量换来的，混进去等于让「多问几句」能换塔。
`CONTRACTS.md` §6 明确要求 agent 自己的 token 单独记账。

### 接口

| 接口 | 作用 |
|---|---|
| `POST /agent/ask` | 收一个问题，返回回答 + 证据 + `missingData` + 它自己的用量 |
| `GET /agent/status` | 配好了没有 + 它自己这个月花了多少（`ownCostMicros`） |

```
POST /api/relay/agent/ask   Authorization: Bearer tt_app_...
                            {"question": "why did cost rise?"}
{"text": "…", "model": "deepseek-chat", "createdAtEpochMillis": 1790500000000,
 "ownCostMicros": 198, "evidence": [], "toolCalls": [],
 "missingData": ["Usage data tools are not wired up yet, so this answer is not
                  based on your recorded usage."],
 "usage": {"input": 600, "cacheRead": 400, "cacheWrite": 0, "output": 200, "calls": 1}}
```

### key 只在服务端

大纲 §8「no client holds a model key」。所以：key 从环境变量读、
**不进数据库、不进日志、不进任何响应**；健康检查只报 `agentConfigured: true/false`；
上游返回 401/403 时**不把上游的错误文本原样回传**（里面可能带 key 的片段），
只返回一句 503。

客户端只发问题：**请求体里没有 `uid`、没有 key**（`extra="forbid"`，
传了直接 400 而不是静默忽略）。

### 工具调用循环（2026-09-27 接上）

模型能调 `CONTRACTS.md` §6 那五个只读工具，服务端执行、把结果喂回去、
再让它据此回答。实测（真实 DeepSeek）：

> 问「我 9 月 20 到 27 号一共用了多少 input token？分几天用的？」
>
> 答「合计 **3,000,000**，成本约 **$0.90**（估算，非账单）。但要注意：
> 这 8 天里**只有 2 天有记录**，其余 6 天没有数据。」

数字正确，而且**它自己把缺的天说出来了**——`Coverage` 的机制保证生效了。

四条实现口径：

1. **`uid` 不在任何工具的 JSON Schema 里。** 模型看不见它，也就传不了它；
   身份由服务端从凭据解出。**传了也不影响算谁的账。**
2. **名单外一律拒绝**（不是返回空）。返回空会让模型以为「查了，没数据」，
   而真相是它调了一个不存在的东西。
3. **每一轮的用量都累加，`calls` 记轮数而不是 1。** 一次问答通常是两轮往返
   （先要工具、再回答），只记最后一轮会少算一半以上。
4. **循环有上限（4 轮）。** 没有上限的话，一个不断要求工具的模型能把 key 刷爆，
   而且请求永远不返回。用完轮数会**如实说**「它一直在要工具，没给出最终答案」。

**提示词里必须带今天的日期。** 踩过：不带的时候问「9 月 20 到 27 号」，
模型查的是 **2025** 年，拿到 0 条记录，然后平静地答「这段时间没有用量」。
日期是事实，不该让模型猜。

### 哪个工具现在能给出真数据

| 工具 | 状态 |
|---|---|
| `getUsageSummary` | ✅ 和 HTTP 接口同一套聚合 |
| `compareAgentCosts` | ✅ 同上 |
| `getBudgetStatus` | ✅ 花销现算，算不出价会说明 |
| `getForumHighlights` | ✅ **官方帖 + 社区热帖**，两类各自带 `source` |
| `getMyThreads` | ✅ **能查了**（2026-02）。智能体的凭据是账号 token，解出的 `user_id` 就是 `posts.author_uid`，**直接 join，不需要任何绑定表**。改之前它返回结构化的「不知道」而不是空列表——空列表读起来是「你没发过帖子」，真相是「我查不到」 |

## 转发接口

**`ANY /api/relay/<上游路径>`** —— 方法、请求体、响应体**全部原样穿过**。

服务端只做四件事：

1. 拿 relay key 查身份和上游地址
2. 把 `authorization` 换成用户的上游 key（补 `Bearer `），丢掉逐跳首部
3. 发给上游，把响应原样回传
4. 从响应里**旁路**抽 usage 落库

**不做协议转换。** 用户在 cc-switch 里选对「上游格式」并指向对应路径；选错会直接
看到上游的报错，我们不改写它。这样这个模块不需要理解任何一家的协议语义。

### 路径拼接

只剥掉中转自己那一层前缀：`/api/relay/v1/chat/completions` → `/v1/chat/completions`。
然后拼到用户填的 base 上。所以 `https://api.deepseek.com` 得到
`https://api.deepseek.com/v1/chat/completions`，而
`https://open.bigmodel.cn/api/coding/paas/v4` 得到 `.../v4/chat/completions`。
**不会出现 `/v1/v1/`。**

### 流式（SSE）

`content-type: text/event-stream` 时按块透传，**发给客户端的字节和上游完全一致**；
旁边用一个小缓冲解析 SSE 行抽 usage。上游发完最后一块之后才落库（usage 在最后几块里）。

nginx 那一段必须 `proxy_buffering off`，否则打字机效果会消失。

### 错误约定

| 情况 | 返回 |
|---|---|
| 上游请求失败 | `502 Upstream is unreachable`，**不留下用量记录** |
| 上游返回错误状态 | **如实透传**（上游 401 就是 401），不改写成我们的错误 |
| 没有 usage 的响应 | 不记录。**「没有 usage」和「花了 0 token」是两件事** |
| 上游连不上 | 502，不伪装成 200 |

## SSRF 防护（这一层必须有）

用户能指定 URL，服务器就替他发请求——这是一个真实的 SSRF 面。`relay.py` 的
`upstream_target` 是**唯一允许发起出站请求的出口**，校验：

- 只允许 `http`/`https`，URL 里不许带凭据、query、fragment
- **拒绝私网 / 回环 / 链路本地 / 保留地址**（`169.254.169.254` 云元数据、内网扫描）
- `FORUM_RELAY_ALLOWED_HOSTS` 非空时按白名单校验（**生产必须填**）
- **拒绝指回自己**（`FORUM_RELAY_SELF_HOSTS`），否则是转发环

`FORUM_RELAY_ALLOW_PRIVATE=1` 只给本地验收用（假上游在回环上），**生产保持 0**。

## 环境变量

见 `deploy/forum-relay.env.example`。装了那个文件到
`/etc/tokentrail-forum-relay.env` 才会启用中转；文件不存在时服务照常起，
`/api/relay/*` 返回 404（和以前一样）。

## 本地怎么验

```bash
# 单元 + 集成（49 个）
python3 -m venv /tmp/tokentrail-forum-venv
/tmp/tokentrail-forum-venv/bin/pip install -r backend/requirements-test.txt
cd backend && /tmp/tokentrail-forum-venv/bin/python -m pytest -q tests

# 真实 uvicorn + 真实 HTTP 的端到端（注册 → 转发 → 记账 → 边界）
/tmp/tokentrail-forum-venv/bin/python backend/scripts/check_relay_local.py
```
