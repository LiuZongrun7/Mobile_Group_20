"""用量账本的存储层（原 `relay_store.py`，2026-09-30 随中转一起改名）。

**这里现在只有一件事：用量。** 中转删掉之后，`relay_keys`（凭据）与
`relay_secrets`（用户的上游 key 明文）两张表连同它们的代码一起没了——
用户不再把自己的 API key 交给我们，"这条用量是谁交上来的"也不再是一个问题。

留下来的：

1. `relay_usage` —— 每次模型调用的四个 token 桶、provider、model、时间、`call_id`。
   归属列是**账号的 `user_id`**（2026-02 改的，之前挂在 relay key 上，
   于是"换个 key 就换个人"）。
2. 按天 / 按次读回来的三个查询（`daily_for` / `calls_for` / `usage_for`），
   Insights、预算、赛季三处都用它。
3. 「一天」的口径（`Asia/Shanghai`）——和 App 侧 `util/TimeUtils.ZONE` 必须是同一个。

**表名保留 `relay_usage`**：改表名要写迁移、要动已经在跑的生产库，而不改变任何行为；
下次真的动 schema 时一起做。`record()` 现在是**唯一**的写入口，但生产上暂时没有
调用方——"用户在 App 里提问、后端调模型"那条路还没写（新大纲 4–15 周的主体），
写完由它来记账，所以这个方法留着。
"""
import json
import re
import secrets
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from .store import now_ms



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

TABLES = """
-- 用量的归属是**账号**（`user_id`），不是 relay key。
--
-- 2026-02 改：这一列原来叫 `key_hash`，指向注册它的那条 key。那是「relay key
-- 自带身份」那个阶段的遗留——换个 key 就等于换个人，余额和赛季跟着清零。
-- 现在 relay key 只是「往这个账号提交用量的一条路」，一个账号可以有多条
-- （换上游、轮换凭据），所以账必须记在账号上。
--
-- 老数据（如果真有）在迁移里按注册时记下的 `relay_keys.user_id` 回填；
-- 回填不到的保持旧值，等于「自成一户」——宁可让它孤立，也不记到别人名下。
CREATE TABLE IF NOT EXISTS relay_usage (
 call_id TEXT, user_id TEXT NOT NULL, model TEXT NOT NULL,
 service_tier TEXT, provider TEXT, created INTEGER NOT NULL,
 input INTEGER NOT NULL DEFAULT 0, cache_read INTEGER NOT NULL DEFAULT 0,
 cache_write INTEGER NOT NULL DEFAULT 0, output INTEGER NOT NULL DEFAULT 0,
 calls INTEGER NOT NULL DEFAULT 1
);
"""

# 建索引**必须和建表分开**，而且必须排在迁移之后。
# 踩过的坑（两次）：`relay_usage_day` 用到 `provider`、`relay_usage_call` 用到 `call_id`，
# 两列都是迁移才加的。放进 TABLES 一起跑的话，旧表上会直接报
# "no such column"——而那时候进程正在启动，表现成「服务起不来」，
# 看不出根因是索引顺序。
INDEXES = """
CREATE INDEX IF NOT EXISTS relay_usage_lookup ON relay_usage(user_id, created);
CREATE INDEX IF NOT EXISTS relay_usage_day ON relay_usage(user_id, created, provider, model);
CREATE UNIQUE INDEX IF NOT EXISTS relay_usage_call ON relay_usage(user_id, call_id);
"""

# 已存在的表加列。`CREATE TABLE IF NOT EXISTS` 对**已存在的表**什么也不做，
# 所以光改上面的 TABLES 是加不上列的——服务器上已经有这张表了（即使是空的）。
# 不写迁移的话，新代码一 INSERT 就会报 "no such column: provider"，
# 而那时候服务已经在跑、正在转发请求。
#
# `call_id` 是为了 `GET /usage/calls` 能给出 `UsageCall.id`。
# **不能靠 `created` 事后拼**：同一毫秒里的两个请求会拼出同一个 id，
# 而那个 id 正是客户端去重用的键——撞了就丢数据。
MIGRATIONS = (
    ("relay_usage", "provider", "ALTER TABLE relay_usage ADD COLUMN provider TEXT"),
    ("relay_usage", "call_id", "ALTER TABLE relay_usage ADD COLUMN call_id TEXT"),
)


def _columns(db, table):
    """表有哪些列。表不存在时返回空集。"""
    return {row["name"] for row in db.execute(f"PRAGMA table_info({table})")}


def _replace_identifier(text, before, after):
    """把一段 SQL 里的**标识符** `before` 换成 `after`（字符串字面量不动）。

    为什么要按标识符切着换：朴素地 `text.replace("key_hash", "user_id")`
    会连外键里的 `relay_keys(key_hash)` 一起改掉，那条语句就再也建不出来了。
    比较前要把引号剥掉——`ALTER TABLE ... RENAME TO` 记下的名字是
    `"relay_usage_old"` 这样的带引号形式。
    """
    parts = re.split(r'("(?:[^"]|"")*"|\'(?:[^\']|\'\')*\'|`[^`]*`|\[[^\]]*\]|[A-Za-z_][A-Za-z0-9_]*)',
                     text)
    return "".join(f'"{after}"' if part and part.strip('"`[]') == before else part
                   for part in parts)


def _column_definitions(db, table):
    """表的列定义，直接可用来拼 `CREATE TABLE`。

    遍历顺序就是 `PRAGMA table_info` 的顺序（即表里的列顺序），
    否则重建出来的表列序会变——列序本身不影响语义，但会让
    「同一张表在两台机器上长得不一样」，之后对不上就很难查了。
    """
    definitions = []
    primary_key = []
    for row in db.execute(f"PRAGMA table_info({table})"):
        piece = f'"{row["name"]}" {row["type"] or "TEXT"}'
        if row["notnull"]:
            piece += " NOT NULL"
        if row["dflt_value"] is not None:
            piece += f' DEFAULT {row["dflt_value"]}'
        definitions.append(piece)
        if row["pk"]:
            primary_key.append((row["pk"], row["name"]))
    if primary_key:
        # 主键拼成**表级**子句，列名从 `pk` 序号拿。原来想从建表语句里抠
        # `PRIMARY KEY(...)`，但 `key_hash TEXT PRIMARY KEY` 这种写法里的
        # `PRIMARY KEY` 挂在列上，抠不到——那会在下面报
        # "expressions prohibited in PRIMARY KEY"（SQLite 把不存在的列名
        # 当成表达式），而报错完全指不到「主键没抽出来」这件事上。
        primary_key.sort()
        definitions.append("PRIMARY KEY("
                           + ", ".join(f'"{name}"' for _, name in primary_key) + ")")
    return definitions


def _rebuild_column(db, table, old, new, backfill=None):
    """把 `old` 列改名成 `new`，并把列里的值改写一遍。返回是否真的动了。

    为什么不用现成的 `ALTER TABLE ... RENAME COLUMN`：那一条既不能带条件，
    也不能顺手改值。迁移在这里要做两件事——**改名**（`key_hash` → `user_id`）
    和**改写值**（旧的 key sha256 换成它所属账号的 user_id）。

    做法是标准的「建新表 → 搬 → 换名」（SQLite 不支持改列约束，只能这样）。

    §为什么是拼列定义、而不是改写原建表语句§：改写过两版都栽在引号上——
    `ALTER TABLE ... RENAME TO` 会把表名记成带双引号的 `"relay_usage_old"`，
    而按标识符切分时又要把引号剥掉才能比得上，两边一凑就静默不生效
    （表现成「新表没建出来」，报错还指错方向）。`PRAGMA table_info` 给的是
    干净的列名和类型，拼出来的语句没有歧义。**代价是主键要单独保**——
    它是表级约束，不在列信息里，所以从建表语句里抠出来原样带过去。

    搬家期间要临时关掉外键：SQLite 的外键是在**语句结束**时检查的，
    `DROP TABLE relay_keys` 会因为 `relay_usage` 还指着它而报错，
    而那时候正是最不能失败的时候（进程启动中）。
    """
    if new in _columns(db, table) or old not in _columns(db, table):
        return False
    columns = [row["name"] for row in db.execute(f"PRAGMA table_info({table})")]
    # 逐条换名字：列定义和主键子句都过一遍 `_replace_identifier`，
    # 主键里的列名不会漏（它就是被改名的这一列）。
    definitions = [_replace_identifier(piece, old, new)
                   for piece in _column_definitions(db, table)]

    db.execute("PRAGMA foreign_keys=OFF")
    try:
        db.execute(f"ALTER TABLE {table} RENAME TO {table}_old")
        # 换名之后旧表的索引跟着表走，新表上再建同名索引会撞车——先清掉。
        # （那几张 `relay_usage_*` 索引由 `INDEXES` 在迁移之后重建。）
        for row in db.execute("SELECT name FROM sqlite_master WHERE type='index' "
                              "AND tbl_name=?", (f"{table}_old",)).fetchall():
            if row["name"] and not row["name"].startswith("sqlite_autoindex"):
                db.execute(f'DROP INDEX IF EXISTS "{row["name"]}"')
        db.execute(f'CREATE TABLE "{table}" ({", ".join(definitions)})')
        # 搬数据的列名**一个个加引号**，而且**两边用各自的名字**：
        # 新表那边是 `new`，老表那边还是 `old`。不加引号时 SQLite 会先按名字
        # 去源表找，找到 `user_id` 就当成 `relay_usage_old.user_id`，
        # 报 "no such column: user_id"（踩过：报错信息完全指错方向）。
        target = ",".join(f'"{new}"' if name == old else f'"{name}"' for name in columns)
        source = ",".join(f'"{name}"' for name in columns)
        db.execute(f"INSERT INTO {table}({target}) SELECT {source} FROM {table}_old")
        if backfill:
            # **`OR IGNORE` 这一句是整个迁移的关节。** 回填是
            # `SET user_id=(子查询)`，认不出账号的行会拿到 NULL：
            #   * 普通 `UPDATE`：撞 NOT NULL 就整条语句失败，
            #     连**匹配得上**的行也不回填（踩过：报 "NOT NULL constraint failed"）；
            #   * `UPDATE OR IGNORE`：写不进去的行原样跳过。
            # 于是「认不出账号的老 key 自成一户」这条语义不用额外代码就成立了。
            expression = backfill.replace(f"{table}.{old}", f"{table}.{new}")
            db.execute(f"UPDATE OR IGNORE {table} SET {new}={expression}")
        db.execute(f"DROP TABLE {table}_old")
    finally:
        db.execute("PRAGMA foreign_keys=ON")
    return True


# 用量/赛季/预算的归属列改名：`key_hash` → `user_id`（2026-02）。
# 值也要改：老的 key sha256 换成那条 key 所属账号的 user_id，否则历史用量
# 会全部变成「查不到」。取不到账号的老行保持原值（自成一户，见上面的注释）。
_OWNERSHIP_RENAMES = (
    ("relay_usage", "(SELECT k.user_id FROM relay_keys k "
                    "WHERE k.key_hash=relay_usage.user_id AND k.user_id IS NOT NULL)"),
    ("season_balances", "(SELECT k.user_id FROM relay_keys k "
                        "WHERE k.key_hash=season_balances.user_id AND k.user_id IS NOT NULL)"),
    ("season_settlements", "(SELECT k.user_id FROM relay_keys k "
                           "WHERE k.key_hash=season_settlements.user_id AND k.user_id IS NOT NULL)"),
    ("budgets", "(SELECT k.user_id FROM relay_keys k "
                "WHERE k.key_hash=budgets.user_id AND k.user_id IS NOT NULL)"),
)


def migrate_ownership(db):
    """把上面那四张表的归属列改名并回填。幂等：改过一遍之后再跑是空操作。

    **回填只在 `relay_keys` 还在的时候做。** 那张表是"老行属于谁"的唯一线索，
    而它现在已经从 TABLES 里删掉了（2026-09-30），新库上根本不会存在：
    这时候只改名、不回填——值保持原样，等于"自成一户"，
    和取不到账号时的处理一致（宁可孤立，也不记到别人名下）。
    """
    changed = []
    legacy_keys = "relay_keys" in {row["name"] for row in
                                   db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    for table, backfill in _OWNERSHIP_RENAMES:
        if not legacy_keys:
            backfill = None
        try:
            if _rebuild_column(db, table, "key_hash", "user_id", backfill):
                changed.append(table)
        except Exception as error:  # noqa: BLE001 —— 见下面的说明
            # 迁移失败**不能把服务带崩**：库里的表可能根本不存在（没启用中转），
            # 或者形状和预期不一样。如实记一行，让后续查询去报它自己的错。
            print(f"[usage_store] ownership migration skipped for {table}: {error}")
    return changed






def drop_relay_tables(db):
    """把中转时代的 `relay_keys` / `relay_secrets` 从老库里删掉。

    `CREATE TABLE IF NOT EXISTS` 不会删掉已经存在的表，所以不写这一步的话，
    生产库上那两张表会一直留着——里面没有任何代码会读它，但**只要有数据，
    它就是"用户上游 key 明文"的残留**。删表比留一张没人管的凭据表安全。

    幂等：`IF EXISTS`，新库上什么也不做。
    """
    db.executescript("""
DROP TABLE IF EXISTS relay_secrets;
DROP TABLE IF EXISTS relay_keys;
""")


def migrate(db):
    """把已存在的表补到最新结构。

    `PRAGMA table_info` 是 SQLite 里查列名的标准做法；列已存在就跳过，
    所以这个函数**可以重复跑**（每次进程启动都会跑一次）。
    """
    for table, column, statement in MIGRATIONS:
        columns = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
        if columns and column not in columns:
            db.execute(statement)


class UsageStore:
    """用量账本的读写（原 `RelayStore`，2026-09-30 随中转一起改名）。

    中转删掉之后这里只剩「记一笔用量」和「按天 / 按次读回来」——它是 Insights、
    预算、赛季三处共同的数据源。`relay_usage` 这个**表名**保留：改表名要写迁移，
    而且不改变任何行为，等下次真动 schema 时一起做。
    """

    def __init__(self, store):
        self.store = store
        with store.connect() as db:
            # 顺序是功能性的：建表 → 补列 → 建索引。索引依赖 provider 列，
            # 所以不能并进 TABLES 一起跑（见 INDEXES 的注释）。
            db.executescript(TABLES)
            migrate(db)
            # 归属列改名排在 `migrate` 之后、`INDEXES` **之前**：
            # 上面的索引就建在 `user_id` 上，顺序反了会在老库上直接报
            # "no such column"（这个坑前面踩过两次，见 INDEXES 的注释）。
            migrate_ownership(db)
            db.executescript(INDEXES)
            # **删凭据表必须排在 `migrate_ownership` 之后**：那个迁移要把老的
            # `key_hash` 列按 `relay_keys.user_id` 回填，先把表删了它就查不到来源
            # （只在老库上会走到，但那时正是最不能出错的时候）。
            drop_relay_tables(db)
    def record(self, db, digest, tokens):
        """把一次模型调用的用量落库。

        `digest` 是**账号的 user_id**：一个账号无论从哪条路产生用量（App 内提问、
        导入的记录），账都记在同一个人头上。

        `db` 由调用方的 `with store.connect(write=True)` 传进来，让「记用量」和
        同一批里的其它写在一个事务里——分开写的话，崩在半路会留下半截状态。

        `call_id` 在这里生成，形状按 `UsageCall.id` 的约定：
        `call:<provider>:<uid 前缀>:<毫秒>:<随机>`。
        **前缀 `call:` 是必需的**——`UsageCall` 里没有别的字段能说明这行是逐次调用
        还是时间桶，而「能不能做按会话分析」必须能算出来（桶行不行）。

        写入用 upsert 累加：`calls` 列和 `(user_id, call_id)` 唯一索引都保留，
        同一个 id 重放不会丢数据也不会报错。正常情况下 id 是新的，走不到更新那一支。
        """
        if tokens is None:
            return
        provider = tokens.get("provider") or "unknown"
        stamp = now_ms()
        identifier = tokens.get("callId") or "call:{}:{}:{}:{:06x}".format(
            provider, digest[:12], stamp, secrets.randbelow(1 << 24))
        db.execute("""INSERT INTO relay_usage(user_id,model,service_tier,provider,created,
            input,cache_read,cache_write,output,calls,call_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(user_id,call_id) DO UPDATE SET
            input=input+excluded.input, cache_read=cache_read+excluded.cache_read,
            cache_write=cache_write+excluded.cache_write, output=output+excluded.output,
            calls=calls+excluded.calls""",
            (digest, tokens["model"], tokens.get("serviceTier"), tokens.get("provider"), stamp,
             tokens["input"], tokens["cacheRead"], tokens["cacheWrite"], tokens["output"],
             tokens["calls"], identifier))

    def calls_for(self, digest, since=0, until=None, limit=500):
        """逐次调用记录，`UsageCall` 的形状。

        **只有带 `call_id` 的行才返回**：那一列是后加的，老行（迁移之前写的）
        没有可用的 id，而 `UsageCall.id` 是客户端去重的键——给一个拼出来的假 id
        会让两条不同的记录撞成一条。
        """
        query = """SELECT call_id,provider,model,created,input,cache_read,cache_write,
            output,calls FROM relay_usage
            WHERE user_id=? AND call_id IS NOT NULL AND created>=?"""
        parameters = [digest, since]
        if until is not None:
            query += " AND created<?"
            parameters.append(until)
        query += " ORDER BY created DESC, call_id DESC LIMIT ?"
        parameters.append(max(1, min(limit, 2000)))
        with self.store.connect() as db:
            rows = db.execute(query, parameters).fetchall()
        return [{"id": row["call_id"], "provider": row["provider"], "model": row["model"],
                 "startedAtEpochMillis": row["created"], "input": row["input"],
                 "cacheRead": row["cache_read"], "cacheWrite": row["cache_write"],
                 "output": row["output"], "calls": row["calls"]}
                for row in rows]

    def usage_for(self, digest, since=0, limit=1000):
        """按 (模型, 档位, 供应商) 汇总这个**账号**的用量。"""
        with self.store.connect() as db:
            rows = db.execute("""SELECT model,service_tier,provider,
                SUM(input) AS input,SUM(cache_read) AS cache_read,SUM(cache_write) AS cache_write,
                SUM(output) AS output,SUM(calls) AS calls,MIN(created) AS first_created,
                MAX(created) AS last_created FROM relay_usage
                WHERE user_id=? AND created>=? GROUP BY model,service_tier,provider
                ORDER BY last_created DESC LIMIT ?""", (digest, since, limit)).fetchall()
        return [{"model": row["model"], "serviceTier": row["service_tier"], "provider": row["provider"],
                 "input": row["input"], "cacheRead": row["cache_read"],
                 "cacheWrite": row["cache_write"], "output": row["output"], "calls": row["calls"],
                 "firstAtEpochMillis": row["first_created"], "lastAtEpochMillis": row["last_created"]}
                for row in rows]

    def daily_for(self, digest, since=0, until=None, limit=1000):
        """按 (`天, provider, model`) 滚出来的日汇总——`DailyUsage` 的形状。

        **按天分组必须在 SQL 里做，不能拉回 Python 再分**：分组键是
        `Asia/Shanghai` 的日历天，而存储是 UTC 毫秒，两者的换算只有一处
        （见下面的 `DAY_OFFSET` 说明）。拉回 Python 分的话，
        「哪天算哪一天」这个口径就会在客户端和服务端各有一份实现，
        正是 `TimeUtils` 那个类注释要避免的事。
        """
        query = """SELECT date(created/1000 + ?, 'unixepoch') AS day, provider, model,
            SUM(input) AS input, SUM(cache_read) AS cache_read, SUM(cache_write) AS cache_write,
            SUM(output) AS output, SUM(calls) AS calls
            FROM relay_usage WHERE user_id=? AND created>=?"""
        parameters = [UTC_TO_FINANCIAL, digest, since]
        if until is not None:
            query += " AND created<?"
            parameters.append(until)
        query += " GROUP BY day, provider, model ORDER BY day DESC, model LIMIT ?"
        parameters.append(limit)
        with self.store.connect() as db:
            rows = db.execute(query, parameters).fetchall()
        return [{"day": row["day"], "provider": row["provider"], "model": row["model"],
                 "input": row["input"], "cacheRead": row["cache_read"],
                 "cacheWrite": row["cache_write"], "output": row["output"], "calls": row["calls"]}
                for row in rows]



def json_body(value):
    try:
        return json.loads(value)
    except (ValueError, TypeError):
        return None
