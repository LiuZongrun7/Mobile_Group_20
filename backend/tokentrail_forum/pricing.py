"""服务端价目表：按天查当天生效的费率，并把成本算出来。

## 为什么成本必须在服务端算

`CONTRACTS.md` §4 有一条底线：**「算不出价」和「花了 0 元」必须分得开**。
两边各算一次的话，客户端会说「价格未知」而服务端说「0」——同一笔钱两个答案，
而且没人知道该信哪个。所以：

- 服务端持有价目表，`costMicros` 只有服务端填；
- 查不到费率就留 **`null`**，**不填 0**；
- 客户端拿到 null 显示「价格未知」。

## 费率按「天」查，不按「现在」查

`PricingRate` 的 `effectiveFrom` 是日期。查某天的成本要用**那天生效的那一版**，
不能用今天的价回头算——改了价目表不能让历史账单变。所以
`rate_for(db, provider, model, day)` 取「不晚于该天的最近一版」。

## 币种

来源价目是**人民币**（DeepSeek 官方文档和导出都是元/1M），而项目内部计价
一律用微美元（`CONTRACTS.md` §9）。所以录价的时候**换算一次**存进来，
换算率写在这一条记录的 `source`/`rate_version` 里，查得出来是按哪一版换的。

换算必须和 App 侧 `data/Money` 是同一个常数，两处一起改。
"""
from datetime import date

from fastapi import HTTPException

# 和 `data/Money.CNY_PER_USD_MICROS` 必须一致（1e6 微美元 = 1 美元 = 7.1 元）。
CNY_PER_USD_MICROS = 7_100_000

SCHEMA = """
CREATE TABLE IF NOT EXISTS pricing_rates (
 provider TEXT NOT NULL, model TEXT NOT NULL, effective_from TEXT NOT NULL,
 rate_version TEXT NOT NULL, input_micros INTEGER NOT NULL,
 cache_read_micros INTEGER NOT NULL, cache_write_micros INTEGER NOT NULL,
 output_micros INTEGER NOT NULL, source_url TEXT,
 PRIMARY KEY(provider, model, effective_from)
);
"""

# 四个 token 桶 → 它们在表里的列名。
#
# **这个映射只能有一处。** 踩过：`cost_micros` 一开始拿列名当键名去取
# `rate["input"]`，而表里的列叫 `input_micros`——于是每一项都取到 None、
# 整笔成本返回 None，而 `rateVersion` 却是填上的（费率确实查到了）。
# 症状是「有费率但算不出价」，查起来要绕一圈。
#
# 键名用**契约里的驼峰**（`TokenBundle` / `DailyUsage` 的字段名），
# 列名用下划线，两套都在这里对齐一次。
BUCKET_COLUMNS = (
    ("input", "input_micros"),
    ("cacheRead", "cache_read_micros"),
    ("cacheWrite", "cache_write_micros"),
    ("output", "output_micros"),
)

# 上线新费率时要传哪几个桶（给接口的文档和校验用）。
BUCKETS = tuple(column for _, column in BUCKET_COLUMNS)


def cny_per_1m_to_usd_micros(cny_per_million):
    """人民币元 / 1M token → 微美元 / 1M token。

    **两步都要做对，少乘一个 1e6 是这里最容易犯的错**：

    1. 元 → **微元**（1 元 = 1_000_000 微元）
    2. 微元 → 微美元（除以 `CNY_PER_USD_MICROS`）

    例：1 元/1M → `1 * 1_000_000 * 1_000_000 // 7_100_000` = **140** 微美元/1M
    （$0.00014/1M ≈ ¥0.001/1K，和 DeepSeek 的 1 元/1M 对得上）。

    写成「先乘 1e6 再除」而不是「除完再乘」，是为了不丢精度：
    0.02 元/1M 先除会变成 0。
    """
    return int(round(cny_per_million * 1_000_000)) * 1_000_000 // CNY_PER_USD_MICROS


def cost_micros(tokens, rate):
    """按一版费率算一笔成本。**任何一项费率缺失就整体返回 None。**

    为什么不逐项跳过缺失的那一项：那样算出来的是一个「部分成本」，
    它和真实成本的差额没有任何地方体现，看起来却像个正常数字。
    宁可整笔说「算不出来」（`CONTRACTS.md` §4 那条底线）。

    `rate` 是 `rate_for` 返回的字典；`tokens` 要有 input / cacheRead /
    cacheWrite / output 四个键。
    """
    if rate is None:
        return None
    total = 0
    for bucket, column in BUCKET_COLUMNS:
        per_million = rate.get(column)
        if per_million is None:
            return None
        total += int(tokens.get(bucket) or 0) * int(per_million)
    return total // 1_000_000


class Pricing:
    """价目表。挂在内核的 `Store` 上。"""

    def __init__(self, store):
        self.store = store
        with store.connect() as db:
            db.executescript(SCHEMA)

    def upsert(self, provider, model, effective_from, rates, rate_version, source_url=None):
        """录一版费率。同一个 (provider, model, 生效日) 覆盖。

        `rates` 的键是 `BUCKETS` 里的四个名字，值是**微美元 / 1M**。
        """
        validate_effective_from(effective_from)
        with self.store.connect(write=True) as db:
            db.execute("""INSERT INTO pricing_rates
                (provider,model,effective_from,rate_version,input_micros,cache_read_micros,
                 cache_write_micros,output_micros,source_url) VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(provider,model,effective_from) DO UPDATE SET
                rate_version=excluded.rate_version, input_micros=excluded.input_micros,
                cache_read_micros=excluded.cache_read_micros,
                cache_write_micros=excluded.cache_write_micros,
                output_micros=excluded.output_micros, source_url=excluded.source_url""",
                (provider, model, effective_from, rate_version,
                 rates["input"], rates["cache_read"], rates["cache_write"],
                 rates["output"], source_url))
        return self.rate_for(provider, model, effective_from)

    def rate_for(self, provider, model, day):
        """取「不晚于 `day` 的最近一版」。查不到返回 None——**不是 0**。"""
        if provider is None or model is None or day is None:
            return None
        with self.store.connect() as db:
            row = db.execute("""SELECT * FROM pricing_rates
                WHERE provider=? AND model=? AND effective_from<=?
                ORDER BY effective_from DESC LIMIT 1""",
                (provider, model, day)).fetchone()
        return None if row is None else dict(row)

    def rates_for_days(self, triples):
        """批量查一组 `(provider, 模型, 天)` 的费率。

        键里**必须带天**：改了价目表之后，同一个模型在不同的天可能是两个价，
        用一次的价算一整段会让历史账单跟着变。带天之后每行各查各的，
        同时又只查一次库（一天十几个 (provider, 模型) 也不至于十几次往返）。
        """
        return {triple: self.rate_for(triple[0], triple[1], triple[2]) for triple in set(triples)}

    def listing(self, provider=None, model=None, limit=500):
        query = "SELECT * FROM pricing_rates WHERE 1=1"
        parameters = []
        if provider:
            query += " AND provider=?"
            parameters.append(provider)
        if model:
            query += " AND model=?"
            parameters.append(model)
        query += " ORDER BY provider, model, effective_from DESC LIMIT ?"
        parameters.append(max(1, min(limit, 2000)))
        with self.store.connect() as db:
            rows = db.execute(query, parameters).fetchall()
        return [dict(row) for row in rows]


def validate_effective_from(value):
    """`yyyy-MM-dd` 校验。**必须转成 400，不能让它变成 500。**

    `date.fromisoformat` 对 `2026-02-30` 这种不存在的日期也会抛，所以这里
    统一接住。注意还要显式要求 10 个字符的格式：`fromisoformat` 在 3.10 上
    连 `20260927` 都收，而契约里的日期一律是带连字符的。
    """
    if not isinstance(value, str) or len(value) != 10:
        raise HTTPException(400, "effectiveFrom must be yyyy-MM-dd")
    try:
        date.fromisoformat(value)
    except ValueError:
        raise HTTPException(400, "effectiveFrom must be yyyy-MM-dd") from None
