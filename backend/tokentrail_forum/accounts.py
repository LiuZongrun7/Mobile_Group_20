"""APP 自己的账号：注册、登录、会话。

## 为什么自己写账号，而不是继续借外部的

之前 APP 的登录打的是 `http://127.0.0.1:8000/api/login`——那是**服务器上另一个
项目**的服务。这样一来：

- 我们锁在别人的账号体系上，它的 schema、它的可用性、它的策略都影响我们；
- 「用户是谁」的答案在别人的库里，我们只能拿到一个转手的 `user_id`。

**这个 APP 里没有"团队"这回事**，有的只是 APP 自己的用户。所以账号归我们：
一张 `accounts` 表 + 三个接口，和那台机器上的其它服务彻底断开。

## 密码怎么存

**`hashlib.scrypt`，标准库自带，不引第三方。** 参数写在下面，每个用户一份随机盐。

不引 bcrypt/argon2 的理由：这两个要装 C 扩展，而这台服务器上多一个编译依赖
就多一个坏掉的方式；scrypt 是**内存硬**的（这是它设计的目标），够用。

**绝不存明文、绝不用裸 sha256。** 裸 sha256 的问题是快——一张显卡每秒几十亿次，
密码字典一撞就开。scrypt 慢几个数量级，而且吃内存，那种撞法不成立。

## 会话 token 怎么发

随机 32 字节，`tt_app_` 前缀（和 relay key 的 `tt_`、测试身份的 `tt_test_` 一个思路，
一眼能认出是哪一类）。**库里只存 sha256**——和 relay key 一样：
token 泄露等于别人能用你的账号，没有理由把原文留着。

## 和 relay key 的关系

**没有关系。** 这是两个独立的东西：

| | 账号 token | relay key |
|---|---|---|
| 谁的 | APP 用户的 | 中转那条路用的 |
| 干什么 | 登录论坛、发帖、读自己的用量 | 让 cc-switch 把请求打到我们这儿 |
| `user_id` | 就是账号的 id | 不再当身份用（见下） |

**`user_id` 只有账号这一个来源。** 之前我把 relay key 的 sha256 当身份用，
那是错的——relay key 是给信息收集那条路用的凭据，不该同时兼任"你是谁"。
"""
import hashlib
import hmac
import secrets
from datetime import datetime, timezone

from fastapi import HTTPException

from .store import now_ms

SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (
 user_id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
 password_hash TEXT NOT NULL, created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS account_sessions (
 token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES accounts(user_id),
 created INTEGER NOT NULL, expires INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS account_sessions_user ON account_sessions(user_id);
"""

TOKEN_PREFIX = "tt_app_"
SESSION_DAYS = 30

# scrypt 参数。`n` 是 CPU/内存代价，`r` 是块大小，`p` 是并行度。
# n=2**14 / r=8 约 16 MB、单次几十毫秒——登录时感觉不到，
# 但让"用显卡撞字典"变得不划算。**调大之前先量一下登录耗时。**
SCRYPT_N = 2 ** 14
SCRYPT_R = 8
SCRYPT_P = 1
SCRYPT_DKLEN = 32
SALT_BYTES = 16

MIN_PASSWORD = 8
MAX_PASSWORD = 200
MAX_USERNAME = 64


def hash_password(password, salt=None):
    """`scrypt(salt, password)` → `scrypt$n$r$p$salt$hash`（都是十六进制）。

    参数一起存进去，将来调大了还能验老密码——不存的话改了参数所有老用户
    都登不进来。
    """
    salt = salt or secrets.token_bytes(SALT_BYTES)
    derived = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=SCRYPT_N,
                             r=SCRYPT_R, p=SCRYPT_P, dklen=SCRYPT_DKLEN)
    return "$".join(["scrypt", str(SCRYPT_N), str(SCRYPT_R), str(SCRYPT_P),
                     salt.hex(), derived.hex()])


def verify_password(password, stored):
    """验密码。**用 `compare_digest` 而不是 `==`**：后者在第一个不同字节就返回，
    比较耗时随匹配前缀变化，理论上能被用来逐字节猜哈希。"""
    try:
        algorithm, n, r, p, salt, expected = stored.split("$")
        if algorithm != "scrypt":
            return False
        derived = hashlib.scrypt(password.encode("utf-8"), salt=bytes.fromhex(salt),
                                 n=int(n), r=int(r), p=int(p), dklen=len(expected) // 2)
        return hmac.compare_digest(derived.hex(), expected)
    except (ValueError, TypeError):
        # 存量记录坏了：**当成验证失败**，不是抛异常（抛出去会变成 500，
        # 而"这条记录坏了"不该让整个登录接口看起来挂了）。
        return False


def validate_registration(username, password):
    """返回给用户看的错误说明，或 None。"""
    name = (username or "").strip()
    if not 3 <= len(name) <= MAX_USERNAME:
        return f"Username must be 3–{MAX_USERNAME} characters"
    if any(character.isspace() for character in name):
        return "Username must not contain spaces"
    if not 8 <= len(password or "") <= MAX_PASSWORD:
        return f"Password must be {MIN_PASSWORD}–{MAX_PASSWORD} characters"
    return None


class Accounts:
    """账号与会话。挂在内核的 `Store` 上。"""

    def __init__(self, store):
        self.store = store
        with store.connect() as db:
            db.executescript(SCHEMA)

    def register(self, username, password):
        """注册。用户名撞了返回 None（调用方转 409，绝不覆盖）。"""
        problem = validate_registration(username, password)
        if problem:
            raise HTTPException(400, problem)
        name = username.strip()
        created = now_ms()
        with self.store.connect(write=True) as db:
            if db.execute("SELECT 1 FROM accounts WHERE username=?", (name,)).fetchone() is not None:
                return None
            # user_id 用随机 hex 而不是自增整数：自增会把「你是第几个注册的」
            # 泄出去，而且换库/迁移时容易撞。
            user_id = "u_" + secrets.token_hex(16)
            db.execute("INSERT INTO accounts VALUES(?,?,?,?)",
                       (user_id, name, hash_password(password), created))
        return {"userId": user_id, "username": name, "createdAtEpochMillis": created}

    def login(self, username, password):
        """验密码并签发会话。用户名不存在和密码错**返回同一个结果**——
        区分开等于送人一个「这个用户名存在」的探测接口。"""
        name = (username or "").strip()
        with self.store.connect() as db:
            row = db.execute("SELECT * FROM accounts WHERE username=?", (name,)).fetchone()
        if row is None:
            # 还是跑一次哈希：不然「不存在的用户名」会明显更快，能被计时分辨出来。
            hash_password(password or "")
            return None
        if not verify_password(password or "", row["password_hash"]):
            return None
        return self.issue(row["user_id"])

    def issue(self, user_id):
        """签发一个新会话。返回明文 token——**只在这一刻拿得到**。"""
        token = TOKEN_PREFIX + secrets.token_urlsafe(32)
        created = now_ms()
        expires = created + SESSION_DAYS * 86_400_000
        with self.store.connect(write=True) as db:
            # 顺手清掉过期的：不然这张表只涨不跌。
            db.execute("DELETE FROM account_sessions WHERE expires<=?", (created,))
            db.execute("INSERT INTO account_sessions VALUES(?,?,?,?)",
                       (hashlib.sha256(token.encode()).hexdigest(), user_id, created, expires))
        return {"token": token, "userId": user_id, "expiresAtEpochMillis": expires}

    def resolve(self, authorization):
        """token → `(user_id, username)`。无效返回 None。"""
        if not authorization or not authorization.startswith("Bearer "):
            return None
        token = authorization[7:].strip()
        if not token or len(token) > 200:
            return None
        with self.store.connect() as db:
            row = db.execute("""SELECT a.user_id, a.username, s.expires
                FROM account_sessions s JOIN accounts a ON a.user_id = s.user_id
                WHERE s.token_hash=?""", (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()
        if row is None or row["expires"] <= now_ms():
            return None
        return row["user_id"], row["username"]

    def account(self, user_id):
        with self.store.connect() as db:
            row = db.execute("SELECT user_id, username, created FROM accounts WHERE user_id=?",
                             (user_id,)).fetchone()
        return None if row is None else {
            "userId": row["user_id"], "username": row["username"],
            "createdAtEpochMillis": row["created"]}

    def logout(self, authorization):
        """撤销当前会话。已经失效的 token 也返回成功——退出是幂等的。"""
        if not authorization or not authorization.startswith("Bearer "):
            return
        token = authorization[7:].strip()
        if not token:
            return
        with self.store.connect(write=True) as db:
            db.execute("DELETE FROM account_sessions WHERE token_hash=?",
                       (hashlib.sha256(token.encode()).hexdigest(),))
