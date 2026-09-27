"""按维度滚一段区间的用量——`UsageSummary` 的形状。

## 为什么单独一个模块

这段逻辑是 agent 的 `getUsageSummary` 的落点，而 agent 那一侧有一整套口径要求
（`CONTRACTS.md` §6）：**返回数字的工具必须带 `Coverage`**，缺失的天要能被
强制写进回答里。把它从 HTTP 层分出来，是为了让那些口径有地方写注释、
也让它们能被直接测（不必每次都经过一个路由）。

## 三条口径

1. **`uid` 由调用方从凭据解出来传进来，不是参数。** §6 那条
   「`uid` 不作为任何工具的参数：由服务端从请求携带的会话 token 里解出来，
   不信客户端传的」——所以这个函数收的是已经解好的 uid。
2. **`coverage.daysMissing` 只装「一条记录都没有」的天**，「用量为 0」不进
   （`Coverage` 的类注释）。这是 agent 承认自己不知道的机制保证。
3. **算不出价的行 `costMicros` 是 `None`**，不是 0。汇总时**不把 None 当 0 加**——
   那样算出来的是一个偏低的总额，而且看不出偏低。响应里用
   `pricingComplete: false` 和 `unpricedModels` 说明这件事。
"""
from datetime import date, timedelta

from fastapi import HTTPException

from .pricing import cost_micros
from .seasons import day_millis_range

GROUP_BY = ("DAY", "MODEL", "PROVIDER")


def summarize(relay, pricing, uid, from_day, to_day, group_by):
    """滚出 `UsageSummary`。`from_day` / `to_day` 是 `yyyy-MM-dd`，**两端都含**。"""
    group_by = (group_by or "MODEL").upper()
    if group_by not in GROUP_BY:
        raise HTTPException(400, f"groupBy must be one of {', '.join(GROUP_BY)}")
    start = _parse_day(from_day, "from")
    end = _parse_day(to_day, "to")
    if end < start:
        # 反向区间返回空而不是报错？**不。** 静默的空区间会让 agent 说
        # 「这段时间没有用量」，而实际上它把参数写反了。宁可 400。
        raise HTTPException(400, "from must not be later than to")
    if (end - start).days > 366:
        raise HTTPException(400, "Range must not exceed 366 days")

    start_millis, _ = day_millis_range(start.isoformat())
    _, end_millis = day_millis_range(end.isoformat())
    rows = relay.daily_for(uid, since=start_millis, until=end_millis, limit=5000)

    rates = pricing.rates_for_days([(row["provider"], row["model"], row["day"]) for row in rows])

    groups = {}
    totals = {"input": 0, "cacheRead": 0, "cacheWrite": 0, "output": 0}
    total_cost = 0
    unpriced_models = set()
    rate_versions = set()
    complete_pricing = True
    days_with_data = set()
    for row in rows:
        days_with_data.add(row["day"])
        key = _group_key(row, group_by)
        bucket = groups.setdefault(key, {
            "key": key,
            "provider": row["provider"],
            "model": row["model"] if group_by != "PROVIDER" else None,
            "tokens": {"input": 0, "cacheRead": 0, "cacheWrite": 0, "output": 0},
            "calls": 0, "costMicros": 0, "rateVersion": None})
        for name in totals:
            bucket["tokens"][name] += row[name]
            totals[name] += row[name]
        bucket["calls"] += row["calls"]

        rate = rates.get((row["provider"], row["model"], row["day"]))
        amount = cost_micros(row, rate)
        if amount is None:
            # **不加进总额**。加 0 会让总额偏低而看不出来。
            complete_pricing = False
            unpriced_models.add(row["model"])
        else:
            bucket["costMicros"] += amount
            total_cost += amount
            if bucket["rateVersion"] is None:
                bucket["rateVersion"] = rate["rate_version"]
            rate_versions.add(rate["rate_version"])

    # 区间的每一天都检查一遍：**一条记录都没有的天**算缺，「用量为 0」不算。
    missing = []
    cursor = start
    while cursor <= end:
        if cursor.isoformat() not in days_with_data:
            missing.append(cursor.isoformat())
        cursor += timedelta(days=1)

    ordered = sorted(groups.values(), key=lambda item: (-item["costMicros"], -item["calls"], item["key"]))
    result = {
        "uid": uid,
        "totals": totals,
        "rows": ordered,
        "groupBy": group_by,
        "from": start.isoformat(),
        "to": end.isoformat(),
        "costMicros": total_cost,
        # 有一条算不出价就是 false。agent 必须据此说「这个数不完整」，
        # 而不是把 costMicros 当成确切答案讲出来。
        "pricingComplete": complete_pricing,
        "unpricedModels": sorted(unpriced_models),
        "rateVersions": sorted(rate_versions),
        "coverage": {"from": start.isoformat(), "to": end.isoformat(),
                     "daysWithData": len(days_with_data), "daysMissing": missing},
    }
    return result


def _group_key(row, group_by):
    if group_by == "DAY":
        return row["day"]
    if group_by == "PROVIDER":
        # provider 可能是 null（认不出上游域名）。用字符串 "unknown" 当**分组键**，
        # 但 `provider` 字段仍然给 null——契约里 `Provider` 是枚举，
        # 值域只有三个，塞枚举外的值客户端反序列化会直接抛异常。
        return row["provider"] or "unknown"
    return f"{row['provider'] or 'unknown'}/{row['model'] or 'unknown'}"


def _parse_day(value, field):
    if not isinstance(value, str) or len(value) != 10:
        raise HTTPException(400, f"{field} must be yyyy-MM-dd")
    try:
        return date.fromisoformat(value)
    except ValueError:
        raise HTTPException(400, f"{field} must be yyyy-MM-dd") from None


COMPARE_METRICS = ("COST", "TOKENS", "COST_PER_1M")


def compare(relay, pricing, uid, from_day, to_day, metric):
    """按 (provider, 模型) 比一段区间——`CompareResult` 的形状。

    它是 agent 的 `compareAgentCosts` 的落点。**只比成本、token 和单价**：
    `CompareResult` 的类注释写着「不比哪个模型更聪明」，那条不只是措辞——
    这个接口里根本没有质量数据，所以那条边界是结构上成立的，不靠自觉。

    **排序按请求的指标来**，但每行三种数都返回（成本和 token 本来就有，
    单价是除出来的）。这样 agent 拿一个响应就能回答「哪个贵」和「哪个单价高」
    两个问题，不用发两次请求。
    """
    metric = (metric or "COST").upper()
    if metric not in COMPARE_METRICS:
        raise HTTPException(400, f"metric must be one of {', '.join(COMPARE_METRICS)}")

    # 复用汇总的同一套口径（区间、`Coverage`、按天查费率、算不出价不加）。
    # **不另写一遍聚合**：两处各算一次的话，「同一个问题两个答案」是迟早的事。
    grouped = summarize(relay, pricing, uid, from_day, to_day, "MODEL")
    rows = []
    for row in grouped["rows"]:
        tokens = row["tokens"]
        total = sum(tokens.values())
        rows.append({
            "provider": row["provider"],
            "model": row["model"],
            "tokens": tokens,
            "calls": row["calls"],
            "costMicros": row["costMicros"],
            # **算不出价的行必须能被认出来，否则它看起来像「最便宜」。**
            # `costMicros` 是 0 而实际「不知道」——排到最后一名、读起来是
            # 「这个模型几乎不花钱」。所以逐行给出可得性，让排序和结论都能
            # 把这一行排除在外（`CompareResult.Row` 没有这个字段，
            # 多出来的键客户端会忽略，但 agent 读得到）。
            "priced": row["model"] not in grouped["unpricedModels"],
            # 单价：总成本 / 总 token。**token 为 0 时是 0**，不是除零——
            # 一行挂零 token 却有钱的情况不该出现，但出现了也不该让整个响应崩掉。
            "costPer1MTokensMicros": 0 if total == 0 else row["costMicros"] * 1_000_000 // total,
        })

    # **没价的行永远排最后**，不参与指标排序——它的 0 不是「便宜」，
    # 是「不知道」。让它混进排名就等于把「不知道」当成一个结论讲出来。
    def rank(item):
        tokens_total = sum(item["tokens"].values())
        if not item["priced"]:
            return (1, 0, item["model"] or "")
        if metric == "TOKENS":
            return (0, -tokens_total, item["model"] or "")
        if metric == "COST_PER_1M":
            return (0, -item["costPer1MTokensMicros"], item["model"] or "")
        return (0, -item["costMicros"], item["model"] or "")

    rows.sort(key=rank)

    return {
        "uid": uid,
        "rows": rows,
        "from": grouped["from"],
        "to": grouped["to"],
        "metric": metric,
        "coverage": grouped["coverage"],
        "rateVersions": grouped["rateVersions"],
        # 和汇总一样带上：算不出价的行没有加进 costMicros，
        # agent 必须据此说「这个比较不完整」，不能把排名当成定论。
        "pricingComplete": grouped["pricingComplete"],
        "unpricedModels": grouped["unpricedModels"],
    }
