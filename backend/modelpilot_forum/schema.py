"""库的形状与「一天」的口径。

2026-09-30 之后，服务端只剩下账号、论坛/新闻、以及智能体自己那本账
（`agent_usage`）。原来用量、预算、价目、赛季四组表都在 `usage_store.py` 里建，
那一整套随记账一起删了——**但老库里那些表还在**，所以这个模块接手两件事：

1. **把废弃的表删掉**（`drop_obsolete_tables`，启动时跑一次，幂等）。
   `CREATE TABLE IF NOT EXISTS` 不会删表，不写这一步的话，生产库上会一直留着
   `relay_secrets`（用户上游 key 的明文）、`relay_usage`（用量流水）这些**没有
   任何代码读、但里面有数据**的表。删表比留一堆没人管的表安全。
2. **提供全服务共用的「一天」口径**。存储是 UTC 毫秒，而"哪一天"按
   `Asia/Shanghai` 算——智能体按月读自己那本账要用它。
"""
import re
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

# 「一天」的口径，和 App 侧 `util/TimeUtils.ZONE` 必须是同一个。
#
# 这里**不写死 28800**，而是从一个带时区的时刻真的算一遍偏移——北京没有夏令时，
# 所以现在两者等价；但写死的话，将来换时区（或哪天口径改成别的时区）就要改两处，
# 而且改漏了不会报错，只会让同一天的用量落到相邻两天上。
FINANCIAL_TIMEZONE = "Asia/Shanghai"
DAY_OFFSET_SECONDS = int(datetime.now(timezone.utc).astimezone(
    ZoneInfo(FINANCIAL_TIMEZONE)).utcoffset().total_seconds())

# **同一个偏移有两个方向，两个都要有名字。** 踩过：两处都用 `+DAY_OFFSET_SECONDS`，
# 等于把偏移加了两次——北京 20 号 12:00 的用量（UTC 04:00）落在
# `day_millis_range("2026-09-20")` 的**起点之前**，于是那一整天在按天查询里
# 被跳过、在按天汇总里被算成「缺数据」。数字看起来只是「少了一天」，很难查。
#
#   UTC → 北京墙上时间：`instant + 偏移`   ← SQL 分组用这个
#   北京墙上时间 → UTC：`wallclock - 偏移`  ← 日期区间转毫秒用这个
UTC_TO_FINANCIAL = DAY_OFFSET_SECONDS
FINANCIAL_TO_UTC = -DAY_OFFSET_SECONDS

# 没启用的表：**全部**是 2026-09-30 那次收缩删掉的功能留下的。
#
#   relay_keys / relay_secrets —— 中转凭据与用户上游 key 明文
#   relay_usage                —— 用量流水（记账那套）
#   season_balances / season_settlements —— 游戏赛季
#   budgets                    —— 预算上限
#   pricing_rates              —— 价目表
#
# 用 `DROP TABLE IF EXISTS`：新库上本来就没有，老库上删掉。**幂等**（每次启动都跑）。
OBSOLETE_TABLES = (
    "relay_secrets",
    "relay_keys",
    "relay_usage",
    "season_settlements",
    "season_balances",
    "budgets",
    "pricing_rates",
)


def drop_obsolete_tables(db):
    """把上面那些表从老库里删掉。启动时跑，幂等。

    历史：这个函数的前身是 `usage_store.drop_relay_tables()`，那时只删两张凭据表，
    还要排在"归属列改名"之后（回填要靠 `relay_keys`）。现在连用量表都删了，
    没有回填这回事了，顺序也就无所谓了。
    """
    for table in OBSOLETE_TABLES:
        db.execute(f"DROP TABLE IF EXISTS {table}")


def today_in_financial_timezone():
    """服务端的「今天」，口径和 App 的 `TimeUtils.today()` 一致（Asia/Shanghai）。

    **不能用系统本地时区**：服务器在 UTC 上跑，本地日期会比北京晚 8 小时，
    于是"今天"在每天 16:00 到 24:00（UTC）之间是错的——那正好是北京的下一天。
    """
    return datetime.now(ZoneInfo(FINANCIAL_TIMEZONE)).date()


def day_millis_range(day):
    """北京时间某一天的 `[起点, 次日起点)`，UTC 毫秒。"""
    start = date.fromisoformat(day)
    begin = datetime(start.year, start.month, start.day, tzinfo=timezone.utc)
    begin = int(begin.timestamp() * 1000) - DAY_OFFSET_SECONDS * 1000
    return begin, begin + 86_400_000


def month_bounds(month):
    """`2026-09` → `("2026-09-01", "2026-10-01")`。校验交给调用方。"""
    if not re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])", month or ""):
        raise ValueError(f"month must be yyyy-MM, got {month!r}")
    first = date.fromisoformat(month + "-01")
    following = (first + timedelta(days=31)).replace(day=1)
    return first.isoformat(), following.isoformat()
