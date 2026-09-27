"""价目表的种子数据。

## 这一份数据的来路（**报告里要照抄，别转述**）

**两批，来源不同，都要标清楚：**

### 一、DeepSeek 现役模型（2026-09-27 取，官方页面）

`https://api-docs.deepseek.com/quick_start/pricing/` 上"Models & Pricing"那张表，
单位 **USD / 1M tokens**（注意：这张表本来就是美元，不用换算）：

| 模型 | cache hit 谷/峰 | cache miss 谷/峰 | output 谷/峰 |
|---|---|---|---|
| `deepseek-flash` | 0.003 / 0.006 | 0.15 / 0.30 | 0.60 / 1.20 |
| `deepseek-v4-pro` | 0.022 / 0.044 | 0.66 / 1.32 | 1.98 / 3.96 |

页面同时写明：**谷价是峰价的一半**，峰时段是 UTC 周一至周五 01:00–04:00 与
06:00–10:00（中国法定假日除外）。`deepseek-flash` 是现役，旧名
`deepseek-v4-flash` / `deepseek-v4-flash-vision-exp` 仍被接受但模型已退役。

### 二、已退役模型（2026-09-26 实测，`DATA_SOURCES.md` §4）

`deepseek-chat` / `deepseek-reasoner` 这两个名字**现在已经不在 `/models` 里了**
（2026-09-27 实测 `/models` 只返回 `deepseek-flash` 和 `deepseek-v4-pro`），
但历史用量可能仍然引用它们，而且 Rust/旧客户端可能还在发这些名字。
它们的单价来自那份**真实账号导出**：低谷档 cacheRead 0.02 / input 1 / output 4
元 / 1M，`cacheWrite` 记 0（DeepSeek 不计缓存写入）。

## 三个必须说清的取舍

1. **新模型记峰价，不记谷价。** 峰谷差 2 倍，而**单次响应里没有任何小时信息**
   （`DATA_SOURCES.md` §4 第 3 条实测过），所以还原不出某一笔是哪个价。
   在「必须挑一个」的前提下挑**高的那档**：偏高的估算比偏低的安全——
   报出来的成本不会低于实际账单，而「以为还能花很多、其实已经超了」是有代价的。
   代价写在这里：**实际的非峰时段用量会被高估最多 2 倍。**
2. **两个模型都记了，不是只记默认的那个。** 用户可能自己指定 `deepseek-v4-pro`
   （接口有 `model` 参数），只记一个的话另一种用量会算不出价。
3. **`cacheWrite` 记 0**，依据同上（DeepSeek 不计缓存写入）。
"""
# (provider, model, effectiveFrom, rateVersion, {四桶}, sourceUrl)
#
# 值是**微美元 / 1M**，和 App 侧 `data/Money` 同一套单位。
# 官方表本身就是美元，所以这批**不需要汇率换算**——直接乘 1e6 就是微美元。
SEED = (
    # ---- 现役：2026-09-27 取自官方定价页 ----
    ("DEEPSEEK", "deepseek-flash", "2026-09-01", "2026-09-deepseek-peak",
     {"input": 300_000, "cache_read": 6_000, "cache_write": 0, "output": 1_200_000},
     "https://api-docs.deepseek.com/quick_start/pricing/"),
    ("DEEPSEEK", "deepseek-v4-pro", "2026-09-01", "2026-09-deepseek-peak",
     {"input": 1_320_000, "cache_read": 44_000, "cache_write": 0, "output": 3_960_000},
     "https://api-docs.deepseek.com/quick_start/pricing/"),
    # ---- 已退役：名字仍被接受，历史用量还在引用 ----
    # 1 元 / 1M → 140845 微美元 / 1M（走汇率），和 App 侧 `data/Money` 同值。
    ("DEEPSEEK", "deepseek-chat", "2026-09-01", "2026-09-deepseek-offpeak",
     {"input": 140_845, "cache_read": 2_816, "cache_write": 0, "output": 563_380},
     "https://api-docs.deepseek.com/quick_start/pricing/"),
    ("DEEPSEEK", "deepseek-reasoner", "2026-09-01", "2026-09-deepseek-offpeak",
     {"input": 140_845, "cache_read": 2_816, "cache_write": 0, "output": 563_380},
     "https://api-docs.deepseek.com/quick_start/pricing/"),
)


def install(store, pricing, overwrite=False):
    """把种子录进去。返回 `(新增, 跳过)`。

    **默认不覆盖已存在的记录**（主键是 `(provider, 模型, 生效日)`）。
    理由：种子是「我们知道的价」的历史底稿，而生产库里可能有人后来录了更准的一版
    （比如真去官方文档核对过、或者发现了另一档峰谷价）。启动时无条件覆盖的话，
    每次重启都会把那些修正**悄悄冲掉**，而且没有任何痕迹。

    要强制覆盖就显式传 `overwrite=True`（迁移或重新录价时用）。

    **幂等**：重复执行不会堆出多版费率，也不会改变已有内容。
    """
    added = skipped = 0
    for provider, model, effective_from, version, rates, source in SEED:
        if not overwrite and pricing.rate_for(provider, model, effective_from) is not None:
            skipped += 1
            continue
        pricing.upsert(provider, model, effective_from, rates, version, source)
        added += 1
    return added, skipped
