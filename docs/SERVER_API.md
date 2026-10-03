# 服务端 API 契约

> **2026-09-30 改：中转整块删除，本文件从 `RELAY_API.md` 改名而来。**
>
> 改之前这一层是**API 中转**：用户把自己上游的 `URL + API key` 填进 App，服务端替他
> 把请求转发到上游（cc-switch 指过来），顺手从响应里把 usage 抽出来落库。
> 新方向（ModelPilot 大纲）反过来：**用户在 App 里提问 → 后端用我们自己的模型连接去调
> → 产生用量 → 记账**。所以不再需要替谁转发，也就不再需要用户把上游 key 交到我们手上。
>
> 删掉的东西（连带它们的口径一起删，不是"留着不用"）：
>
> | 删了什么 | 原位置 |
> |---|---|
> | 整个转发模块：SSRF 白名单、逐跳首部处理、路径拼接、流式 SSE 透传 | `backend/tokentrail_forum/relay.py`（只剩 `split_usage` 挪进新的 `usage.py`） |
> | 路由：`/keys*` 注册与轮换、`ANY /{path:path}` 兜底转发 | `backend/tokentrail_forum/relay_routes.py`（改名 `api_routes.py`） |
> | 表：`relay_keys`（凭据）、`relay_secrets`（用户的上游 key 明文） | `backend/tokentrail_forum/relay_store.py`（改名 `usage_store.py`） |
> | 客户端：`RelayCredentials`、`ui/auth/RelaySetupDialog`、`RelaySetupViewModel`、「我的」页里的中转状态与设置入口 | `app/src/main/java/com/mobilegroup20/modelpilot/...` |
> | 凭据类型：relay key（`tt_` 前缀） | —— |
>
> **现在没有转发这条路了。** 服务端只回答"你是谁、你用了多少、花了多少、预算多少、
> 余额多少"，以及智能体的问答。
>
> **产生用量的那条新路还没写。** 目前 `relay_usage` 里的记录来自"客户端提交的记录"
> （导入路径），App 内提问 → 后端调模型 → 落库这一条**只有智能体自己在走**
> （它走的是独立的 `agent_usage`，见下面"应用内 AI 智能体"那节）。所以下面那些读接口
> 现在读到的可能是一张空表——**那不是接口坏了，是还没有东西往里写**。
>
> **URL 前缀里的 `relay` 是历史字面量，没改。** 它在 App 的 `ServerApi`、部署脚本和
> 文档里都写死了，改它要同时动三处、还要重新部署；而它现在和"中转"这件事已经没关系。
> 同理 `FORUM_ENABLE_RELAY` 这个开关名也是历史遗留——它现在守的是记账那几组接口。

## 前缀与身份

```
账号 token ──┬──► /api/account/*            账号（注册/登录/查我/退出）
（App 用）    ├──► /api/relay/usage|season|budgets|pricing|agent   用量、余额、预算、价目、智能体
             └──► /api/forum/*              论坛
```

**身份只有一个：账号 token。**（2026-02 起收紧；2026-09-30 中转删除后它成了唯一一种凭据。）

- 账号：用户名 + 密码 → `tt_app_` 前缀的会话 token。`user_id` 形如 `u_<32 hex>`。
  密码用 `hashlib.scrypt` 存（每用户独立盐，`scrypt$n$r$p$salt$hash`）。
- **用量、赛季余额、预算、智能体记账的归属列都是 `user_id`。**
- 智能体**只认账号 token**，论坛也只认账号 token（或 Debug 下的 `tt_test_` 测试身份）。
- `tt_` 这个前缀曾经是 relay key，**现在不再签发、也不再认**。拿一个 `tt_...`
  打任何接口都是 401。

> **为什么"身份是账号"这件事要单独讲一段。** 更早的时候 relay key 自带身份
> （`uid = sha256(relay key)`），用量和余额都挂在它上面，代价是**换个 key 就换个人**：
> 余额清零、历史断掉，而换 key 本来只是换一条提交用量的路；没配中转的用户在服务端
> 干脆没有身份。现在有了 App 自己的账号表（`accounts`），身份就有了唯一的落点。
> 那一次改动**没有引入任何映射表**——`owner_of(secret)` 把任意凭据解成账号的
> `user_id`，所有读写都按它。中转删掉之后连"任意凭据"都不需要了：
> `Accounts.resolve(authorization)` 只认账号 token 一种。

**用量私有、论坛公开。** 用量类的接口查询条件就是解出来的 `user_id`，
**不是请求参数**——传了也不影响算谁的账（`CONTRACTS.md` §6 那条"不信客户端传的"）。
所以"用别的账号的 token 查别人的用量"在结构上做不到，而不是靠一层判断挡住。

## 账号（`/api/account/*`）

| 接口 | 作用 |
|---|---|
| `POST /account/register` → 201 | 注册。**不返回 token**——注册完自己登一次 |
| `POST /account/login` | 登录，签发会话 token |
| `GET /account/me` | 当前账号（`userId` / `username` / `createdAtEpochMillis`） |
| `POST /account/logout` | 退出。**幂等**，已经失效的 token 也返回成功 |

三个口径：

1. **注册不自动登录。** 自动登录看着省事，但它让"注册"和"登录"两条路都要懂怎么签发
   会话，而且注册接口一旦被脚本刷，自动登录等于顺手帮他建了一堆可用会话。
2. **用户名错和密码错返回同一个 401**（`Incorrect username or password`）——
   区分开等于送人一个"这个用户名存在吗"的探测接口。
3. **`me` 和 `register` / `login` 用同一套字段名**（`userId` / `username`），
   App 侧只认一套，不用为每个接口各写一个解析。

## 用量

### `GET /api/relay/usage?since=<epochMillis>`

按 `(模型, 档位, 供应商)` 聚合的用量。**账算在账号上**（`uid` 回的是账号的 `user_id`，
`u_<hex>` 形状）：

```json
{"uid": "u_9f2…", "items": [
  {"model": "deepseek-chat", "serviceTier": null, "provider": "DEEPSEEK",
   "input": 300, "cacheRead": 900, "cacheWrite": 0, "output": 340, "calls": 1,
   "firstAtEpochMillis": 1790500000000, "lastAtEpochMillis": 1790500000000}
]}
```

`provider` 认不出来时是 **`null`，不是 `"unknown"`**——`Provider` 是枚举，
塞一个枚举外的字符串进去，客户端反序列化时会**直接抛异常**而不是拿到一个"未知"。

**四桶口径和 `CONTRACTS.md` §8 一致**：`input` 只装**未命中缓存**的那部分。
OpenAI / DeepSeek 的 `prompt_tokens` 是输入总量，所以已经减掉了缓存那两段；
Anthropic 的 `input_tokens` 本身就是未命中部分，不再减（减了会变负）。
归一化写在 `backend/tokentrail_forum/usage.py` 的 `split_usage`——它是从被删的
`relay.py` 里唯一留下来的函数，现在给智能体用，将来"用户提问、后端调模型"那条路也用它。

### `GET /api/relay/usage/daily?since=&until=&limit=` → `DailyUsage` 形状

按 `(天, provider, 模型)` 滚出来的日汇总，**这是服务端成为唯一数据源的那一层**：
客户端不再自己拿 `UsageCall` 滚日汇总，只读这里的结果。

```json
{"uid": "u_9f2…", "pricingAvailable": false, "timezone": "Asia/Shanghai",
 "items": [
   {"uid": "u_9f2…", "day": "2026-09-27", "provider": "DEEPSEEK", "model": "deepseek-chat",
    "input": 300, "cacheRead": 900, "cacheWrite": 0, "output": 340, "calls": 1,
    "costMicros": null, "rateVersion": null, "settled": false}
 ]}
```

三个必须说清的口径：

1. **「天」是 `Asia/Shanghai` 的日历天**，和 App 侧 `util/TimeUtils.ZONE` 是同一个。
   分组在 SQL 里做（`date(created/1000 + 偏移, 'unixepoch')`），偏移从 `zoneinfo` 推导，
   **不写死 28800**。用 UTC 分组的话，北京时间 23:30 和次日 00:30 会被合并成一天，
   日曲线会少一根柱子，而且不报错。也**不要把行拉回 Python 再分**——那会让
   "哪天算哪一天"这个口径在客户端和服务端各有一份实现。
2. **`costMicros` 用「那一天生效的费率」算，查不到价就是 `null`。**
   批量查询的键是 `(provider, 模型, 天)` 而不是 `(provider, 模型)`——
   改了价目表不能让历史账单变，同一个模型在不同的天可能就是两个价。
   按 `CONTRACTS.md` §4 那条底线，算不出价必须留 null 让界面显示「价格未知」，
   **不许填 0**——填 0 会把「不知道花了多少」显示成「花了 0 元」，
   正是那一节点名的「最难发现的一类 bug」。
   响应里的 `pricingAvailable` 是对**整体**的概括（有一条算不出来就是 false），
   逐行的真相看每行的 `rateVersion`：它是 null 就表示这一行没价。
3. **`uid` 由服务端填**，客户端传不了。

`since` / `until` 是 UTC 毫秒，`since` 含、`until` 不含。

### `GET /api/relay/usage/calls?since=&until=&limit=` → `UsageCall` 形状

逐次调用记录，给 agent 的按会话分析和明细用。

```json
{"uid": "u_9f2…", "pricingAvailable": false, "items": [
  {"id": "call:DEEPSEEK:5300b75a05ca:1790500000000:00a1b2",
   "uid": "u_9f2…", "provider": "DEEPSEEK", "model": "deepseek-chat",
   "startedAtEpochMillis": 1790500000000,
   "input": 300, "cacheRead": 900, "cacheWrite": 0, "output": 340,
   "source": "IMPORTED",
   "costMicros": null, "costCurrency": null, "nativeCostMicros": null}
]}
```

四个映射是契约里的硬口径，都不是风格问题：

1. **`id` 的形状是 `call:<provider>:<uid 前缀>:<毫秒>:<随机>`**，前缀 `call:` 是**必需的**
   （`UsageCall.id` 的注释：本类没有别的字段能说明这行是逐次调用还是时间桶，
   而「能不能做按会话分析」必须能算出来）。
   **id 在写入时生成，不在读取时拼**——同一毫秒里的两个请求会拼出同一个 id，
   而那个 id 是客户端去重用的键，撞了就丢数据。
2. **`source` 恒为 `"IMPORTED"`。** `UsageCall.Source` 只有 `IMPORTED` 和 `SAMPLE`，
   而 `CONTRACTS.md` §4 规定只有 `IMPORTED` 进日汇总、预算和资源余额。
   写进来的都是真实流量，属于前者。语义上有点勉强（这个名字也覆盖"拉取来的"），
   但 `DATA_SOURCES.md` §2 已拍板不改枚举名，报告里说明即可。
3. **`costMicros` / `costCurrency` / `nativeCostMicros` 恒为 `null`**——**逐次记录这一层
   不查价目表**：一条一条地查价，一次请求几百行就是几百次查询，而"这次花了多少"
   在日汇总和汇总接口里已经算好了。契约里这几个字段本来就是可空类型，
   null 表示「价格未知」。**不许填 0**——`CONTRACTS.md` §4 那条底线。
4. **`provider` 认不出来时是 `null`**（同 `usage`）；`model` 抽不到时也是 `null`
   而不是编一个名字。

**迁移之前写下的行会被跳过**：`call_id` 是后加的列，那些老行没有可用的 id，
而给一个拼出来的假 id 会让两条不同的记录撞成一条（`calls_for` 里那句
`call_id IS NOT NULL` 就是干这个的）。

### `GET /api/relay/usage/summary?from=&to=&groupBy=` → `UsageSummary` 形状

按维度滚一段区间的用量。**这是 agent 最常用的那个工具**（`getUsageSummary`）。
`from` / `to` 是 `yyyy-MM-dd`，**两端都含**；`groupBy` 取 `DAY` / `MODEL` / `PROVIDER`。

```
GET /api/relay/usage/summary?from=2026-09-01&to=2026-09-27&groupBy=MODEL
{"uid": "u_9f2…", "totals": {"input": 10, "cacheRead": 0, "cacheWrite": 0, "output": 0},
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
   它的由来是一个具体场景：用户 9/10 之后没再导入日志，问「这周花了多少」，
   agent 拿到的区间是 9/1–9/21 而只有 9/1–9/10 有数据；把缺失当 0 就会报告
   「本周比上周降了 50%」——数字算得没错，结论完全是反的。
3. **算不出价的行不加进 `costMicros`**，并且 `pricingComplete` 变 `false`、
   `unpricedModels` 列出是哪些。加 0 会得到一个偏低的总额而看不出偏低。
   `totals` 里的 **token 数照旧全算**——token 不需要价格就知道。
4. **反向区间返回 400，不返回空。** 静默的空区间会让 agent 说
   「这段时间没有用量」，而实际上它把参数写反了。区间上限 366 天。

`groupBy=PROVIDER` 时，认不出上游的那一组**分组键是 `"unknown"`，但 `provider`
字段仍是 `null`**——理由同上：枚举外的值客户端反序列化会直接抛异常。

### `GET /api/relay/usage/compare?from=&to=&metric=` → `CompareResult` 形状

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

## 预算（只存上限，花销现算）

| 接口 | 作用 |
|---|---|
| `GET /budgets/{month}` | 某月的预算 + 覆盖度 + 现算花销。`month` 是 `yyyy-MM` |
| `PUT /budgets/{month}` | 设置或覆盖上限（`capMicros` + 可选 `warnAtRatio`） |

```
GET /api/relay/budgets/2026-09
{"uid": "u_9f2…", "month": "2026-09", "configured": true,
 "capMicros": 20000000, "warnAtRatio": 0.5,
 "spentMicros": 0, "pricingAvailable": false, "unpricedDays": ["2026-09-03"],
 "pricedDays": 1,
 "coverage": {"from": "2026-09-01", "to": "2026-09-27",
              "daysWithData": 2, "daysMissing": ["2026-09-01", "…"]}}

PUT /api/relay/budgets/2026-09   {"capMicros": 20000000, "warnAtRatio": 0.5}
```

### 只存上限，从来不存「已花」

`TASKS.md` §1.1 的要求：存「已花」的话，它和用量就是两份数据，任何一次导入、纠正、
延迟落库都会让两者不一致，而对账时不知道该信哪个。**花销每次都从用量现算。**

### ⚠️ `spentMicros` 不一定完整，看 `pricingAvailable`

花销需要价目表，而**价目表可能没有覆盖到你的模型**：

- 查不到价的天**不进 `spentMicros`**，它们的日期列在 `unpricedDays` 里；
- 只要有一天算不出来，`pricingAvailable` 就是 **`false`**；
- **客户端不能把 `spentMicros` 当成完整花销**，也不能因为看到 0 就说「你花了 0 元」——
  那是 `CONTRACTS.md` §4 点名的「数字看着没错、结论是错的」。要显示「价格未知」。
- **不把算不出来的天当 0 加进去**，否则总额偏低而看不出偏低。

内置种子只含 DeepSeek（见下面「价目表」那节），所以 OpenAI / MiMo 的记录落到这里
就是 `pricingAvailable: false`——**这是预期行为，不是 bug**。

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

**这三个接口都要账号 token**：价目本身不是秘密，但它挂在账号身份下面，
匿名开放会变成一个免费的「我们支持哪些模型」探测口，而且没有别的接口是按账号过滤的
例外。要公开的话应该另开一个不带身份的前缀。

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
{"uid": "u_9f2…", "lastSettledDay": "2026-09-26",
 "balance": {"input": 100, "cache": 100, "output": 200},
 "updatedAtEpochMillis": 1790500000000, "tokensPerUnit": 10000,
 "monthTokens": {"input": 1200, "cacheRead": 900, "cacheWrite": 0, "output": 340,
                 "calls": 1, "isCurrentMonth": true}}

POST /api/relay/season/settle
{"uid": "u_9f2…", "settledDays": ["2026-09-26"],
 "gained": {"input": 100, "cache": 100, "output": 200},
 "tokensCounted": {"input": 1000000, "cacheRead": 600000, "cacheWrite": 400000, "output": 2000000},
 "balanceAfter": {"input": 100, "cache": 100, "output": 200}, "lastSettledDay": "2026-09-26"}

POST /api/relay/season/spend   {"input": 40, "cache": 25, "output": 10}
→ {"uid": "u_9f2…", "balance": {"input": 60, "cache": 75, "output": 190}}
```

> **消费者（游戏）已移出，规则保留（2026-09-30 改）。** 塔防 2026-09-30 整块搬到与
> 本工程同级的 `TokenTrail_Game/`，**这套接口暂时没有客户端消费者**。留着它是因为
> "服务端结算余额"这件事本身是通的、有测试盯着的，而且新方向里"用量 → 余额"
> 迟早还要用；删掉它要重写一遍同样的规则。**换算规则一个字没改**，
> 下面的五条规则和取整口径仍然有效。

### `POST /season/settle` 没有「结算哪一天」这个参数

和客户端 `SeasonRepository.settleCompletedDays` 的签名一致（`TASKS.md` §3.2 点名了
这条）：能算哪些天**只由数据本身决定**（`lastSettledDay` 之后、今天之前），
调用方说不了算。所以它天然幂等——连调两次，第二次的 `settledDays` 是空的。

五条规则都有对应的测试（`backend/tests/test_seasons.py`，20 个用例）：

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
检查然后一起扣成负数）。扣不动返回 **409**，金额为负返回 **400**。

### 新账号第一次结算有上限

`lastSettledDay` 为空时往回最多 `MAX_BACKFILL_DAYS = 400` 天。没有上限的话，
一个刚注册的账号会把账号存在的全部历史一次算完、一次造出一个巨大的余额。

### `costMicros` 和结算无关

结算**不需要**价格：资源余额是从 **token 数**换来的，和花多少钱无关。
（"游戏资源"是它原来的名字——塔防 2026-09-30 整块移出，换算规则一个字没改。）
所以价目表缺哪家的价并不挡结算——那边 `costMicros` 是 `null`、界面显示「价格未知」，
余额照算。

## 应用内 AI 智能体（和上面几组是两个开关）

**2026-09-27 新增。** 它是「home-screen AI assistant」（大纲 §1），
之前文档里我把它写成「建议服务」——那是我的用词问题，**只有一个 AI 智能体**。

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
 "missingData": [],
 "usage": {"input": 600, "cacheRead": 400, "cacheWrite": 0, "output": 200, "calls": 2}}

GET /api/relay/agent/status
{"uid": "u_9f2…", "configured": true, "model": "deepseek-chat",
 "ownUsageThisMonth": {...}, "ownCostMicros": 198, "pricingAvailable": true,
 "separateLedger": true}
```

### ⚠️ 它和用户的用量是两套账本，**必须是两套**

| | 用户的用量 | 智能体 |
|---|---|---|
| 用谁的 key | ——（现在是后端调模型，那条路还没写） | **我们自己的**（`FORUM_AGENT_KEY`） |
| 用量表 | `relay_usage`（**按账号**） | **`agent_usage`**（也按账号，**独立的表**） |
| 进不进用户的日汇总/预算/结算 | 进 | **不进** |
| 凭据 | 账号 token | 账号 token（**服务端现在只认这一种**） |

**为什么这是硬要求。** 用户问「我这周怎么花了这么多」——如果把智能体自己调模型的
消耗算进他的用量，**答案里的数字就被问题本身污染了**：问得越多、账越大。
而且资源余额是从用量换来的，混进去等于让「多问几句」也能换余额。
`CONTRACTS.md` §6 明确要求 agent 自己的 token 单独记账。响应里的
`separateLedger: true` 就是把这件事写在接口上。

### 限流（这里现在是唯一的限流点）

`rate_limit(db, user_id, "agent", FORUM_AGENT_REQUESTS_PER_MINUTE)`，
**按账号**、滑窗 60 秒、默认每分钟 10 次（账号和记账那几组接口都没有限流）。
智能体每次问答都要花我们的钱，所以要挡一下；超了返回 **429** 带 `Retry-After: 60`。

> 中转时代这里还有一道**按 IP** 的注册限流（防止脚本刷 relay key）。
> 那条路删掉之后自然没有了——现在没有任何接口是匿名可写的。

### key 只在服务端

大纲 §8「no client holds a model key」。所以：key 从环境变量读、
**不进数据库、不进日志、不进任何响应**；健康检查只报 `agentConfigured: true/false`；
上游返回 401/403 时**不把上游的错误文本原样回传**（里面可能带 key 的片段），
只返回一句 503。没配 key 时 `POST /agent/ask` 直接 503。

客户端只发问题：**请求体里没有 `uid`、没有 key**（`extra="forbid"`，
传了直接 400 而不是静默忽略）。

### 工具调用循环

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
   ⚠️ 这里的坑：`split_usage` 返回的 `calls` **恒为 1**（它数的是"一次上游往返"），
   所以**四个 token 桶照累加、`calls` 单独数**。两处都加的话每轮被计两次——
   踩过：2 轮的请求报出 `calls: 4`，而 **token 数是对的**，
   只核对 token 的话完全看不出问题。
4. **循环有上限（4 轮）。** 没有上限的话，一个不断要求工具的模型能把 key 刷爆，
   而且请求永远不返回。用完轮数会**如实说**「它一直在要工具，没给出最终答案」
   （进 `missingData`），而不是拿半截对话当答案。

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

### `missingData` 是机制保证，不是提示词

它由工具自己报上来的缺口拼成（缺哪些天、哪些模型没价、哪一项查不到），
再加上循环没收敛时那一句。**不要把它当成占位符删掉**：没有它，
模型会凭印象编一个数字，而"我没有数据"恰恰是最该说出口的那句话。

## 环境变量与开关

| 变量 | 作用 |
|---|---|
| `FORUM_ENABLE_RELAY` | 注册记账那几组接口（`/relay/usage*`、`/budgets*`、`/pricing*`、`/season*`）。**默认关闭，不开就是 404** |
| `FORUM_ENABLE_AGENT` | 注册智能体（`/relay/agent/*`）。**和上面分开**：两件事的风险与用途都不一样 |
| `FORUM_AGENT_KEY` | 我们自己的模型 key。**只在服务端**，从 `/etc/tokentrail-forum-relay.env` 读（权限 `640 root:tokentrail`） |
| `FORUM_AGENT_MODEL` / `FORUM_AGENT_ENDPOINT` | 默认 `deepseek-chat` / `https://api.deepseek.com` |
| `FORUM_AGENT_REQUESTS_PER_MINUTE` | 智能体按账号的限流，默认 10 |
| `FORUM_PRICING_SEED` | 启动时录入内置价目种子（幂等，不覆盖已有记录） |

> ⚠️ **名字是历史遗留，别被误导。** `FORUM_ENABLE_RELAY` 里的 `relay` 和 `/api/relay`
> 前缀一样，是转发时代留下的字面量；它现在守的是记账接口。健康检查里那个
> `relayEnabled` 同理。
>
> **`FORUM_RELAY_ALLOWED_HOSTS` / `_ALLOW_PRIVATE` / `_SELF_HOSTS` /
> `_REQUESTS_PER_MINUTE` 四个变量已经不起作用了**（2026-09-30）：它们原来是给
> "转发到用户自己的上游"做 SSRF 防护和限流的，那条路删掉之后没有东西可防。
> env 文件里留着不报错，但也不生效——**不要以为填了白名单就更安全**。

装了 `/etc/tokentrail-forum-relay.env` 才会启用；文件不存在时服务照常起，
`/api/relay/*` 返回 404（和以前一样）。

## 本地怎么验

```bash
# 单元 + 集成
python3 -m venv /tmp/tokentrail-forum-venv
/tmp/tokentrail-forum-venv/bin/pip install -r backend/requirements-test.txt
cd backend && /tmp/tokentrail-forum-venv/bin/python -m pytest -q tests   # 192 个
```

`backend/scripts/check_relay_local.py`（"注册 → 转发 → 记账 → 边界"那个端到端脚本）
**已随中转一起删除**——它验的主链路不存在了。公网验收见
`backend/scripts/check_account_deployment.py`（账号这条线，见 `backend/README.md`）。
