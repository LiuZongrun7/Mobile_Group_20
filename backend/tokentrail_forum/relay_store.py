"""中转服务的存储层：relay key、上游凭据、转发用量。

与论坛表分开的原因不是「好看」，是三件事真的不一样：

1. **上游凭据是这整个服务里最敏感的东西。** 它和别的分开一张表之后，
   「查身份」这条路径永远不碰它（`key()` 不 join `relay_secrets`）。
2. **用量要走结算和 agent，帖子不走。** `CONTRACTS.md` §5 把「用量私有」和
   「论坛公开」列为必须分开的两类校验，中转用量属于前者。
3. **这张表里有两样东西，别混**：`relay_keys` 是**凭据**（怎么把用量交上来），
   `relay_usage` 的归属列是**账号的 `user_id`**（这是谁的账）。

§2026-02 改§ 身份口径原来写的是「`uid = sha256(relay key)`」，现在**不是**了：
relay key 只是挂在账号下面的一条通道，用量、余额、预算、结算全部按账号的
`user_id` 记。`key_hash`（sha256(relay key)，十六进制 64 字符）仍然存在，
但它的用途只剩两个——查这条 key 的上游凭据、以及审计这条 key 用过多少次。
relay key 是 32 字节随机数的 base64url，所以原像空间足够大，sha256 不需要
加盐也不需要慢哈希。但**它仍然不许进日志**——见 `relay_auth.py` 里
`describe()` 的说明。
"""
import hashlib
import re
import json
import secrets
from datetime import datetime, timezone
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

from .store import now_ms


PREFIX = "tt_"
MIN_CUSTOM_LENGTH = 24
GENERATED_BYTES = 32

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
-- 身份总表。**这是「一个人」的唯一落点，别的地方都引用它。**
--
-- `user_id` 是**我们自己发的 UUID**（方案 B，2026-09-27 定）。为什么不用团队
-- user_id 当主键：那样「注册中转」就必须先登录团队账号，而 relay key 是
-- relay key、团队账号是团队账号——两件事能各自独立发生，身份表该能表达这一点。
--
-- `team_uid` 可空：填了表示「这个我们的用户 = 那个团队账号」。
-- 它只为一个功能服务：`getMyThreads`（查自己发过的帖子及反响）。
-- 论坛帖子的 `author_uid` 存的是**团队 uid**（那是团队后端给的，改不了），
-- 所以查「我的帖子」要靠这张表把两端对上。
--
-- **不要求登录也能用中转和智能体**：没绑定过 team_uid 的用户照样有 user_id，
-- 只是 `getMyThreads` 会如实说「不知道你在论坛是谁」。
-- relay key：**中转那条路专用的凭据**，属于一个账号。
--
-- 方位必须是这样：账号是身份（`accounts`），relay key 挂在账号下面。
-- 反过来（relay key 自带身份）会让用量、余额、结算全挂到 relay key 上，
-- 于是「换个 key 就换个人」——而实际上只是换了条提交用量的路。
CREATE TABLE IF NOT EXISTS relay_keys (
 key_hash TEXT PRIMARY KEY, user_id TEXT, display_name TEXT NOT NULL,
 created INTEGER NOT NULL, last_used INTEGER, request_count INTEGER NOT NULL DEFAULT 0,
 disabled INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS relay_secrets (
 key_hash TEXT PRIMARY KEY REFERENCES relay_keys(key_hash), upstream_url TEXT NOT NULL,
 upstream_secret TEXT NOT NULL, created INTEGER NOT NULL
);
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
    # 方案 B：`relay_keys` 一开始是自带一个随机 uid、没有 users 表的。
    # 这份迁移把老行补上一个 users 记录，让外键指向有意义的东西——
    # **不改 key_hash**（那是历史数据的归属，改了等于把过去记到别人名下）。
    # relay key 挂到账号上（2026-09-27）。老行没有 user_id 时留空——
    # 它们是在「relay key 自带身份」那个阶段注册的，谁是谁已经说不清了；
    # 开发阶段没有真实用户，所以直接当没绑过。
    ("relay_keys", "user_id", "ALTER TABLE relay_keys ADD COLUMN user_id TEXT"),
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
    """把上面那四张表的归属列改名并回填。幂等：改过一遍之后再跑是空操作。"""
    changed = []
    for table, backfill in _OWNERSHIP_RENAMES:
        try:
            if _rebuild_column(db, table, "key_hash", "user_id", backfill):
                changed.append(table)
        except Exception as error:  # noqa: BLE001 —— 见下面的说明
            # 迁移失败**不能把服务带崩**：库里的表可能根本不存在（没启用中转），
            # 或者形状和预期不一样。如实记一行，让后续查询去报它自己的错。
            print(f"[relay_store] ownership migration skipped for {table}: {error}")
    return changed


def upstream_host(url):
    """上游地址 → 主机名。**和中转解析上游时同一个口径**（`relay.upstream_target` 也是
    按 hostname 判 SSRF 的），所以这里不用另写一套规则。取不出来返回 None，
    不猜——猜出来的域名会让「这条 key 指向哪儿」这句话变成假的。
    """
    if not url:
        return None
    try:
        return urlsplit(url).hostname or None
    except ValueError:
        return None


def key_hash(value):
    """relay key → uid。十六进制而不是 base64：uid 会出现在 JSON 和查询里，
    十六进制没有 `-`/`_`，不用担心被当成 URL 安全字符处理。"""
    return hashlib.sha256(value.encode()).hexdigest()


def generate_key():
    """自动生成的 key。`tt_` 前缀让它一眼能从团队账号 token 里认出来，
    和测试身份的 `tt_test_` 是同一个思路。"""
    return PREFIX + secrets.token_urlsafe(GENERATED_BYTES)


def validate_custom_key(value):
    """自定义 key 的校验。返回 None 表示通过，否则返回给用户看的错误说明。

    长度下限不是形式主义：**自定义 key 同时是登录凭据**，太短就等于把
    「读我的用量」和「用我的上游 key 转发」这两件事一起交出去。
    """
    if not value or not isinstance(value, str):
        return "Relay key is required"
    if not value.startswith(PREFIX):
        return f"Relay key must start with {PREFIX!r}"
    if len(value) < MIN_CUSTOM_LENGTH:
        return f"Relay key must be at least {MIN_CUSTOM_LENGTH} characters"
    if len(value) > 200:
        return "Relay key is too long (maximum 200 characters)"
    if any(character.isspace() for character in value):
        return "Relay key must not contain whitespace"
    return None


def migrate(db):
    """把已存在的表补到最新结构。

    `PRAGMA table_info` 是 SQLite 里查列名的标准做法；列已存在就跳过，
    所以这个函数**可以重复跑**（每次进程启动都会跑一次）。
    """
    for table, column, statement in MIGRATIONS:
        columns = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
        if columns and column not in columns:
            db.execute(statement)


class RelayStore:
    """中转相关表的读写。挂在内核的 `Store` 上，共用同一个 sqlite 连接工厂。"""

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

    # ---- 注册与身份 ----------------------------------------------------

    def register(self, key, upstream_url, upstream_secret, display_name="", user_id=None):
        """给**某个账号**注册一个 relay key。撞了返回 None（调用方转 409）。

        `user_id` 是**账号的 id**（`accounts.user_id`）——relay key 没有自己的身份，
        它只是一个「怎么把用量交上来」的凭据，挂在账号下面。

        **没给 user_id 就拒绝。** 早期版本让 relay key 自带身份（uid = 它的 sha256），
        结果是用量、余额、结算全挂在一个凭据上——换个 key 就换个人。
        方位错了，所以现在必须显式给账号。
        """
        if not user_id:
            raise ValueError("relay key must belong to an account")
        digest = key_hash(key)
        with self.store.connect(write=True) as db:
            if db.execute("SELECT 1 FROM relay_keys WHERE key_hash=?", (digest,)).fetchone() is not None:
                return None
            created = now_ms()
            db.execute("""INSERT INTO relay_keys(key_hash,user_id,display_name,created)
                VALUES(?,?,?,?)""",
                (digest, user_id, display_name or default_name(digest), created))
            db.execute("INSERT INTO relay_secrets VALUES(?,?,?,?)",
                       (digest, upstream_url, upstream_secret, created))
        return self.summary(digest)

    def account_for(self, digest):
        """这个 relay key 属于哪个账号。**
        这就是上游 key → 账号的那一步**：转发时按它记用量，
        于是用量和论坛身份天然是同一个 user_id，不需要任何绑定表。
        """
        with self.store.connect() as db:
            row = db.execute("SELECT user_id FROM relay_keys WHERE key_hash=?", (digest,)).fetchone()
        return None if row is None else row["user_id"]

    def belongs_to(self, uid, user_id):
        """这条 key 是不是这个账号的。**一次查询**，不是把名下 key 拉回来再比。"""
        with self.store.connect() as db:
            row = db.execute("SELECT 1 FROM relay_keys WHERE key_hash=? AND user_id=?",
                             (uid, user_id)).fetchone()
        return row is not None

    def keys_for(self, user_id, with_upstream=False):
        """这个账号注册过哪些 relay key（一个账号可以有多条，比如换了上游）。

        **返回的是元数据，永远不含 key 明文**——库里只有 sha256，取不回来。
        所以「我的 key 长什么样」这件事只能靠 `displayName` 认，
        这也是注册时那个备注名值得填的原因（见 `docs/RELAY_API.md`）。
        """
        query = "SELECT key_hash, display_name, created, last_used, request_count, disabled " \
                "FROM relay_keys WHERE user_id=? ORDER BY created DESC"
        with self.store.connect() as db:
            rows = db.execute(query, (user_id,)).fetchall()
            urls = {}
            if with_upstream:
                # 上游地址在另一张表里（刻意的：查身份那条路不碰凭据表）。
                # 这里要 join 是因为用户得知道「这条 key 指向哪个上游」，
                # 否则多条 key 之间没法区分。**上游密钥仍然不返回。**
                urls = {row["key_hash"]: row["upstream_url"] for row in db.execute(
                    "SELECT key_hash, upstream_url FROM relay_secrets WHERE key_hash IN "
                    "(SELECT key_hash FROM relay_keys WHERE user_id=?)", (user_id,))}
        items = [{"uid": row["key_hash"], "displayName": row["display_name"],
                  "createdAtEpochMillis": row["created"],
                  "lastUsedAtEpochMillis": row["last_used"],
                  "requestCount": row["request_count"],
                  "disabled": bool(row["disabled"])} for row in rows]
        if with_upstream:
            for item in items:
                url = urls.get(item["uid"])
                item["upstreamUrl"] = url
                # 主机名在服务端算好给界面用：客户端不该为了显示一个域名去解析 URL，
                # 那种解析在各平台上的边界行为都不一样（端口、IPv6、末尾斜杠）。
                item["upstreamHost"] = upstream_host(url)
                item["hasUpstreamSecret"] = url is not None
        return items


    def owner_of(self, secret):
        """任意凭据（relay key **或账号 token**）→ 账号 `user_id`。认不出来返回 None。

        §这是「relay key 只出现在中转里」的落点§：用量、赛季、预算这些接口
        既收账号 token 也收 relay key，但**先把它解成账号**，后面的账都记在账号上。
        于是：
          * 一个账号换了几条 key，余额和赛季都不断；
          * 智能体（只有账号 token）也能读同一份用量，不需要 relay key；
          * 手机上只存账号 token 就够了。
        """
        if not secret:
            return None
        if secret.startswith(PREFIX):
            return self.account_for(key_hash(secret))
        from .accounts import Accounts
        identity = Accounts(self.store).resolve("Bearer " + secret)
        return None if identity is None else identity[0]

    def key(self, digest):
        """按 uid 取身份信息（**不含上游凭据**）。"""
        with self.store.connect() as db:
            row = db.execute("SELECT * FROM relay_keys WHERE key_hash=?", (digest,)).fetchone()
        return None if row is None else {
            "uid": row["key_hash"], "displayName": row["display_name"],
            "createdAtEpochMillis": row["created"], "lastUsedAtEpochMillis": row["last_used"],
            "requestCount": row["request_count"], "disabled": bool(row["disabled"])}

    def summary(self, digest):
        """身份信息 + 上游地址。**上游密钥本身永远不返回给任何调用方。**"""
        identity = self.key(digest)
        if identity is None:
            return None
        with self.store.connect() as db:
            row = db.execute("SELECT upstream_url FROM relay_secrets WHERE key_hash=?", (digest,)).fetchone()
        identity["upstreamUrl"] = row["upstream_url"] if row else None
        identity["hasUpstreamSecret"] = row is not None
        return identity

    def forwarding(self, digest):
        """转发时才取的东西：上游地址 + 上游密钥。别的地方不要调。"""
        with self.store.connect() as db:
            row = db.execute("SELECT upstream_url,upstream_secret FROM relay_secrets WHERE key_hash=?",
                             (digest,)).fetchone()
        return None if row is None else {"url": row["upstream_url"], "secret": row["upstream_secret"]}

    def update_upstream(self, digest, upstream_url=None, upstream_secret=None):
        """换上游地址或上游密钥。**不动 key_hash**，所以历史归属不会断——
        这正是 relay key 和上游凭据要分开的原因（见模块开头）。"""
        with self.store.connect(write=True) as db:
            if db.execute("SELECT 1 FROM relay_keys WHERE key_hash=?", (digest,)).fetchone() is None:
                return None
            if upstream_url is not None:
                db.execute("UPDATE relay_secrets SET upstream_url=? WHERE key_hash=?", (upstream_url, digest))
            if upstream_secret is not None:
                db.execute("UPDATE relay_secrets SET upstream_secret=? WHERE key_hash=?", (upstream_secret, digest))
        return self.summary(digest)

    def revoke(self, digest):
        """停用。**不删行**：用量虽然记在账号上，但这条 key 本身是审计线索
        （哪天注册的、用过多少次），删了就说不出过去了。"""
        with self.store.connect(write=True) as db:
            changed = db.execute("UPDATE relay_keys SET disabled=1 WHERE key_hash=?", (digest,)).rowcount
        return changed > 0

    def listing(self, digests):
        return [item for item in (self.summary(digest) for digest in digests) if item is not None]

    # ---- 转发记账 ------------------------------------------------------

    def touch(self, db, digest):
        """一次成功转发之后更新计数。连接由调用方传进来，和 `record` 同一个事务。"""
        db.execute("UPDATE relay_keys SET last_used=?, request_count=request_count+1 WHERE key_hash=?",
                   (now_ms(), digest))

    def record(self, db, digest, tokens):
        """把一次转发的用量落库。

        `digest` 是**账号的 user_id**（转发那条路已经解出来了，见
        `RelayStore.account_for`）——一个账号的多条 key 合起来算一笔账。

        和 `touch` 传同一个 `db`，由调用方的 `with store.connect(write=True)` 保证
        在同一个写事务里。分开写的话「计数涨了但用量没落」这种半截状态会在崩溃时留下。

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


def default_name(digest):
    """没给名字时用 uid 前 8 位。**不是从 key 本身截的**——那样等于把凭据的一部分
    显示在屏幕上，uid 是 hash，显示它才安全。"""
    return "relay-" + digest[:8]


def json_body(value):
    try:
        return json.loads(value)
    except (ValueError, TypeError):
        return None
