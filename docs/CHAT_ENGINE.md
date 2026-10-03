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
| `usage_call` 的 **APP 来源**（复用已有的那张表） | **账本**：每次模型调用一行 —— provider / model / route(AUTO\|MANUAL) / chatId / toolCalls / 四桶 token / 来源(APP\|IMPORTED) / `call_id` |
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

## 7. 明确不做（这一版）

- 图片编辑（大纲：等能力/接入验证之后再说）——设计稿里的入口先置灰并写明原因。
- 自主写代码、发邮件、插件市场（大纲 §4 范围外）。
- 赛季/资源/游戏（已移出，见 `../TokenTrail_Game/`）。
- 后端替用户调模型（与"key 不离开手机"冲突；将来要改就是换第 1 节那张表）。
