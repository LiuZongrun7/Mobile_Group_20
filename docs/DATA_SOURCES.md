# 用量数据从哪来

这份文件回答一个问题：**用户在我们的软件里填点什么，我们就能替他把自己的用量拉回来。**
它是 `data/remote/` 那层 fetcher 的规格，和 [`CONTRACTS.md`](CONTRACTS.md) §4 的数据流配套。

**先把两条路分开**，它们经常被混着说：

| | 谁去取 | 用户做什么 | 在哪 |
| --- | --- | --- | --- |
| **拉取 fetch** | 我们，主动打 provider 的接口 | 在 App 里填一次凭据 | `data/remote/` ← **主线** |
| **导入 import** | 不用取，用户给文件 | 手动导出、把文件拖进来 | `data/importer/` ← 兜底 |

**两条路产出的东西是同一个**（`UsageCall`），所以下游——去重、日汇总、结算、游戏资源——
完全一样。这也是为什么 fetcher 层很薄：它只负责**把字节弄到手**，解析仍然归 `data/importer/`。
（DeepSeek 那条最明显：控制台点「导出」和程序去拉，拿回来的是**同一个 zip**，
我们只是替用户点了那个按钮。）

---

## 1. 三家对照

### OpenAI / Codex —— 唯一一条官方支持的

| | |
| --- | --- |
| **用户填** | 一个 **Admin key**（`sk-admin-...`）。普通项目 key 打过去是 **403**，因为它没有 `api.usage.read` 这个 scope。只有组织的 owner 能建 admin key，而且 admin key **不能**用来跑推理 |
| **我们打** | `GET https://api.openai.com/v1/organization/usage/completions`，参数 `start_time` / `end_time` / `bucket_width=1m\|1h\|1d` / `group_by=model` / `limit` / `page`。旁边还有 `/v1/organization/costs` 给金额 |
| **拿到什么** | **按时间桶聚合**，不是逐条调用。每行 `input_tokens` / `input_cached_tokens` / `output_tokens` / `num_model_requests`，带 `model` / `project_id` / `api_key_id` / `user_id` / `batch`，分页靠 `has_more` + `next_page` |
| **官方吗** | ✅ 官方文档、官方 scope、有分页。这是三家里唯一一条能写进报告的 |
| **四桶** | input ✅ / cacheRead ✅（`input_cached_tokens`）/ output ✅ / **cacheWrite ❌**（OpenAI 不单列） |
| **坑** | 社区报告某些模型下 `input_cached_tokens` **恒返回 0**。接之前先拿真 key 打一次，别照着文档假设它有值 |

### DeepSeek —— 私有接口，但粒度最细

| | |
| --- | --- |
| **用户填** | 浏览器**登录态**里的 `userToken`。**不是 API key**——API key 打不了这个接口 |
| **我们打** | `https://platform.deepseek.com/api/v0/usage/export`，返回一个 zip，里面是 `amount-*.csv` + `cost-*.csv` |
| **拿到什么** | 逐条的 token 数和金额，比 OpenAI 那个桶细得多 |
| **官方吗** | ❌ 私有控制台接口。官方 API Reference 里**没有任何**用量 / 消费 / 成本端点 |
| **四桶** | input / cacheRead / output 能填；**cacheWrite 恒 0**（DeepSeek 不计缓存写入，`PricingRate` 里那栏填 0 就是为它准备的） |
| **坑** | token 会过期、登出即失效；拿它去访问控制台接口在 DeepSeek 的 ToS 上**站不住**；接口随时可能改，没有任何兼容承诺 |

> 这张表是**接之前**的预判。2026-09-26 拿到真实导出验过之后，有几条要改
> （列名、币种、切天口径、以及「不用自己维护价目表」）——**以 §4 为准**。

### Xiaomi MiMo / ZCode —— 接不上

> **本节是社区资料拼出来的，我们一条都没验过。** 小米只公开了
> [价目表](https://mimo.mi.com/docs/en-US/price/pay-as-you-go) 和
> [首次调用](https://mimo.mi.com/docs/en-US/quick-start/summary/first-api-call) 这类页面，
> 用量端点官方文档里**没有**。底下的 base URL、控制台路径、cookie 名字来自第三方插件和博客
> （CodexBar 的 `docs/mimo.md`、cc-switch 的用量查询、skillsmp 上的 mimo-usage skill）。
> 报告里要按「未验证的社区发现」讲，别说成官方接口；**等拿到一个真 key 要重新核一遍**。

| | |
| --- | --- |
| **用户填** | 目前**没得填**（走手动导入）。按量付费和 Token Plan 是两套 key：`sk-...` 打 `https://api.xiaomimimo.com/v1`，`tp-...` 打 `https://token-plan-cn.xiaomimimo.com/v1` |
| **我们打** | 按量付费和 Token Plan 那两个 base 都**没有**用量端点。控制台侧社区在用 `https://platform.xiaomimimo.com/api/v1/balance` 和 `/api/v1/tokenPlan/usage` |
| **拿到什么** | 余额 + Token Plan 的套餐用量。**没有按模型、按天的 token 明细**，填不满 `UsageCall` |
| **官方吗** | ❌ 官方 API Reference 里没有任何用量 / 消费端点。上面那两条控制台路径是社区逆出来的 |
| **四桶** | 价目表里 input / cacheRead / output 都有单价；**cacheWrite 限时免费**，所以那一栏填 0 是有依据的，不是缺数据 |
| **坑** | 控制台接口用 **cookie** 鉴权（`api-platform_serviceToken` + `userId`），约 24 小时过期、登出即失效——比 DeepSeek 那个登录态 token 还脆。和 DeepSeek 一样，拿它打控制台接口在 ToS 上站不住 |
| **结论** | **这条接不上，走手动导入。** 而且注意：社区有文档的用量端点只有 **Token Plan** 那个，而大纲 §2 第 88 行明确要求**排除 Token Plan 用量**、只算按量付费——**就算硬接上，拿到的也不是我们要的那类数据** |

价目表上的模型名（定价用得到，别写错）：`mimo-v2.6-pro`、`mimo-v2.6-flash`、
`mimo-v2.6-pro-ultraspeed`、`mimo-v2.5`、`mimo-v2.5-pro`（将下线）、
以及 `mimo-v2.5-asr` / `mimo-v2.5-tts-*` 那些语音模型。桩里用的是 `mimo-v2.6-pro`。

**一句话**：能自动拉的只有 OpenAI；DeepSeek 是「技术上能做、代价是替用户存一个登录态 token」；
MiMo 拿不到明细，而且它唯一有文档的用量端点是 Token Plan，正好是大纲要求排除的那个。

---

## 2. 粒度问题：这条不定下来，去重那条验收会打架

`UsageCall` 现在的注释是「**一次**模型调用的原始记录」，`id` 要能由「提供方 + 请求 id + 日期」
算出来。但 **OpenAI 给的是时间桶，没有请求 id**。两条路：

- **A（推荐）**：桶也落成 `UsageCall`，`id` 用 `provider + 桶起点 + 模型 + 项目`。
  **列不用改，表结构不动**——桶行和调用行的列完全一样。代价是「一行 = 一个桶」
  而不是一次调用，**桶来源的行做不了按会话分析**（桶里没有会话信息）。
  因为 `bucket_width` 最大 `1d`、最小 `1m`，**桶不会跨天**，日汇总照样滚得出来。
- **B**：桶只落到 `DailyUsage`，不下钻到 `UsageCall`。那 OpenAI 这条就永远看不到单次调用，
  和「`UsageCall` 是唯一事实来源」的说法直接冲突。

**已定：走 A**（2026-09-26）。表结构不动，桶行和调用行同列；桶不跨天，日汇总照样滚得出来。

代价认下来——桶行没有会话信息。为了让它**可判断**（而不是靠人记），
`id` 加一个前缀来区分粒度：

```
call:<provider>:<请求 id>:<yyyy-MM-dd>
bucket:<provider>:<桶起点毫秒>:<model>:<projectId>
```

**前缀是必需的，不是装饰**：`UsageCall` 里没有别的字段能告诉你这行是桶还是调用，
而「哪些行能做按会话分析」必须能算出来。有 `bucket:` 前缀的行，
Sessions 那类图表要过滤掉，否则会以为数据丢了。

顺带一笔：`Source.IMPORTED` 这个名字现在也覆盖拉取来的数据。**不改枚举**
（改它要动 `contract`、服务端字段、桩数据三处），报告里说明一句就行。

---

## 3. 用户凭据怎么存

`data/remote/package-info.java` 里那句「客户端**不持有任何模型 API key**」讲的是
**建议服务**用的那个 key，**那句不删、仍然成立**。但现在多了一类：

| key | 谁的 | 存哪 |
| --- | --- | --- |
| 建议服务调模型用的 key | 我们自己的 | **只在服务端**，客户端永远看不到（不变） |
| 用户查自己用量用的凭据 | 用户的 | 客户端。这就是新增的那类 |

两类要**分开写**，否则读起来像自相矛盾。

**已定（2026-09-26）：**

- **凭据只落设备本机，明文存 `SharedPreferences`，不加密。** 这是**有意的取舍**，
  不是漏做：课程项目把力气花在体验上——用户填完立刻能用，不该为了防一个本地攻击面
  多引一个库、多一层失败可能。评审要是问，就答"取舍已记录在案"。
- **但绝不上传服务端。** 这条**不是**隐私换体验，是两头都更差：
  上传对我们零收益（唯一好处是用户换手机），代价是我们替全班同学保管一堆 Admin key，
  服务端只要有一个口子就是一起漏；本机存反而更快、离线也能用。
  所以「随便存」的范围是**设备内**，**不出设备**。
- 界面上提一句：凭据只存在本机，可以随时在 provider 后台吊销。
- **不要**把凭据写进 Room、写进日志、或塞进 `UsageCall` 的任何字段。
- 见 [`CONTRACTS.md`](CONTRACTS.md) §9。

---

## 4. 实测记录

### 已验：DeepSeek 用量导出（2026-09-26，真实账号导的一份 7 天数据）

导出的是一份 `usage_data_2026-09-20_2026-09-26.zip`，解开是
`amount-*.csv` + `cost-*.csv` 两个文件。看下来的结论，**有三条和原来的假设不一样**：

1. **列名和值域**（原来第 3 条待验项）：
   - `amount-*.csv`：`user_id, utc_date, model, api_key_name, type, price, amount`
   - `type` 只有四个值，正好对上我们的四桶：

     | 导出里的 `type` | 我们的桶 |
     | --- | --- |
     | `input_cache_miss_tokens` | `input` |
     | `input_cache_hit_tokens` | `cacheRead` |
     | `output_tokens` | `output` |
     | `request_count` | `calls`（不是 token） |

     **没有 cacheWrite**，和上面 §1 的预判一致。
   - 行的粒度是 `(天, 模型, api_key_name, type)`，一周 74 行，唯一。
   - `request_count` 那几行 `price` 是**空字符串**，不是 0。
   - 两个 CSV 都带 **BOM**，按第一列名匹配会失败。

2. **金额是人民币，而且自带单价** —— 这条推翻了两处假设：
   - `cost-*.csv` 给的日金额是 **CNY**，不是美元。项目内部计价用微美元，所以导入时
     要做一次反向换算，见 `CONTRACTS.md` §9。
   - `amount-*.csv` 每行自带 `price`。用「`amount × price` 按天求和」和 `cost-*.csv`
     的日金额对账，**7 天全部精确吻合**。所以**根本不需要自己维护价目表**——
     峰谷价、折扣都已经在 `price` 里了。这也是 `UsageCall.costMicros` 存在的理由。

3. **峰谷价是真的，而且正好 2 倍**（cacheRead 0.02 / 0.04，input 1 / 2，
   output 4 / 8，单位是「元 / 1M tokens」）。同一天里两档会**同时出现**，而导出里
   **没有任何小时信息**，所以还原不出哪笔是哪个价——但也不需要还原，因为每行自带
   `price`。这条同时解释了 `CONTRACTS.md` §9 里「低谷价」那条为什么可以先不管。

4. **天是按 `+08:00` 切的**，和我们一致，不是 UTC。`CONTRACTS.md` §3 原来写错了，
   已经改掉。

5. **⚠️ 这份数据不能进仓库。** 里面有 `user_id`（UUID）、**打过码但仍可辨的
   `api_key`**、以及 `api_key_name` 里**真实同学的姓名**。要用它当测试样例，
   必须先把这三列换掉（`uid-1` / `key-a` / `同学A`），再进
   `app/src/test/resources/`。原始 zip 只留在本机。

### 还没验

1. **OpenAI 的 `input_cached_tokens` 到底有没有值**——要真 key 打一次。它要是恒为 0，
   `cacheRead` 这一桶就只能靠解析本地日志补。**这条到现在没动过**，
   `DATA_SOURCES.md` §1 那个「恒返回 0」是社区报告，不是我们测的，别当结论用。
2. **三家接口在这台机器 / 大陆网络下通不通**——要不要代理。通不了的话演示就得换网络。
3. **OpenAI 那套四桶口径能不能真的填满**——上面第 1 条是它的前置。
