"""服务端预算：只存上限，花销从来现算。

## 为什么只存上限

`TASKS.md` §1.1 对 `BudgetRepository` 的要求：「只存上限，**花销从来现算**，
不存『已花』」。存「已花」的话，它和用量就是两份数据，任何一次导入、纠正、
延迟落库都会让两者不一致，而对账时不知道该信哪个。

## 现在的一个诚实缺口：花销算不出来

`spentMicros` 需要**价目表**（`pricing/rates`），而那张表还没建。所以：

- `spentMicros` 返回 **0**，但响应里同时带 **`pricingAvailable: false`**；
- 客户端**不能**因为看到 0 就说「你花了 0 元」——那正是 `CONTRACTS.md` §4
  说的「数字看着没错、结论是错的」。
- 等 `pricing/rates` 建好之后，这个模块改成按天取当天生效的费率现算，
  **表的形状不用动**。

## `Coverage` 的口径

按 `Coverage` 的类注释：**只有「一条记录都没有」的天才进 `daysMissing`**，
「用量为 0」不进。所以这里按「本月有记录的天」反推缺失的天。
本月只算到**今天**——未来的天还没发生，算进去会让覆盖度永远不满。

没设过预算时 `configured` 为 false，而**不是** `capMicros == 0`：
「没设预算」和「设了 0 元预算」是两件事（见 `BudgetStatus.configured` 的注释）。
"""
from datetime import date, datetime, timedelta, timezone

from .pricing import cost_micros
from .seasons import day_millis_range

SCHEMA = """
CREATE TABLE IF NOT EXISTS budgets (
 user_id TEXT NOT NULL, month TEXT NOT NULL, cap_micros INTEGER NOT NULL,
 warn_ratio REAL NOT NULL DEFAULT 0.8, updated INTEGER NOT NULL,
 PRIMARY KEY(user_id, month)
);
"""

DEFAULT_WARN_RATIO = 0.8


def month_bounds(month):
    """`yyyy-MM` → `(第一天, 下个月第一天)`，都是 `yyyy-MM-dd`。"""
    year, number = int(month[:4]), int(month[5:7])
    following = f"{year + 1}-01-01" if number == 12 else f"{year}-{number + 1:02d}-01"
    return f"{month}-01", following


class Budgets:
    """预算的读写。挂在内核的 `Store` 上，和中转、赛季共用一个数据库。"""

    def __init__(self, store, relay, pricing):
        self.store = store
        self.relay = relay
        self.pricing = pricing
        with store.connect() as db:
            db.executescript(SCHEMA)

    def get(self, digest, month):
        with self.store.connect() as db:
            row = db.execute("SELECT * FROM budgets WHERE user_id=? AND month=?",
                             (digest, month)).fetchone()
        return None if row is None else {
            "uid": digest, "month": row["month"], "capMicros": row["cap_micros"],
            "warnAtRatio": row["warn_ratio"], "updatedAtEpochMillis": row["updated"]}

    def save(self, digest, month, cap_micros, warn_ratio=None):
        ratio = DEFAULT_WARN_RATIO if warn_ratio is None else float(warn_ratio)
        with self.store.connect(write=True) as db:
            db.execute("""INSERT INTO budgets(user_id,month,cap_micros,warn_ratio,updated)
                VALUES(?,?,?,?,?) ON CONFLICT(user_id,month) DO UPDATE SET
                cap_micros=excluded.cap_micros, warn_ratio=excluded.warn_ratio,
                updated=excluded.updated""",
                (digest, month, int(cap_micros), ratio,
                 int(datetime.now(timezone.utc).timestamp() * 1000)))
        return self.get(digest, month)

    def status(self, digest, month, today):
        """预算 + 本月覆盖度 + **现算的花销**。

        `today` 由调用方给（服务端算好了传进来），这样「本月算到哪天」
        只有一个来源，不在这里再调一次时钟。

        `spentMicros` **每次现算、从不存**（`TASKS.md` §1.1：只存上限）。
        查不到价的天**不进这个数**，而是列在 `unpricedDays` 里；只要有一天的价
        算不出来，`pricingAvailable` 就是 false——客户端不能把 `spentMicros`
        当成完整花销。**不把算不出来的天当 0 加进去**，那正是
        `CONTRACTS.md` §4 那条底线。
        """
        budget = self.get(digest, month)
        first, following = month_bounds(month)
        start_millis, _ = day_millis_range(first)
        above_millis, _ = day_millis_range(following)
        rows = self.relay.daily_for(digest, since=start_millis, until=above_millis, limit=5000)

        # 本月该算到哪天：本月还没过完就只算到今天；已经过完（或是个未来的月）
        # 就按整月。把未来的天算进去会让覆盖度永远填不满，那是假的「缺数据」。
        month_end = date.fromisoformat(following) - timedelta(days=1)
        last_counted = min(today, month_end)

        in_window = {row["day"] for row in rows if first <= row["day"] <= last_counted.isoformat()}
        missing = []
        cursor = date.fromisoformat(first)
        while cursor <= last_counted:
            if cursor.isoformat() not in in_window:
                missing.append(cursor.isoformat())
            cursor += timedelta(days=1)

        # 花销：每行用它**自己那一天**生效的费率。
        rates = self.pricing.rates_for_days([(row["provider"], row["model"], row["day"])
                                             for row in rows])
        spent = 0
        priced_days = set()
        unpriced_days = set()
        for row in rows:
            if not (first <= row["day"] <= last_counted.isoformat()):
                continue
            rate = rates.get((row["provider"], row["model"], row["day"]))
            amount = cost_micros(row, rate)
            if amount is None:
                unpriced_days.add(row["day"])
            else:
                spent += amount
                priced_days.add(row["day"])

        return {
            "uid": digest,
            "month": month,
            "configured": budget is not None,
            "capMicros": None if budget is None else budget["capMicros"],
            "warnAtRatio": None if budget is None else budget["warnAtRatio"],
            "spentMicros": spent,
            # **`pricingAvailable` 才决定 `spentMicros` 能不能当完整花销用。**
            # 有记录的天里只要有一天算不出价，这里就是 false。
            "pricingAvailable": not unpriced_days,
            "unpricedDays": sorted(unpriced_days),
            "pricedDays": len(priced_days),
            "coverage": {"from": first, "to": last_counted.isoformat(),
                         "daysWithData": len(in_window), "daysMissing": missing},
        }
