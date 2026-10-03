# 服务端 API 契约

> **2026-09-30（第二刀）：记账链整块删除，本文件按删完之后的现状重写。**
>
> 这一轮删的是**整条记账链**——用量、预算、价目、赛季四组接口，连同它们的表、
> 模块和 Android 侧的客户端类。第一刀（同一轮早些时候）删的是「API 中转」：
> 用户把自己上游的 `URL + API key` 填进 App，服务端替他转发（cc-switch 指过来），
> 顺手从响应里把 usage 抽出来落库。新方向（ModelPilot 大纲）反过来：
> **用户在 App 里提问 → 后端用我们自己的模型连接去调 → 产生用量 → 记账**。
>
> **为什么第二刀要删干净，而不是"留着不用"。** 记账链没有生产者：删掉中转之后，
> 没有任何代码会往用量表里写新记录（App 侧提交导入记录那条路也没接）。
> 它也没有消费者：App 不再打任何用量/预算接口。**空转的接口比没有接口更糟**——
> 它会让人（和 AI）以为账已经在记了，照着接口清单去写 Insights，
> 写出来的东西读一张永远空的表。Insights 要等「后端替用户调模型」那条路写出来、
> 账重新开始记之后**重新设计**，**别照抄删掉的那套**（那套是给"转发旁路抽取的用量"
> 设计的，新链路的数据形状不一样，见 `CONTRACTS.md` §4）。
>
> | 删了什么 | 原位置 |
> |---|---|
> | 路由：`/api/relay/usage*`（按次 / 按天 / 按模型 / 对比）、`/budgets*`、`/pricing*`、`/season*` | `backend/modelpilot_forum/api_routes.py`（该文件已不存在） |
> | 模块：用量账本、预算、价目、价目种子、汇总对比、赛季结算 | `usage_store.py`、`budgets.py`、`pricing.py`、`seed_pricing.py`、`summary.py`、`seasons.py` |
> | 表：用量流水、赛季余额、赛季结算、预算、价目 | `relay_usage`、`season_balances`、`season_settlements`、`budgets`、`pricing_rates`（老库启动时由 `schema.drop_obsolete_tables` 删掉） |
> | Android 侧：服务端用量客户端与预算仓储 | `data/remote/ServerApi`、`HttpBudgetRepository`、`BudgetRepository`、`StubBudgetRepository`、`contract/model/Budget`、`contract/tool/{BudgetStatus,Coverage,UsageSummary,CompareResult}` |
> | 智能体的三个数字类工具：`getUsageSummary`、`getBudgetStatus`、`compareAgentCosts` | `backend/modelpilot_forum/agent_tools.py`（**只读工具现在只剩两个**） |
>
> **唯一保留的用量相关东西**：`usage.py`（只剩一个函数 `split_usage`，
> 智能体把模型响应的 usage 归一成四个桶要用它）和 `agent_usage` 表
> （智能体自己那本账，按账号记）。
>
> **改名（同一轮做的，和删减是两件事）**：Python 包 `tokentrail_forum` → **`modelpilot_forum`**；
> 服务名 `tokentrail-*` → `modelpilot-*`；路径 `/opt/tokentrail` → `/opt/modelpilot`、
> `/var/lib/tokentrail-forum` → `/var/lib/modelpilot-forum`；库文件 `forum.sqlite3` →
> `modelpilot.sqlite3`；**env 变量前缀 `FORUM_*` → `MODELPILOT_*`**；
> env 文件 `/etc/tokentrail-forum.env` → `/etc/modelpilot-forum.env`、
> `/etc/tokentrail-forum-relay.env` → `/etc/modelpilot-agent.env`。
> 旧名字**不再读**，不是"两个都认"。
>
> **URL 前缀里的历史字面量只剩一个：`/api/agent`。** 它 2026-09-30 之前是
> `/api/relay/agent`——记账接口删掉之后 `relay` 这个字面量就没有任何意义了
> （前缀下只剩智能体），所以一起改掉。App 侧目前**没有**调智能体，这次改动
> 不需要动客户端；将来 App 要接的时候用新前缀。**`/api/relay/*` 整个前缀不存在了**，
> 打过去是 404。

## 前缀与身份

```
账号 token ──┬──► /api/account/*            账号（注册/登录/查我/退出）
（App 用）    ├──► /api/forum/*              论坛与新闻（图文、点赞、评论、官方帖、热帖）
              └──► /api/agent/*              应用内智能体（ask / status）
```

**身份只有一个：账号 token。**（2026-02 起收紧；2026-09-30 中转删除后它成了唯一一种凭据。）

- 账号：用户名 + 密码 → `tt_app_` 前缀的会话 token。`user_id` 形如 `u_<32 hex>`。
  密码用 `hashlib.scrypt` 存（每用户独立盐，`scrypt$n$r$p$salt$hash`）。
- **智能体那本账（`agent_usage`）的归属列是 `user_id`**（记账链删掉之后，
  这是服务端唯一一张还按账号记数的表）。
- **智能体只认账号 token**，论坛也只认账号 token（或 Debug 下的 `tt_test_` 测试身份）。
- `tt_` 这个前缀曾经是 relay key，**现在不再签发、也不再认**。拿一个 `tt_...`
  打任何接口都是 401。

> **为什么"身份是账号"这件事要单独讲一段。** 更早的时候 relay key 自带身份
> （`uid = sha256(relay key)`），用量和余额都挂在它上面，代价是**换个 key 就换个人**：
> 余额清零、历史断掉，而换 key 本来只是换一条提交用量的路；没配中转的用户在服务端
> 干脆没有身份。现在有了 App 自己的账号表（`accounts`），身份就有了唯一的落点。
> 那一次改动**没有引入任何映射表**——`owner_of(secret)` 把任意凭据解成账号的
> `user_id`，所有读写都按它。中转删掉之后连"任意凭据"都不需要了：
> `Accounts.resolve(authorization)` 只认账号 token 一种。

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

账号路由**永远注册**，没有开关（`build_account_router` 无条件 `include_router`）。

## 论坛与新闻（`/api/forum/*`）

路由写在 `app.py` 里（存储细节在 `store.py`，新闻采集在 `news_job.py`）。
论坛存储与新闻库是**两个库**：`store.connect()` 读写本库，`store.news_connect()`
只读新闻快照。完整契约见 [`FORUM_API.md`](FORUM_API.md)，下面是现状清单：

| 接口 | 作用 |
|---|---|
| `GET /forum/posts` | 社区帖分页（游标 `cursor` + `limit`） |
| `POST /forum/posts` → 201 | 发帖。**要 `Idempotency-Key`**（最长 128 字符） |
| `GET /forum/posts/{id}` | 帖子详情。**社区帖查不到会去新闻库里再找一次**——官方帖的详情也要能打开，否则列表里点进去是 404，用户会以为链接坏了 |
| `GET/POST /forum/posts/{id}/replies` | 评论列表与发表（发表同样要幂等键） |
| `PUT/DELETE /forum/posts/{id}/like` | 点赞 / 取消（幂等：点两次只算一次） |
| `GET /forum/news` | 新闻，按 `published` 倒序 |
| `GET /forum/official` | **官方帖 = `news` 表里的资讯**，以 `ForumPost` 形状返回 |
| `GET /forum/hot` | 社区热帖（点赞 1 分、评论 2 分） |
| `GET /forum/highlights` | 官方帖 + 社区热帖，**两类各自带 `source`** |
| `GET /forum/me/posts` / `GET /forum/me/threads` | 本人发过的帖子 / 带回复的完整会话 |
| `POST /forum/images` → 201 | 上传图片（最大 10 MB，JPEG/PNG/WebP/GIF） |
| `GET /forum/images/{id}` | 取图。**没挂到帖子上的图只有属主能取** |
| `POST/DELETE /forum/test-session` | 免账号测试身份（Debug 测试区专用，见 `backend/README.md`） |

四条口径：

1. **官方帖直接被映射，不另存一份进 `posts`。** 存两份的话同一个标题在两处，
   改一次采集逻辑要改两个地方，而且迟早漂移。代价是官方帖不能评论（它们在
   `news` 表里，没有 `post_id` 可挂评论）——这是有意的：官方帖是公告，
   讨论去对应的社区帖。
2. **官方帖不是"拿别人的文章冒充官方帖"**：那些文章是我们自己的采集任务为这个项目
   采的，`source_name` / `original_url` 跟着返回，界面上看得出原始出处。
   `source` 填 `OFFICIAL` 不是装饰：客户端据此渲染官方徽章，agent 据此决定措辞
   （官方帖可作事实来源，社区帖只能当经验分享）。
3. **`modelFilter` 只让社区那半边返回空，官方帖不受它影响。** 资讯本来就带 `category`，
   按模型筛没有意义，而"筛了模型就什么官方内容都看不到"会让 agent 以为没有官方公告。
4. **热帖算法写在 SQL 里**（`likes` 计 1、`replies` 计 2），`since` 是可选的起始日。

## 应用内 AI 智能体（`/api/agent/*`）

**2026-09-27 新增，2026-09-30 收缩。** 它是「home-screen AI assistant」（大纲 §1），
之前文档里我把它写成「建议服务」——那是我的用词问题，**只有一个 AI 智能体**。

### 接口

| 接口 | 作用 |
|---|---|
| `POST /agent/ask` | 收一个问题，返回回答 + 证据 + `missingData` + 它自己的用量（token） |
| `GET /agent/status` | 配好了没有 + 它自己这个月用了多少 token |

```
POST /api/agent/ask   Authorization: Bearer tt_app_...
                      {"question": "最近论坛有什么值得看的？"}
{"text": "…", "model": "deepseek-chat", "createdAtEpochMillis": 1790500000000,
 "evidence": [{"display": "官方帖 3 条、社区热帖 2 条", "basis": "…", "fromTool": "getForumHighlights"}],
 "toolCalls": [], "missingData": [],
 "usage": {"input": 600, "cacheRead": 400, "cacheWrite": 0, "output": 200, "calls": 2}}

GET /api/agent/status
{"uid": "u_9f2…", "configured": true, "model": "deepseek-chat",
 "ownUsageThisMonth": {"input": 1200, "cacheRead": 900, "cacheWrite": 0, "output": 340, "calls": 1},
 "separateLedger": true, "costReporting": "unavailable"}
```

> **`/status` 不再报钱（2026-09-30）。** 原来它报 `ownCostMicros` 和 `pricingAvailable`，
> 靠的是服务端的价目表；价目表随记账链一起删了，**没有费率就算不出金额**。
> 按 `CONTRACTS.md` §4 那条底线，算不出来就**不能说 0**，所以这里**直接不报这个字段**，
> 而不是填一个 0 上去，改成 `"costReporting": "unavailable"` 明说这件事。
> `ownUsageThisMonth` 还在，而且现在是**只报 token**（四个桶 + `calls`）。

### 只读工具现在只剩两个

| 工具 | 状态 |
|---|---|
| `getForumHighlights` | ✅ 官方帖 + 社区热帖，**两类各自带 `source`** |
| `getMyThreads` | ✅ 按账号的 `user_id` 查自己发过的帖子与收到的回复。**直接 join，不需要任何绑定表**（智能体凭据是账号 token，解出的 `user_id` 就是 `posts.author_uid`）。查不到时返回结构化的「不知道」（`unavailable` / `reason`），不是空列表——空列表读起来是「你没发过帖子」，真相是「我查不到」 |

`getUsageSummary` / `getBudgetStatus` / `compareAgentCosts` 读的是用量、预算、价目，
**随记账链删掉了**：留一个没有数据源的工具只会让模型编数字。将来「后端替用户调模型」
那条路写出来、账重新开始记之后，再按当时的表结构**重新设计**它们，别照抄。

### 工具调用循环

模型能调上面两个只读工具，服务端执行、把结果喂回去、再让它据此回答。四条实现口径：

1. **`uid` 不在任何工具的 JSON Schema 里。** 模型看不见它，也就传不了它；
   身份由服务端从凭据解出，**传了也不影响算谁的账**。
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

### `missingData` 是机制保证，不是提示词

它由工具自己报上来的缺口拼成（哪一项查不到），再加上循环没收敛时那一句。
**不要把它当成占位符删掉**：没有它，模型会凭印象编一个数字，
而"我没有数据"恰恰是最该说出口的那句话。

### ⚠️ 它和用户的用量是两套账本，**必须是两套**

| | 用户的用量 | 智能体 |
|---|---|---|
| 用谁的 key | ——（记账链已删，新的"后端调模型"那条路还没写） | **我们自己的**（`MODELPILOT_AGENT_KEY`） |
| 用量表 | ~~`relay_usage`~~ **已删（2026-09-30）** | **`agent_usage`**（也按账号，**独立的表**） |
| 记账链删掉之后 | 没有表、没有接口 | 只剩这一本 |

**为什么这曾经是硬要求、现在也仍然要照着做。** 用户问「我这周怎么花了这么多」——
如果把智能体自己调模型的消耗算进他的用量，**答案里的数字就被问题本身污染了**：
问得越多、账越大。所以新的记账链设计出来时，智能体的账**必须仍然单独一本**。
响应里的 `separateLedger: true` 就是把这件事写在接口上。
（`CONTRACTS.md` §6 明确要求 agent 自己的 token 单独记账。）

### 限流（这里现在是唯一的限流点）

`rate_limit(db, user_id, "agent", MODELPILOT_AGENT_REQUESTS_PER_MINUTE)`，
**按账号**、滑窗 60 秒、默认每分钟 10 次。智能体每次问答都要花我们的钱，
所以要挡一下；超了返回 **429** 带 `Retry-After: 60`。
（论坛那边发帖 / 评论 / 点赞 / 上传各有自己的 `rate_limit`，但记账链删掉之后，
**按账号限流并且要花钱的只剩智能体这一处**。）

> 中转时代这里还有一道**按 IP** 的注册限流（防止脚本刷 relay key）。
> 那条路删掉之后自然没有了——现在没有任何接口是匿名可写的
> （`/api/forum/test-session` 只存在于隔离的 `/test-api` 测试服务）。

### key 只在服务端

大纲 §8「no client holds a model key」。所以：key 从环境变量读、
**不进数据库、不进日志、不进任何响应**；健康检查只报 `agentConfigured: true/false`；
上游返回 401/403 时**不把上游的错误文本原样回传**（里面可能带 key 的片段），
只返回一句 503。没配 key 时 `POST /agent/ask` 直接 503。

客户端只发问题：**请求体里没有 `uid`、没有 key**（`extra="forbid"`，
传了直接 400 而不是静默忽略）。

## 健康检查（`GET /health`）

**不需要身份**，字段就这七个：

```json
{"status": "ok", "service": "modelpilot-forum",
 "newsCount": 42, "newsLastCollectedAt": "2026-09-30T12:00:00Z",
 "newsSourceErrors": {},
 "testSessionsEnabled": false,
 "agentConfigured": true, "agentModel": "deepseek-chat"}
```

> **`relayEnabled` 也没有了（2026-09-30）。** 那个字段原来报「记账那几组接口开没开」，
> 记账链删掉之后没有东西可报。现在**唯一的开关是智能体**，报的是
> `agentConfigured`（只报配没配，**永远不报 key 本身**）和 `agentModel`。
> 更早的 `authConfigured` 在 2026-02 账号归自己之后就删了。

## 环境变量与开关

| 变量 | 作用 |
|---|---|
| `MODELPILOT_DATA_DIR` | 数据目录（库、图片、备份）。生产是 `/var/lib/modelpilot-forum` |
| `MODELPILOT_PUBLIC_ORIGIN` | **必填**，必须是 HTTPS origin（没有路径、查询、用户信息），否则启动直接 `ValueError` |
| `MODELPILOT_PUBLIC_API_PREFIX` | `/api`（默认）或 `/test-api`。测试区用后者，且只有它能开测试身份 |
| `MODELPILOT_ENABLE_TEST_SESSIONS` | 免账号测试身份。**必须配 `/test-api` 前缀**，否则启动报错 |
| `MODELPILOT_NEWS_DATABASE` | 新闻库路径（只读快照） |
| `MODELPILOT_ENABLE_AGENT` | 注册智能体（`/agent/*`）。**默认关闭，不开就是 404** |
| `MODELPILOT_AGENT_KEY` | 我们自己的模型 key。**只在服务端**，从 `/etc/modelpilot-agent.env` 读（权限 `640 root:tokentrail`） |
| `MODELPILOT_AGENT_MODEL` / `MODELPILOT_AGENT_ENDPOINT` | 默认 `deepseek-chat` / `https://api.deepseek.com` |
| `MODELPILOT_AGENT_REQUESTS_PER_MINUTE` | 智能体按账号的限流，默认 10 |

> ⚠️ **前缀 2026-09-30 从 `FORUM_*` 改成 `MODELPILOT_*`，老名字不再读。**
> 服务器上的 env 文件在同一个部署里换成新名字，所以不会出现"改了一半、
> 服务用默认值静默起来"的情况（`Settings.from_environment` 里那句注释就是记这个）。
>
> **已经不存在、设了也不生效的变量**（删减带出来的，env 文件里留着不报错）：
>
> | 变量 | 为什么没了 |
> |---|---|
> | `FORUM_ENABLE_RELAY` / `MODELPILOT_ENABLE_RELAY` | 它守的记账那几组接口删了 |
> | `FORUM_PRICING_SEED` / `MODELPILOT_PRICING_SEED` | 价目表随记账一起删了 |
> | `FORUM_RELAY_ALLOWED_HOSTS` / `_ALLOW_PRIVATE` / `_SELF_HOSTS` / `_REQUESTS_PER_MINUTE` | 它们是给"转发到用户自己的上游"做 SSRF 防护和限流的，那条路删掉之后没有东西可防。**别以为填了白名单就更安全** |

装了 `/etc/modelpilot-agent.env` 才会启用智能体；文件不存在时服务照常起，
`/api/agent/*` 返回 404（systemd 单元用 `EnvironmentFile=-` 引它，前缀 `-` 表示
文件缺失不算错）。

## 本地怎么验

```bash
# 单元 + 集成
python3 -m venv /tmp/modelpilot-forum-venv
/tmp/modelpilot-forum-venv/bin/pip install -r backend/requirements-test.txt
cd backend && /tmp/modelpilot-forum-venv/bin/python -m pytest -q tests   # 76 个
```

覆盖账号、论坛/新闻、智能体、库形状（`tests/test_schema.py` 盯着
`drop_obsolete_tables` 真的把废弃表删掉）和测试身份。

`backend/scripts/check_relay_local.py`（"注册 → 转发 → 记账 → 边界"那个端到端脚本）
**已随中转一起删除**——它验的主链路不存在了。同理，原来覆盖用量/预算/价目/赛季的
`tests/test_usage.py`、`test_summary.py`、`test_budgets.py`、`test_pricing.py`、
`test_seasons.py` 也一起删了。现在**唯一的端到端验收**是
`backend/scripts/check_account_deployment.py`（对着公网跑，见 `backend/README.md`）。
