# 对话引擎：多格式上下文、切模型、压缩

> 这份文档是新方向（ModelPilot）主线的**实现依据**：Chat + 项目、跨模型上下文、
> Auto / 手动选模型、任务级用量。照着 `docs/modelpilot-outline/` 那 4 页大纲和
> `docs/modelpilot-ui/` 那套设计稿实现，这里是把两者落到代码结构上的那一层。
>
> **2026-09-30 定**。此前删掉的两套（中转转发、旧记账链）都不要照抄——它们的前提
> 是"我们替用户转发流量"和"服务端是唯一数据源"，而新方向的前提是
> **key 在用户手机上、由手机直连各家**。

## 0. 一句话

**每个 provider 各存一份"已经能直接发出去"的上下文；谁快到上限就压一次，
压出来的记忆改写所有上下文。** 因此用户可以随时换模型，换的那一步不需要任何转换。

## 1. 谁在哪一侧：**除了论坛，全部在手机上**

2026-09-30 定：**没有统一支付、没有套餐**——用户填自己的 API key，钱花在他自己账上，
我们不经手任何一笔。所以主线的每一样东西（对话、上下文、记忆、用量、价目）
都留在手机上，服务端只留"必须有一个公共后端才能成立"的那一件事：**论坛**。

| 手机上（`Room` + `Keystore`） | 服务端（只留这些） |
|---|---|
| provider 注册表：六家的 base URL、模型清单、能力、参考价 | 账号（`/api/account/*`，论坛的身份） |
| API key（Keystore 加密，一家一条） | 论坛：帖子 / 回复 / 点赞 / 图片 |
| 项目 / 对话 / 规范化消息 / 记忆 | 新闻采集（RSS 抓取在服务器上跑，App 读结果） |
| **上下文引擎**：渲染缓存 + 木桶阈值 + 压缩 | （智能体：用我们自己的 key，保留但不再扩） |
| 直连各家 + 流式（SSE），压缩用设置里选定的模型 | |
| **用量账本 + 价目表**（Insights 全靠它，全在本机） | |

**这么分的三个好处**：① 我们不经手用户的钱，也就没有"收费"这件事，更没有
"大家还没体验上就先收费"的问题；② 服务端不再承载对话与用量（服务器压力只剩论坛）；
③ 用户凭据一次都没有离开过设备。

**代价（要认下来）**：① **换手机/重装会丢对话与用量**——除非走"导出/导入"
（大纲 §4 的 export 那一条，将来做）；② 用量与费用是**本机统计**，
换个设备看到的数不一样；③ 价目表在手机上，所以要能更新（见下）。

## 2. 上下文引擎（这是这一版的核心）

### 2.1 两种表示

```
CanonicalMessage（规范化，唯一事实来源）
  id, role(user|assistant|tool|system), text, attachments[], toolCalls[], toolResult,
  provider?, model?, createdAt, tokensIn/out(实测), truncatedFrom?

RenderedContext（某个 provider 的"可直接发送"形态，含缓存）
  provider, model, limitTokens, renderedTokens,
  payload: 该家 API 的 messages 数组（system 位置、tool 格式、图片编码都已就位）
  renderedAtCanonicalId: 渲染到哪一条为止（缓存有效性判据）
```

- **发送时零转换**：直接把 `payload` 发出去。
- 任何新消息、任何一次压缩 → 该对话的所有 `RenderedContext` 作废重渲染
  （`renderedAtCanonicalId` 对不上就重渲染，不做增量修补——增量修补是这类系统
  最容易出静默错的地方）。

### 2.2 各家的差异只有三处

1. **system prompt 的位置**（OpenAI 系：放 messages[0]；Anthropic：顶层 `system` 字段）
2. **工具的声明与调用格式**（`tools` / `tool_calls` vs `tools` + `tool_use` / `tool_result`）
3. **多模态附件的编码**（`image_url` data URL vs `source.base64`）

所以渲染器只有两个：`OpenAiCompatibleRenderer`（DeepSeek / OpenAI / GLM / Kimi /
MiMo / Seed 六家共用，差别只在 base URL 与模型清单）和 `AnthropicRenderer`。

### 2.3 木桶效应：什么时候压、压什么

```
limit(model)            = 该模型的上下文上限 × 安全系数（0.8）
usable(context)         = limit − 预留输出 − 本轮附件预估
threshold               = 所有"已启用 provider"里最小的 usable
```

- 最短的那块板（最小的上限）**用到 `threshold − 预留`（默认预留 10K，可配）**
  就触发压缩。**只压一次**：选设置里指定的压缩模型，把"要压的那段"总结成
  一条 `Memory`（这是 provider 无关的对象：`{id, chatId, fromMessageId, toMessageId,
  summary, madeBy:{provider,model}, createdAt, tokensIn, tokensOut}`）。
- 压完把**所有** provider 的上下文都改写成"记忆 + 压缩点之后的消息"，
  于是六份上下文重新对齐。**压缩只花一次钱**，这是这套设计最值的地方。
- **绝不静默截断**：没有成功压缩时（比如压缩模型不可用），退化成"丢最老的若干轮"，
  并且**在界面上写明丢了什么**（`truncatedFrom`），回答里也带着这句话。
- 压缩段落的边界**落在 user 消息之前**，不切开一问一答。

### 2.4 记忆是可以看和改的（大纲 §4 "inspect/correct the retained memory"）

设计稿 `09-memory.png` 那一页就是它。用户能编辑摘要文本、能删掉一条记忆、
也能手动"现在压一次"。编辑后的记忆同样让所有渲染缓存失效。

## 3. 六个 provider（注册表是数据，不是代码）

| provider | base URL | 适配器 | 说明 |
|---|---|---|---|
| DeepSeek | `https://api.deepseek.com` | OpenAI 兼容 | 已有账号即可 |
| OpenAI（GPT） | `https://api.openai.com` | OpenAI 兼容 | |
| GLM（智谱） | `https://open.bigmodel.cn/api/paas/v4` | OpenAI 兼容 | |
| Kimi（月之暗面） | `https://api.moonshot.cn` | OpenAI 兼容 | |
| MiMo（小米） | 待填（以官方文档为准） | OpenAI 兼容 | 价格/上下文以官方为准 |
| Seed（豆包） | 待填（以官方文档为准） | OpenAI 兼容 | 同上 |

**能力与上限写进注册表而不是代码**：`text / vision / pdf / tools`、上下文上限、
每 1M token 的四个桶价格（`input / cacheRead / cacheWrite / output`）。
**没实测过的价格就留空**——空 = 界面显示"价格未知"，**不许编数字**
（`CONTRACTS.md` §4 那条底线在新账本里继续有效）。

Auto 第一版：**用户已配置 key 的模型里，选"能力满足且最便宜"的那个**，
并把选型理由存下来（`routeReason`），给设计稿里那个 `Details` 用。
后面再按大纲加"任务类型 + 偏好 + 预算"打分。

## 4. 手机上要新增的东西（服务端**一张表都不加**）

| 存放 | 用途 |
|---|---|
| `projects` / `chats` / `messages`（Room） | 规范化对话：项目分组、显式指令、历史 |
| `memories`（Room） | 压缩产物（provider 无关），用户可看可改可删 |
| `usage_call` 的 **APP 来源**（复用已有的那张表） | **账本**：每次模型调用一行 —— provider / model / route(AUTO\|MANUAL) / chatId / toolCalls / 四桶 token / 来源(APP\|IMPORTED) / `call_id` / **`taskId`（一轮提问一个）** / **`kind`（ANSWER\|COMPRESS\|TOOL）** |
| `pricing_rates`（Room，由 `BundledPricingSource` 供种子） | 价目：按**生效日期**的四桶费率，改价要留痕 |

- 金额**在本机按生效日期的费率算**，算不出价就是 `null`（界面上是"价格未知"，
  **不是 0**）——`CONTRACTS.md` §4 那条底线换了地方但没变。
- 表结构直接复用已有的 `data/local/`（`usage_call` / `daily_usage` / 价目），
  只多一个"这次调用是 App 自己发的"来源标记与 `route`/`chatId`/`toolCalls` 三列。
- 服务端**没有** `/api/chat`、`/api/usage`、`/api/pricing`：一个都不加。

## 5. 前端：先复刻 outline 里那 4 张图

`docs/modelpilot-outline/assets/` 那四张（= `modelpilot-ui/screens/` 里的
`01-home` / `03-conversation-image-result` / `04-models` / `13-insights`）：

1. **Chat 首页**：Projects 分组（每条对话带 `Last · <模型>` 徽章）+ Recent chats + 底部输入条（`+` / `Auto ▾` / `No project ▾` / 发送）
2. **对话页**：`Auto → <模型> Details` 路由徽章、附件卡、回复、`Save image` / `Edit`、底部输入
3. **模型选择弹层**：`Auto` 置顶（"Chooses for each message"）、`Choose manually`、
   **`Unavailable for this task` 置灰**（设计稿里 PDF/图片任务下 DeepSeek/Claude 是灰的）
4. **Insights**：估算费用（含 `Model API + tools` 拆分）、Total tokens、Model calls、
   预算进度、Usage trend（Spend / Tokens / Calls 可切）

底部导航从现在的「统计 / 论坛 / 我的」改成 **Chat / Insights / Explore / Me**：
`统计` 并进 Insights，`论坛 + 新闻` 并进 Explore。

## 6. 做的顺序（每一步都能单独验证）

1. **上下文引擎**（手机侧，纯 Java + 单测）：规范化消息、两个渲染器、
   木桶阈值、压缩触发与改写、`truncatedFrom` 的诚实降级。
2. **provider 注册表 + key 存储 + 一个真 provider 的流式对话**（DeepSeek 先跑通）。
3. **本机账本与价目**：把每次调用记进 `usage_call`（来源 APP），Insights 直接读本机。
4. **四屏 UI** 按设计稿复刻。
5. 其余五家 provider 逐个接（改配置为主）+ Auto 打分升级。
6. 导出/导入（对话与用量），把"换手机会丢"这件事补上——大纲 §4 有这一条。

### 6.1 落地进度（2026-10-04 记）

**已经能跑通的**（每一步都有对应的类与测试）：

| 步骤 | 状态 | 在哪 |
| --- | --- | --- |
| 1. 上下文引擎 | 完成 | `chat/ContextEngine` `ContextRenderer` `OpenAiCompatibleRenderer` `AnthropicRenderer`（`ContextEngineTest` 等单测钉住） |
| 2. 注册表 + key + 流式对话 | 完成（只有 DeepSeek 实测过） | `chat/ProviderRegistry`、`data/ProviderKeys`（Keystore）、`data/remote/ProviderClient` |
| 3. 本机账本 | 完成 | `chat/UsageRecorder` + `chat/CallLedger`（每次调用一行，`uid` 固定为 `local`，见 `CallLedger` 的类注释）。**2026-10-04 补上任务级成本**：一轮提问生成一个 `taskId`，回答与压缩共用它；`kind` 区分 ANSWER / COMPRESS。真机上验过：假上游收到 6 次回答 + 2 次压缩，账本 8 行，触发压缩的那两轮各自能看到 `COMPRESS,ANSWER` 两行同一个 task |
| 4. 四屏 UI | **3/4** | 首页 `ui/chat/ChatHomeFragment`、对话页 `ChatConversationFragment`、模型弹层 `ModelSheetFragment`；**Insights 还没做**（现在点 Insights 看到的是旧的统计页，它读 `daily_usage` 那张表，而那张表目前只滚导入的记录——所以它不会显示 App 自己发出去的调用） |
| 5. 其余五家 | 未开始 | 注册表里已有六家的 base URL 与模型清单，但只有 DeepSeek 真跑过；其余四家的 `streamUsage` 开关保持 false 等实测 |
| 6. 导出/导入 | 未开始 | |

**另外做了两件不在上面的清单里、但不做就没法用的事**：

- `Me → API keys`（`ui/settings/ApiKeysDialog`）：用户自己填 key 与请求地址，
  并在同一页选**压缩模型**（没选过就是 Auto 挑最便宜的）。
- 压缩本身走 `chat/Summarizer` + `ProviderClient.complete(...)`（非流式那一趟），
  指令见 `Summarizer.INSTRUCTION`——**改那句话等于改产品行为**。

### 6.2 按 outline v12 补的第二批（2026-10-04 记）

| outline 里的条目 | 状态 |
| --- | --- |
| §4-5「Populate dated prices」 | **做了**：`BundledPricingSource` 录了 DeepSeek 两个模型（数字与链接抄自官方定价页）。其余五家留空 —— 没核过就不能写 |
| §6「save the selected route, **policy version and reason**」 | **做了**：`usage_call` 加 `reason` / `policy`（迁移 v6），`AutoRouter.POLICY_VERSION` = `capability-then-price-v1` |
| §6「message-level token fields are not wired」 | **做了**：上游报的 usage 回填到那条回答上（`MessageEntity.tokensIn/Out`） |
| §4-5 / §5 / §8 W10–12「Connect Insights to local call records」+ local budget | **做了**：第 4 屏改成读本机账本（`ui/insights/`）。旧统计页读 `daily_usage`（只滚导入记录）且开着桩数据，显示的数字和真实花费无关，已从导航上摘掉 |
| §4-3 §5 记忆的查看/编辑/重置界面 | **做了**：对话页 `⋮ → Conversation memory`（`ui/chat/MemorySheetFragment`），可看、可改、可丢；丢 = 原文重新进上下文 |
| §4-4 §7.2 文件选择 + PDFBox 抽取 + source links | 还没做 |
| §4-6 §8 W10–12 导出 | 还没做 |
| 对话页 `⋮`（记忆 / 改名 / 删除） | **做了**：改名复用"问一个名字"的对话框；删除会连消息、记忆、本机调用记录一起删，且删完自动退回列表 |
| §4-2 §7.1 其余六家的实测 | 还没做（只有 DeepSeek 端到端跑过） |
| §4-1 §5 草稿恢复 | **做了**：`data/Drafts` 按对话各存一份，`onPause` 存、进页面恢复、发出去就清（转屏本来就由 Android 保，这份管的是进程被杀） |

**Insights 的口径**（`ui/insights/Insights.java`，纯函数、有单测）：
token 按四桶加；算不出价的调用**单独计数并显示**（"其中 N 次还没有价格"）；
Auto 占比只按回答算，不把压缩算进去；本地月度上限存在 `data/Budget`，
**和套餐/充值/统一支付无关**（用户自己填一个数，超了只提醒）。

**已知的、有意留着的坑**（不是忘了）：

- 附件（图片/PDF）在数据模型与两个渲染器里都通了，但界面上的 `+` 还没接；
  图片编辑按 §7 不做。
- 对话页的 `⋮`（重命名/删除/看记忆）与搜索还没做，点了会说"还没做"。
- Insights 需要的聚合查询还没写：`daily_usage` 的滚动只收 `IMPORTED`，
  App 自己那些调用要另走一条（`usage_call` 里 `source = APP` 且 `uid = local`）。
  任务级那一条现在可以直接查：`GROUP BY task_id`（回答与压缩已经挂在同一个任务号下）。
- ~~`BundledPricingSource` 还是空表~~ **2026-10-04 已录 DeepSeek**；
  其余五家仍未核价，界面上照实显示"价格未知"。
- `MIGRATION_3_4` 里的四张表原来把若干列写成了 NOT NULL，而实体里那些字段没有
  `@NonNull`（Room 按可空生成）——**迁移建出来的表与全新安装不一致**。
  2026-10-04 修好了，同时发现真正的原因是 **androidTest 源集里有个引用旧导航 id
  的文件让整个源集编译不过**，于是所有仪器测试（包括这套迁移测试）几周都没跑过。
  这类"测试悄悄不跑了"比测试失败更危险，改完 id 之后 5 个迁移用例全过。

## 7. 明确不做（这一版）

- 图片编辑（大纲：等能力/接入验证之后再说）——设计稿里的入口先置灰并写明原因。
- 自主写代码、发邮件、插件市场（大纲 §4 范围外）。
- 赛季/资源/游戏（已移出，见 `../TokenTrail_Game/`）。
- 后端替用户调模型（与"key 不离开手机"冲突；将来要改就是换第 1 节那张表）。
