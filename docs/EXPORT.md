# 导出数据（对话与用量）

> 2026-10-06 落地。对应大纲 §4「export records」、设计稿第 27/28 张
> （`Me → Data & memory → Export data`）。**代码在 `app/src/main/java/com/mobilegroup20/modelpilot/data/export/`。**
>
> **2026-10-06 晚：另一半（导入）也做完了**，见 §8。导出与导入是一对——
> 只做导出只是"能带走"，做上导入才叫"换得回来"。

## 1. 这件事为什么非做不可

对话与账本**只在这台手机上**（`docs/CHAT_ENGINE.md` §1：key 在手机、调用从手机发出去、
服务端一张表都不加）。这带来一个必须自己认下来的代价：**换手机或重装就全没了。**
导出是这条代价唯一的补丁——所以它不是"附加功能"，是大纲 §4 里跟对话、上下文并列的一条；
而导入是这条补丁的另一半（能让文件**回到**库里，§8）。

## 2. 用户看到的三步

```
Me → Data & memory（第 27 张）
      └─ Export data（第 28 张）
           ① 选范围：对话与记忆 / 用量记录（可同时选，至少选一个）
           ② 选用量日期范围：全部 / 最近 7 天 / 最近 30 天 / 本月
           ③ 选格式：JSON（一份文件） / CSV（一个 zip，一张表一个 csv）
           → Export → 生成（对话框里报「3 conversations · 340 messages · 88 calls」）
           → 系统的「保存到…」→ 落盘 → 提示「Saved modelpilot-export-….zip · …」
```

**对话没有日期范围**，只有用量有。理由：一条对话是连续的上下文，砍掉中间几天交出去的
是一份读不通的记录（也和使用者的记忆对不上）。要少导就少选范围，而不是切对话。

## 3. 产物长什么样

文件名 `modelpilot-export-<yyyyMMdd-HHmm>.<json|zip>`，时间是**北京时间**（全项目唯一的
时区定义，见 `util/TimeUtils.ZONE`）。

### JSON（一份文件）

```json
{ "meta": {…}, "projects": […], "conversations": […], "usage": […] }
```

只写选到的范围：不选用量就没有 `usage` 这个键。

### CSV（一个 zip）

一张表一个文件，加一份 `meta.json`：

| 文件 | 一行是什么 | 列（有删节，见代码为准） |
| --- | --- | --- |
| `projects.csv` | 一个项目 | id, name, instructions, color_index, created_at… |
| `conversations.csv` | **一条消息**，每行都带它所属对话的标题与项目名 | chat_id, chat_title, project_id, project_name, message_id, role, created_at, provider_id, model_id, route, tokens_in/out, attachment_count, attachment_files, text |
| `memory.csv` | 一条压缩摘要 | chat_id, chat_title, memory_id, from/to_message_id, made_by_*, tokens_in/out, edited_by_user, summary |
| `usage.csv` | 一次模型调用 | id, day, started_at, provider, model, source, route, kind, task_id, chat_id, tool_calls, reason, policy, input/cache_read/cache_write/output/total_tokens, cost_micros_usd, cost_amount_usd, cost_amount_cny, original_micros, original_currency, rate_version |
| `meta.json` | 这次导出的自述 | 见下一节 |

消息合并在 `conversations.csv` 一张表里（而不是"对话一张、消息一张"）：CSV 的使用方式
是"用表格软件打开就能看懂"，两张表要用户自己 join 一次，那正是他要 CSV 时不想干的事。

CSV 的写法：**UTF-8 BOM**（没有它 Excel 打开中文是乱码）、**CRLF 行尾**（RFC 4180）、
含逗号/引号/换行的字段加引号并把 `"` 双写。

## 4. `meta`：文件的自述

导出文件会被转发给别人（对账、求助、交作业），所以**"我们故意省掉了什么"跟着文件走**，
而不是只写在文档里。`meta` 里固定有：

- `app` / `appVersion` / `generatedAt`（ISO，带 `+08:00`）/ `timeZone` / `account`；
- `scopes`（`CONVERSATIONS` / `USAGE`）、`format`、`counts`（各有多少条）；
- `usageRange`：`{"from": …, "to": …}`，**不限就是 `null`**（不写 `"all"` 这种字符串，
  拿到文件的人要能用程序判断）；
- `contains`：这次导出里出现过的 provider / model / source（设计稿要求
  "导出包含模型来源与数据来源"）；
- `display`：当前显示币种 + 用户填的汇率（**带来源与填写时间**——设计稿对跨币种的原话是
  「跨币种要记录原币、汇率来源与日期」）；
- `notes`：一段人话，逐条说明下列取舍。

## 5. 五条规矩（都有单测钉着，`DataExporterTest` / `CsvTest`）

1. **不知道就留空。** 金额没算出来、导入记录没有路由理由、消息的 token 还没回填——
   对应那一格是空的，不是 `0`、不是 `"unknown"`。这跟界面上的「未知不显示成 0」是同一条规矩。
   （消息上的 `tokens_in/out` 在库里 `0` 的定义就是"还没拿到上游用量"，所以 JSON 里
   直接不写这个字段、CSV 里是空格。）
2. **不含密钥，也不含请求地址。** key 存在 Keystore 里、本来就不在这个库；用户自己填的
   请求地址**故意不导出**——地址常常长成 `https://relay.example/v1?key=sk-…`，
   带一行这样的 URL 出去等于把 key 抄了一份。
3. **不带附件的本机路径。** 附件导出类型、文件名、大小和抽取出的文本，
   `content://` 那个 uri 丢掉：换台设备没用，还会暴露文件来自哪个应用。
4. **金额用账本原值。** 账本记的是**微单位整数**（1e-6），导出就是那个整数；
   `cost_amount_usd` / `cost_amount_cny` 只是给人看的换算列，而**人民币那一列只在
   用户自己填过汇率时才出现**——没有汇率就不换算（`data/Money` 的类注释）。
   导入记录若来源没自带金额，行上就没有金额：界面的日汇总会按当日费率估算，**导出不估算**。
5. **范围是用户选的，就只读那一半。** 只选用量时不去读聊天表。

另外：以 `=` `+` `-` `@` 开头的单元格前面补一个单引号（CSV injection 防护，
补引号会改动内容，所以这一条也写在 `notes` 里）。CSV 的 `total_tokens` 用
`TokenBundle.total()` 的口径（四类相加），不在这里另写一遍加法。

## 6. 代码在哪、边界在哪

| 类 | 干什么 |
| --- | --- |
| `data/export/ExportScope` `ExportFormat` `ExportRequest` | 用户选了什么（不可变） |
| `data/export/ExportSource` | 要读的那几样数据的接口（**为了能不用 Room 就测**） |
| `data/export/RoomExportSource` | 它落在 Room 上的实现，只有查询、没有判断 |
| `data/export/DataExporter` | 全部规矩都在这里（纯计算，不碰 Android） |
| `data/export/Csv` `ExportTime` | CSV 转义与时间写法 |
| `data/export/ExportResult` `ExportArtifact` | 产物与条数 |
| `ui/settings/ExportSheet` | 问范围 → 生成 → 存到用户选的位置（SAF，不申请存储权限） |
| `Me → Data & memory` 那颗按钮（`MainActivity.showTab`） | 入口；和 API keys 一样**只看在不在「我的」页，不看登录状态** |

界面那一层**先写缓存再让用户选位置**（`cacheDir/exports/`，每次生成前清空）：
系统的"保存到…"会把活动切到后台，转屏或被回收都会让对话框重建，
字节只存在内存里的话，用户选完位置回来就什么都写不出来（表现是"文件是空的"）。
缓存目录每次生成前清空——导出文件里有聊天记录，不该在手机里越堆越多。

## 7. 还没做的

- **导出附件本体**：现在导的是附件的元数据与抽取文本，不是文件本身。
- **按对话导出**（选某几条对话）与"导出后分享给别的应用"：现在是全量（按范围）+ 存到文件。

## 8. 导入（2026-10-06 晚补上）

`Me → Data & memory → Import data`。**代码在 `app/src/main/java/com/mobilegroup20/modelpilot/data/importer/`**，
链路与全部规矩见那个包的 `package-info.java`。

```
Me → Data & memory（第 27 张）
      └─ Import data
           ① Choose file…（系统选择器，JSON 或 CSV 的 zip 都收；**不申请存储权限**，
              也**不看登录状态**——完全本机）
           ② 读文件 → 报「2 conversations · 26 messages · 13 calls」+ 本机已有几条
              + 这个文件自己写的那段取舍（`meta.notes` 原样转述）
           ③ 本机已有的怎么办：默认「只加新的」，要覆盖得自己选
           → Import → 报「added 1 conversations · 26 messages restored」
```

**为什么读错文件不会被静默吞掉**：不是我们的文件（`meta` 那一段不在）在解析之前就拒了，
并且分开说"这不是 ModelPilot 导出的文件"和"是我们的文件但里面是空的"——这两句话对用户
意味着完全不同的下一步（重选文件 / 重新导一次）。

**合并的四条规矩**（都有单测钉着）：

1. **按 id 认亲，不按名字。** 同一个文件导两次，第二次一条都不新增（用量那边主键由记录内容算出）。
2. **本机优先是默认。** 手机上的对话是活的，文件里的是存档；要覆盖得用户自己选
   （`MergePolicy.REPLACE`，换新手机时用）。**导入从不删东西**——本机独有的对话在任何策略下都不动，
   同 id 的消息也保留本机的版本（冲突忽略）。
3. **不补空、不编造。** 文件里没有的字段就是"不知道"，绝不写成 0 或 `"unknown"`。
4. **坏行绝不静默。** 读不动的行计进结果并在界面上报出来；只报"写进去多少"会让一份残缺的文件
   看起来和完整的一样。

**CSV 这条路是退化的，我们如实标出来**：CSV 为了能用 Excel 打开而把结构压扁了——附件只剩
「个数 + 文件名」（类型和正文没了）、工具调用没有列。所以导入时 `ImportBundle.Coverage` 记的是
`TABULAR`，界面上那段 `meta.notes` 原样转述了这件事。**换手机请用 JSON**，这句话在导出那边的
`meta.notes` 里就写着，两边口径一致。

**用量导进来之后账本是活的**：它走的是 `data/local/CallImporter`（和 `RoomUsageRepository`
同一条路），所以主键去重与"受影响那几天的日汇总重滚"都会发生——Insights 立刻就能看到，
不需要谁再点一次什么。
