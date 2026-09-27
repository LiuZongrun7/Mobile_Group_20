"""服务端结算：把已经过完的、还没结算的天换成资源。

## 为什么这件事必须在服务端

`CONTRACTS.md` §7 第 5 条：「`lastSettledDay` 和余额**以服务端为准**，否则重装、换设备、
多开都能重复领」。客户端算的话，重装一次就能把同一批 token 再领一遍资源——
这不是理论风险，是必然发生的事。

## 五条规则（`CONTRACTS.md` §7，一条都不能绕）

1. **今天永远不结算**——当天还没结束，以后的用量还会落进来。
2. **幂等**：同一天算两次不能发两次。靠 `season_settlements` 的主键
   `(uid, day)` + 和余额更新同一个事务。
3. **补齐**：三天没打开，一次补三天。
4. **已结算的天不可变**：之后导入的纠正记录不回头改历史。
5. **`lastSettledDay` 和余额在服务端**。

## 接口为什么没有「结算哪一天」这个参数

`SeasonRepository.settleCompletedDays` 在客户端就**故意不接受**这个参数
（`TASKS.md` §3.2 点名了），服务端这里保持一致：能算哪些天**只由数据本身决定**
（`lastSettledDay` 之后、今天之前）。

## 换算率

`CONTRACTS.md` §8：每 100 万 token 换 100 个资源，也就是 **1000 token = 1 单位**；
`cacheRead` 和 `cacheWrite` **合成一种**资源。三种资源**互不通兑**——
缓存命中单价只有原价十分之一左右，按原始 token 数换同一种资源的话，
「多用缓存」就成了刷资源的漏洞。

**只取整、向下**：余数不攒、不进位。攒余数会让「换个顺序结算」得到不同的余额。
"""
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from .relay_store import FINANCIAL_TIMEZONE, UTC_TO_FINANCIAL

# 每 100 万 token 换 100 个资源 → 1 个资源 = 10000 token。
# App 侧 `MainActivity.TOKENS_PER_UNIT = 1_000_000L / 100L` 说的是同一件事，
# 两处必须一起改。（**不是 1000**——写成 1000 会让资源多十倍，
# 而且界面上看着仍然「像那么回事」，不会报错。）
TOKENS_PER_UNIT = 1_000_000 // 100

# 一次最多往回补多少天。不是业务规则，是防呆：`lastSettledDay` 为空时
# （新用户第一次结算）如果没有上限，就会把账号存在的全部历史一次算完。
MAX_BACKFILL_DAYS = 400

SCHEMA = """
CREATE TABLE IF NOT EXISTS season_balances (
 user_id TEXT PRIMARY KEY, last_settled_day TEXT,
 input INTEGER NOT NULL DEFAULT 0, cache INTEGER NOT NULL DEFAULT 0,
 output INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS season_settlements (
 user_id TEXT NOT NULL, day TEXT NOT NULL,
 input INTEGER NOT NULL DEFAULT 0, cache INTEGER NOT NULL DEFAULT 0,
 output INTEGER NOT NULL DEFAULT 0, calls INTEGER NOT NULL DEFAULT 0,
 settled INTEGER NOT NULL, PRIMARY KEY(user_id, day)
);
"""


def today_in_financial_timezone():
    """服务端的「今天」，口径和 App 的 `TimeUtils.today()` 一致（Asia/Shanghai）。

    用服务器本地时区算的话，服务器在 UTC 时会在北京时间早上 8 点前
    把「昨天」当成「今天」——于是当天还没结束的用量被结算掉，
    而那天的用量之后还会继续涨，第 4 条规则（已结算的天不可变）就破了。
    """
    return datetime.now(ZoneInfo(FINANCIAL_TIMEZONE)).date()


def tokens_to_resources(input_tokens, cache_read, cache_write, output_tokens):
    """token → 三种资源。**向下取整，余数丢弃**（见模块开头的说明）。"""
    return {
        "input": int(input_tokens or 0) // TOKENS_PER_UNIT,
        "cache": (int(cache_read or 0) + int(cache_write or 0)) // TOKENS_PER_UNIT,
        "output": int(output_tokens or 0) // TOKENS_PER_UNIT,
    }


def day_millis_range(day, days=1):
    """`yyyy-MM-dd` → 那段 UTC 毫秒区间 `[起, 止)`。

    天界按 `Asia/Shanghai`。**方向是「北京墙上时间 → UTC」，而且要多减一天的分界**：
    北京 09-20 00:00 = UTC **09-19** 16:00，所以起点要比 UTC 的 09-20 00:00
    **早** 8 小时（`- UTC_TO_FINANCIAL`），不是晚 8 小时。
    用 `+偏移` 的话北京凌晨 0–8 点的用量会落在区间**之外**——那半天的数据被整天跳过，
    而中午之后的用量照常，看起来只是「某天少了一点」。**这个方向错过一次。**

    **naive datetime 必须显式锚到 UTC 再取时间戳。** `datetime(...).timestamp()`
    把 naive 值按**服务器本地时区**解释——服务器现在正好是 UTC+8，所以写错也碰巧对，
    但换一台 UTC 的机器就会整整错 8 小时，也就是报表上的日期差一天，
    而且不会有任何报错。
    """
    start = date.fromisoformat(day)
    end = start + timedelta(days=days)

    def millis(value):
        anchored = datetime(value.year, value.month, value.day, tzinfo=timezone.utc)
        return (int(anchored.timestamp()) - UTC_TO_FINANCIAL) * 1000

    return millis(start), millis(end)


class Seasons:
    """赛季余额与结算。挂在内核的 `Store` 上，和中转共用一个数据库。

    `day_provider` 是**为测试留的**：规则 1「今天永远不结算」和规则 3「补齐三天」
    都没法用真实时钟验证（造不出「三天前」这种时刻，也不该去改系统时间）。
    生产用默认值，测试传一个固定返回某天的函数。
    """

    def __init__(self, store, relay, day_provider=None):
        self.store = store
        self.relay = relay
        self.today = day_provider or today_in_financial_timezone
        with store.connect() as db:
            db.executescript(SCHEMA)

    def state(self, digest):
        with self.store.connect() as db:
            row = db.execute("SELECT * FROM season_balances WHERE user_id=?", (digest,)).fetchone()
        if row is None:
            return {"uid": digest, "lastSettledDay": None,
                    "balance": {"input": 0, "cache": 0, "output": 0},
                    "updatedAtEpochMillis": None}
        return {"uid": digest, "lastSettledDay": row["last_settled_day"],
                "balance": {"input": row["input"], "cache": row["cache"], "output": row["output"]},
                "updatedAtEpochMillis": row["updated"]}

    def month_tokens(self, digest, month):
        """某个月的四类 token 合计。`month` 是 `yyyy-MM`。

        和 `relay.daily_for` 用同一套天口径，所以「这个月」和日汇总的柱子加起来一致。
        """
        today = self.today()
        first = f"{month}-01"
        # 月末用「下个月一号」表示，省掉月份天数判断（2 月、闰年都不用特判）。
        year, number = int(month[:4]), int(month[5:7])
        following = f"{year + 1}-01-01" if number == 12 else f"{year}-{number + 1:02d}-01"
        # **不能写 `daily_for(digest, *day_millis_range(first), until=...)`**：
        # `day_millis_range` 返回两个值，展开后第二个会绑到 `until` 上，
        # 而后面又用关键字传了一次 `until`，直接 TypeError。
        start_millis, _ = day_millis_range(first)
        following_millis, _ = day_millis_range(following)
        rows = self.relay.daily_for(digest, since=start_millis, until=following_millis, limit=5000)
        totals = {"input": 0, "cacheRead": 0, "cacheWrite": 0, "output": 0, "calls": 0}
        for row in rows:
            totals["input"] += row["input"]
            totals["cacheRead"] += row["cacheRead"]
            totals["cacheWrite"] += row["cacheWrite"]
            totals["output"] += row["output"]
            totals["calls"] += row["calls"]
        totals["isCurrentMonth"] = month == today.strftime("%Y-%m")
        return totals

    def settle(self, digest):
        """把「已经过完但还没结算」的天全部结算掉。**今天不算。**

        返回这次真正结算了哪几天，所以「补了三天」和「早就结完了」看得出来——
        幂等的验证就靠这个：连调两次，第二次的 `settledDays` 必须是空的。
        """
        today = self.today()
        with self.store.connect(write=True) as db:
            row = db.execute("SELECT * FROM season_balances WHERE user_id=?", (digest,)).fetchone()
            last = row["last_settled_day"] if row else None
            # 起点：结算过的从次日开始；从没结算过的，往回最多 MAX_BACKFILL_DAYS 天。
            # 上界是「昨天」（严格小于今天），所以今天永远不算（规则 1）。
            floor = (date.fromisoformat(last) + timedelta(days=1)) if last \
                else (today - timedelta(days=MAX_BACKFILL_DAYS))
            if floor >= today:
                # 没有可结算的天。余额直接从手里这行读，**不要调 `self.state()`**——
                # 那会另开连接，而这里握着写锁（同上）。
                unchanged = {"input": row["input"], "cache": row["cache"], "output": row["output"]} if row \
                    else {"input": 0, "cache": 0, "output": 0}
                return {"uid": digest, "settledDays": [], "gained": {"input": 0, "cache": 0, "output": 0},
                        "tokensCounted": {"input": 0, "cacheRead": 0, "cacheWrite": 0, "output": 0},
                        "balanceAfter": unchanged, "lastSettledDay": last}
            start_millis, _ = day_millis_range(floor.isoformat())
            _, end_millis = day_millis_range((today - timedelta(days=1)).isoformat())
            rows = self.relay.daily_for(digest, start_millis, until=end_millis, limit=5000)

            gained = {"input": 0, "cache": 0, "output": 0}
            counted = {"input": 0, "cacheRead": 0, "cacheWrite": 0, "output": 0}
            settled_days = []
            # 按天合并：一天里可能有多个 (provider, 模型)，先加总再取整——
            # 每个 (provider, 模型) 各取一次整的话，零头会被丢掉很多次。
            #
            # 循环变量**不能叫 `row`**：上面那个 `row` 是 `season_balances` 的那一行，
            # 这里是 `relay_usage` 的行，两者**列名不一样**。用同一个名字会把余额行
            # 覆盖掉，然后在 `row["cache"]` 上抛 KeyError——这个 bug 真的发生过，
            # 而且报错位置离真正的原因隔了二十行。
            per_day = {}
            for usage_row in rows:
                bucket = per_day.setdefault(usage_row["day"], {"input": 0, "cacheRead": 0,
                                                               "cacheWrite": 0, "output": 0, "calls": 0})
                for key in bucket:
                    bucket[key] += usage_row[key]
            for day in sorted(per_day):
                if date.fromisoformat(day) >= today:
                    continue                      # 规则 1：今天不结算（双保险）
                if last is not None and day <= last:
                    continue                      # 规则 4：已结算的天不可变
                bucket = per_day[day]
                converted = tokens_to_resources(bucket["input"], bucket["cacheRead"],
                                                bucket["cacheWrite"], bucket["output"])
                for key in gained:
                    gained[key] += converted[key]
                for key in counted:
                    counted[key] += bucket[key]
                # 主键 (uid, day) 让同一天不可能结算两次（规则 2）——
                # 这条 INSERT 和下面的余额更新在同一个事务里。
                db.execute("""INSERT OR IGNORE INTO season_settlements
                    (user_id,day,input,cache,output,calls,settled) VALUES(?,?,?,?,?,?,1)""",
                    (digest, day, converted["input"], converted["cache"], converted["output"], bucket["calls"]))
                settled_days.append(day)
            stamp = int(datetime.now(timezone.utc).timestamp() * 1000)
            if settled_days:
                newest = settled_days[-1]
                if row is None:
                    db.execute("""INSERT INTO season_balances(user_id,last_settled_day,input,cache,output,updated)
                        VALUES(?,?,?,?,?,?)""",
                        (digest, newest, gained["input"], gained["cache"], gained["output"], stamp))
                else:
                    db.execute("""UPDATE season_balances SET last_settled_day=?,
                        input=input+?, cache=cache+?, output=output+?, updated=? WHERE user_id=?""",
                        (newest, gained["input"], gained["cache"], gained["output"], stamp, digest))
            # 余额在**事务内按已知值算**，不再调 `self.state()`：那会另开一个连接，
            # 而这里已经握着写锁（`BEGIN IMMEDIATE`），多开连接轻则读到旧值、
            # 重则等锁。返回的 balanceAfter 必须包含这次刚结算的量。
            before = {"input": row["input"], "cache": row["cache"], "output": row["output"]} if row \
                else {"input": 0, "cache": 0, "output": 0}
            balance = {key: before[key] + gained[key] for key in before}
            final_day = settled_days[-1] if settled_days else last
        return {"uid": digest, "settledDays": settled_days, "gained": gained,
                "tokensCounted": counted, "balanceAfter": balance, "lastSettledDay": final_day}

    def spend(self, digest, input_amount, cache_amount, output_amount):
        """扣资源。**三种资源互不通兑**，而且余额不能变负。

        余额检查放在这个事务里（`BEGIN IMMEDIATE` 已经把写锁拿到手），
        不是先读再写——先读再写在两个并发请求下会双双通过检查然后一起扣成负数。
        """
        amounts = {"input": int(input_amount or 0), "cache": int(cache_amount or 0),
                   "output": int(output_amount or 0)}
        if any(value < 0 for value in amounts.values()):
            return None, "negative"
        if not any(amounts.values()):
            return None, "empty"
        with self.store.connect(write=True) as db:
            row = db.execute("SELECT * FROM season_balances WHERE user_id=?", (digest,)).fetchone()
            if row is None:
                return None, "insufficient"
            for key in amounts:
                if row[key] < amounts[key]:
                    return None, "insufficient"
            db.execute("""UPDATE season_balances SET input=input-?, cache=cache-?, output=output-?, updated=?
                WHERE user_id=?""",
                (amounts["input"], amounts["cache"], amounts["output"],
                 int(datetime.now().timestamp() * 1000), digest))
        return self.state(digest)["balance"], None

    def settled_days(self, digest, limit=400):
        with self.store.connect() as db:
            rows = db.execute("""SELECT day,input,cache,output,calls FROM season_settlements
                WHERE user_id=? ORDER BY day DESC LIMIT ?""", (digest, limit)).fetchall()
        return [{"day": row["day"], "gained": {"input": row["input"], "cache": row["cache"],
                                               "output": row["output"]}, "calls": row["calls"]}
                for row in rows]
