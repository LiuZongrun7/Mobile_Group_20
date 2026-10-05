"""APP 自己的账号：注册、登录、会话、**邮箱验证**。

## 为什么自己写账号，而不是继续借外部的

之前 APP 的登录打的是 `http://127.0.0.1:8000/api/login`——那是**服务器上另一个
项目**的服务。这样一来：

- 我们锁在别人的账号体系上，它的 schema、它的可用性、它的策略都影响我们；
- 「用户是谁」的答案在别人的库里，我们只能拿到一个转手的 `user_id`。

**这个 APP 里没有"团队"这回事**，有的只是 APP 自己的用户。所以账号归我们：
一张 `accounts` 表 + 几个接口，和那台机器上的其它服务彻底断开。

## 密码怎么存

**`hashlib.scrypt`，标准库自带，不引第三方。** 参数写在下面，每个用户一份随机盐。

不引 bcrypt/argon2 的理由：这两个要装 C 扩展，而这台服务器上多一个编译依赖
就多一个坏处；scrypt 是**内存硬**的（这是它设计的目标），够用。

**绝不存明文、绝不用裸 sha256。** 裸 sha256 的问题是快——一张显卡每秒几十亿次，
密码字典一撞就开。scrypt 慢几个数量级，而且吃内存，那种撞法不成立。

## 会话 token 怎么发

随机 32 字节，`tt_app_` 前缀（和测试身份的 `tt_test_` 一个思路，一眼能认出是哪一类）。
**库里只存 sha256**——token 泄露等于别人能用你的账号，没有理由把原文留着。

## 邮箱与验证（2026-10-05 加）

**身份是邮箱，昵称还是用户名。** 注册要填 `email` + `username` + `password`；
登录可以填邮箱也可以填用户名（老用户不用改习惯）。`username` 同时是论坛里的作者名，
所以不能省——邮箱前缀当昵称会让人没法改称呼。

**没验证邮箱的账号登不进来**，这就是"邮箱验证"唯一有意义的落点：不拦登录的话，
填谁的邮箱都能注册，验证码就只是个装饰。注册接口**先建号、再发信**，
发不出去就**把号删掉**再返回 503——反过来（留着号）会让那个邮箱被一个谁也
进不去的账号占住，用户重试只会看到"邮箱已被注册"，而他什么都没做成。

**历史账号一律放行。** 加这一列之前建的账号没有邮箱，让它们继续用用户名登录；
判据是 `created < EMAIL_EPOCH_MILLIS`，**不是"email 是不是空"**——
后者的话，任何绕过注册接口塞进来的一行都能免验证登录。

## 验证码怎么存

存 `sha256(email|purpose|code)`，**不存明文**：库被人看到时，验证码不该还能直接用。
老实说，6 位数字的哈希在离线暴力面前挡不住（10^6 次而已），所以**真正的防线是
另外三条**：10 分钟过期、一条码最多试 5 次、同一邮箱 60 秒才能重发一次。
哈希只是别让"看到库 = 拿到登录机会"这么直接。

## 和 relay key 的关系

**没有关系。** 账号 token 只是账号的，中转那套已经删了（2026-09-30）。
`user_id` 只有账号这一个来源。
"""
import hashlib
import hmac
import secrets
import sqlite3

from fastapi import HTTPException

from .mailer import MailNotConfigured, Mailer
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

# 邮箱相关的表和索引**单独一段**：老库升级时要先把列加上，再建索引，
# 顺序反了会在"列还不存在"的时候去建索引，直接报错（见 `_upgrade`）。
EMAIL_SCHEMA = """
CREATE UNIQUE INDEX IF NOT EXISTS accounts_email ON accounts(email) WHERE email IS NOT NULL;
CREATE TABLE IF NOT EXISTS email_codes (
 email TEXT NOT NULL, purpose TEXT NOT NULL, code_hash TEXT NOT NULL,
 user_id TEXT, created INTEGER NOT NULL, expires INTEGER NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0, consumed INTEGER
);
CREATE INDEX IF NOT EXISTS email_codes_lookup ON email_codes(email, purpose, created);
"""

# 后加的三列。`ALTER TABLE ADD COLUMN` 在 SQLite 里对已有行是安全的：
# 老行拿到 NULL / 默认值，不会重写整张表。
EMAIL_COLUMNS = (
    ("email", "TEXT"),
    ("email_verified", "INTEGER NOT NULL DEFAULT 0"),
    ("email_verified_at", "INTEGER"),
)

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
MAX_EMAIL = 254

# 验证码的几个上限。三条一起看：过期（时间）、尝试次数（人）、重发频率（成本）。
# 验证码的用途。**分开存是有意的**：注册验证码不能拿去改密码，
# 改密码的验证码也不能拿去验证邮箱——两种权限不一样，混用等于悄悄放宽。
PURPOSE_REGISTER = "register"
PURPOSE_RESET = "reset"

CODE_DIGITS = 6
CODE_TTL_MINUTES = 10
CODE_RESEND_SECONDS = 60
CODE_MAX_PER_HOUR = 5
CODE_MAX_ATTEMPTS = 5

# **全局**每小时最多发几封验证码邮件（所有邮箱加起来）。
#
# 为什么需要它：每邮箱的限流挡不住"拿一万个邮箱各要一封"——那不会让我们被刷爆，
# 但会**烧掉发信账号的日配额**（163 免费邮箱一天就那么几十上百封），
# 于是真正要注册的人一封也收不到。这是把"共享的一个发信通道"当成稀缺资源来护。
#
# 60 这个数是估的：正常使用一天也到不了，被刷时能在烧完配额之前刹住。
# 代价是极端情况下可能短暂挡住正常用户——比起"所有人都收不到信"，这个代价更小。
# （**不是**按 IP 限流：那要加一列、还要信任 `x-real-ip`，而这里的目的只是保配额。）
GLOBAL_CODES_PER_HOUR = 60

# 邮箱验证上线的时间点（2026-10-05 00:00 UTC+8）。
# 比它早的账号是"历史账号"：那时还没有 email 这一列，允许继续用用户名登录。
EMAIL_EPOCH_MILLIS = 1_791_129_600_000


def problem(status, code, message, headers=None):
    """带**机器可读 code** 的 HTTP 错误。

    `app.py` 的异常处理器认这种 dict 形态，原样放进 `{"code","message"}`；
    传字符串的老写法仍然是 `HTTP_<状态码>`，两者并存。
    客户端按 `code` 决定显示什么、下一步做什么（"去验证" / "重发" / "等等再试"），
    只按状态码的话 400 里面至少有四种完全不同的处境。
    """
    return HTTPException(status, {"code": code, "message": message}, headers=headers)


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


def validate_password(password):
    """密码规则。返回 `(机器可读的 code, 给人看的说明)`；没问题返回 `(None, None)`。

    **单独一个函数**，因为注册、重置、改密码三条路都要用它：三条各写一遍的话，
    迟早有一条放宽（或者收紧）而另外两条不知道。
    """
    if not MIN_PASSWORD <= len(password or "") <= MAX_PASSWORD:
        return "PASSWORD_INVALID", f"Password must be {MIN_PASSWORD}–{MAX_PASSWORD} characters"
    return None, None


def validate_registration(username, password):
    """返回 `(机器可读的 code, 给人看的说明)`；没问题返回 `(None, None)`。

    分成两截是因为客户端要按 code 决定**在哪一行标红**，
    而按说明文字去猜（"Username must not..."）是在把文案当接口用。
    """
    name = (username or "").strip()
    if not 3 <= len(name) <= MAX_USERNAME:
        return "USERNAME_INVALID", f"Username must be 3–{MAX_USERNAME} characters"
    if any(character.isspace() for character in name):
        return "USERNAME_INVALID", "Username must not contain spaces"
    return validate_password(password)


def normalize_email(value):
    """统一小写去空格。

    **故意把本地部分也一起小写**：RFC 说 `@` 前面是大小写敏感的，但现实里
    没有哪家邮件服务商这么用，而"我注册时写的是 `Alice@`，登录写 `alice@`"
    会变成一个非常难解释的登录失败。全小写是这里唯一的规矩。
    """
    return (value or "").strip().lower()


def validate_email(value):
    """返回错误说明，或 None。

    **不做 RFC 5322 全解析**：那个正则几百行，而且仍然会放过坏地址
    （它管的是"语法像不像"，不是"这个邮箱存在吗"）。这里只挡明显的手滑：
    少了 `@`、域名没有点、有空格、超长、点号连在一起。

    真正的验证手段只有一个，就是**往那个地址发一封信**。
    """
    text = normalize_email(value)
    if not text:
        return "Email is required"
    if len(text) > MAX_EMAIL:
        return f"Email must be at most {MAX_EMAIL} characters"
    if any(character.isspace() for character in text):
        return "Email must not contain spaces"
    if text.count("@") != 1:
        return "Email must contain exactly one @"
    local, _, domain = text.partition("@")
    if not local or len(local) > 64:
        return "Email is not valid"
    if local.startswith(".") or local.endswith(".") or ".." in local:
        return "Email is not valid"
    if "." not in domain or domain.startswith(".") or domain.endswith("."):
        return "Email is not valid"
    if ".." in domain or any(not label for label in domain.split(".")):
        return "Email is not valid"
    return None


def code_digest(email, purpose, code):
    """验证码的存储形态。见模块注释：它挡的是"看到库就能用"，不是离线爆破。"""
    return hashlib.sha256(f"{email}|{purpose}|{code}".encode("utf-8")).hexdigest()


class Accounts:
    """账号与会话。挂在内核的 `Store` 上。"""

    def __init__(self, store, mailer=None):
        self.store = store
        # 不传就是"没配发信"：`Mailer()` 的 host/user/password 都是空，
        # 注册会在发信那一步明确失败，而不是静默建一个验证不了的号。
        self.mailer = mailer or Mailer()
        with store.connect() as db:
            db.executescript(SCHEMA)
            self._upgrade(db)

    @staticmethod
    def _upgrade(db):
        """把老库补成新形状。**幂等**，每次启动都跑。

        顺序不能反：先 `ALTER TABLE` 加列，再建用到这一列的索引。
        反过来的话，第一次在旧库上启动会因为"没有 email 这一列"而失败。
        """
        existing = {row[1] for row in db.execute("PRAGMA table_info(accounts)")}
        for name, declaration in EMAIL_COLUMNS:
            if name not in existing:
                db.execute(f"ALTER TABLE accounts ADD COLUMN {name} {declaration}")
        db.executescript(EMAIL_SCHEMA)

    # ---- 注册 / 验证 ----------------------------------------------------

    def register(self, username, password, email=None):
        """注册并**立刻发一封验证码邮件**。发不出去就把账号删掉再报错。

        返回不含 token 的账号信息（和以前一样：注册不自动登录）。
        """
        name = (username or "").strip()
        code, complaint = validate_registration(name, password)
        if complaint:
            raise problem(400, code, complaint)

        complaint = validate_email(email)
        if complaint:
            address = normalize_email(email)
            raise problem(400, "EMAIL_REQUIRED" if not address else "EMAIL_INVALID", complaint)
        address = normalize_email(email)

        created = now_ms()
        with self.store.connect(write=True) as db:
            if db.execute("SELECT 1 FROM accounts WHERE username=?", (name,)).fetchone() is not None:
                raise problem(409, "USERNAME_TAKEN", "That username is already taken")
            if db.execute("SELECT 1 FROM accounts WHERE email=?", (address,)).fetchone() is not None:
                raise problem(409, "EMAIL_TAKEN", "That email is already registered")
            # user_id 用随机 hex 而不是自增整数：自增会把「你是第几个注册的」
            # 泄出去，而且换库/迁移时容易撞。
            user_id = "u_" + secrets.token_hex(16)
            try:
                db.execute("""INSERT INTO accounts(user_id, username, password_hash, created,
                              email, email_verified, email_verified_at) VALUES(?,?,?,?,?,0,NULL)""",
                           (user_id, name, hash_password(password), created, address))
            except sqlite3.IntegrityError as conflict:
                # 上面那两个 SELECT 只是"早点给出好一点的提示"，**唯一约束才是判据**：
                # 两个请求同时注册同一个邮箱时，先到的那个已经提交，后到的会撞索引。
                # 不接住它就变成 500，而用户看到的是"服务器错误"——其实只是重名。
                raise problem(409, "USERNAME_TAKEN" if "username" in str(conflict)
                              else "EMAIL_TAKEN",
                              "That username or email is already registered") from conflict

        dev_code = None
        try:
            dev_code = self.send_verification(address, user_id=user_id)
        except MailNotConfigured as not_configured:
            self.delete_account(user_id)
            raise problem(503, "MAIL_NOT_CONFIGURED",
                          "This server cannot send email right now, so the account was not created"
                          ) from not_configured
        except Exception as failure:                      # noqa: BLE001 —— 见下面的注释
            # **任何**发信失败都要回滚账号：网络抖一下、授权码过期、邮箱写错了域名，
            # 结果都是"这个号收不到验证码"。留着它，用户重试只会看到"邮箱已被注册"。
            # 这里刻意捕宽：smtplib 抛的东西（SMTPException / OSError / ssl 错误）
            # 不值得在业务层逐个列举，而漏掉一种的代价是留下一个死账号。
            self.delete_account(user_id)
            raise problem(503, "MAIL_FAILED",
                          "Could not send the verification email, so the account was not created"
                          ) from failure

        body = {"userId": user_id, "username": name, "email": address,
                "emailVerified": False, "createdAtEpochMillis": created}
        if dev_code is not None:
            # 只有测试区（`MODELPILOT_MAIL_DEV_ECHO`）会走到这里：不真发信时，
            # 把码给出来，`curl` 联调才走得下去。
            body["devCode"] = dev_code
        return body

    def delete_account(self, user_id):
        """回滚一个没发出去验证信的账号（连带它的验证码和会话）。"""
        with self.store.connect(write=True) as db:
            db.execute("DELETE FROM email_codes WHERE user_id=?", (user_id,))
            db.execute("DELETE FROM account_sessions WHERE user_id=?", (user_id,))
            db.execute("DELETE FROM accounts WHERE user_id=?", (user_id,))

    def send_verification(self, email, purpose=PURPOSE_REGISTER, user_id=None, deliver=True):
        """生成一条验证码，必要时发出去。返回 `dev_code`（没开回显、或没发信时是 None）。

        <p>`deliver=False`：**照样生成、照样记限流的账，但不发信**。用在"这个邮箱
        没注册过"的时候——不给陌生地址发邮件（否则这个接口就成了替人发信的通道），
        但响应和限流必须和"注册过"那条路**一模一样**，不然它就是一个
        "这个邮箱注册过吗"的探测接口。返回值也一起按 deliver 走：
        `devCode` 只在真发了的时候出现，否则响应的形状就泄露了答案。
        """
        address = normalize_email(email)
        current = now_ms()
        with self.store.connect(write=True) as db:
            latest = db.execute("""SELECT created FROM email_codes
                WHERE email=? AND purpose=? ORDER BY created DESC LIMIT 1""",
                                (address, purpose)).fetchone()
            if latest is not None:
                waited = current - latest["created"]
                if waited < CODE_RESEND_SECONDS * 1000:
                    raise problem(429, "RATE_LIMIT", "Please wait before requesting another code",
                                  headers={"Retry-After": str(CODE_RESEND_SECONDS)})
            sent_last_hour = db.execute("""SELECT COUNT(*) FROM email_codes
                WHERE email=? AND created>?""", (address, current - 3_600_000)).fetchone()[0]
            if sent_last_hour >= CODE_MAX_PER_HOUR:
                # 换个说法防刷：每小时 5 封是上限，不是"再等等就好了"。
                raise problem(429, "RATE_LIMIT", "Too many codes requested for this email",
                              headers={"Retry-After": "3600"})
            # 全局上限：见 GLOBAL_CODES_PER_HOUR 的注释（护的是发信账号的日配额）。
            # 数的是**这张表的行数**，包含"没注册过所以没真发"的那些——它们同样说明
            # 有人在批量请求，而这时候刹住正是我们想要的。
            sent_globally = db.execute("SELECT COUNT(*) FROM email_codes WHERE created>?",
                                       (current - 3_600_000,)).fetchone()[0]
            if sent_globally >= GLOBAL_CODES_PER_HOUR:
                raise problem(429, "RATE_LIMIT",
                              "Too many codes requested right now, please try again later",
                              headers={"Retry-After": "600"})
            code = "".join(secrets.choice("0123456789") for _ in range(CODE_DIGITS))
            db.execute("""INSERT INTO email_codes(email, purpose, code_hash, user_id,
                          created, expires, attempts, consumed) VALUES(?,?,?,?,?,?,0,NULL)""",
                       (address, purpose, code_digest(address, purpose, code), user_id,
                        current, current + CODE_TTL_MINUTES * 60_000))

        if not deliver:
            return None
        # 发信放在事务**外面**：SMTP 是一次网络往返（慢的时候几十秒），
        # 而这段时间里不该一直攥着数据库的写锁。
        self.mailer.send_code(address, code, CODE_TTL_MINUTES, purpose)
        return code if self.mailer.dev_echo else None

    def _consume_code(self, db, address, purpose, supplied, current):
        """在事务里核销一条验证码。返回 `(failure, account_row)`。

        <p><b>失败时不抛异常</b>：调用方出了事务再抛。原因是
        `Store.connect(write=True)` 遇到异常会 rollback，而"记一次失败尝试再抛"
        会把那次计数一起回滚掉——表现就是一条码可以无限试（2026-10-05 真机上踩过）。

        <p>`purpose` 要传对：注册码和重置码**不能互相顶用**。
        """
        row = db.execute("""SELECT * FROM email_codes
            WHERE email=? AND purpose=? AND consumed IS NULL
            ORDER BY created DESC LIMIT 1""", (address, purpose)).fetchone()
        if row is None:
            return problem(400, "CODE_INVALID", "That code is not correct"), None
        if row["attempts"] >= CODE_MAX_ATTEMPTS:
            return problem(429, "CODE_ATTEMPTS",
                           "Too many attempts for this code, request a new one",
                           headers={"Retry-After": str(CODE_RESEND_SECONDS)}), None
        if row["expires"] <= current:
            return problem(400, "CODE_EXPIRED", "That code has expired, request a new one"), None
        if not hmac.compare_digest(row["code_hash"], code_digest(address, purpose, supplied)):
            # 记一次失败：**这条计数是"一条码最多试 5 次"的全部依据**。
            db.execute("UPDATE email_codes SET attempts=attempts+1 WHERE code_hash=?",
                       (row["code_hash"],))
            return problem(400, "CODE_INVALID", "That code is not correct"), None
        db.execute("UPDATE email_codes SET consumed=? WHERE code_hash=?",
                   (current, row["code_hash"]))
        account = db.execute("SELECT * FROM accounts WHERE email=?", (address,)).fetchone()
        if account is None:
            # 码是对的，但没有账号可用（注册没成功、或邮箱根本没注册过）。
            # **仍然按"码不对"回**：区别对待就是一个探测接口。
            return problem(400, "CODE_INVALID", "That code is not correct"), None
        return None, account

    def resend_verification(self, email):
        """重发**注册**验证码。

        <p>没注册过的邮箱：照样记一行限流、返回一模一样的结果，但**不发信**——
        否则这个接口既能探"这个邮箱注册过吗"，又能被当成给陌生地址发邮件的通道。
        （2026-10-05 改：原来是不管有没有账号都发。改成不发之后，
        响应形状和限流一点没变，探测不出来。）
        """
        address = normalize_email(email)
        with self.store.connect() as db:
            row = db.execute("SELECT user_id FROM accounts WHERE email=?", (address,)).fetchone()
        if row is None:
            self.send_verification(address, purpose=PURPOSE_REGISTER, deliver=False)
            return {"sent": True}
        code = self.send_verification(address, purpose=PURPOSE_REGISTER, user_id=row["user_id"])
        body = {"sent": True}
        if code is not None:
            body["devCode"] = code
        return body

    def verify_email(self, email, code):
        """核销一条验证码，把账号标成已验证。

        失败分三种，**客户端要能分开**：码不对（可以再试）、码过期（重发）、
        试太多次（重发）。邮箱不存在时一律按"码不对"回——区分开就等于送人一个
        「这个邮箱注册过吗」的探测接口。

        **这段的结构是被一个坑逼出来的**：`Store.connect(write=True)` 在异常时
        会 `rollback`，所以"记一次失败尝试之后再抛 HTTPException"会把那次计数一起
        回滚掉——表现就是"一条码可以无限试"。现在的写法是**在事务里只记结果、
        出了事务再抛**：要么整个成功、要么失败计数确实落库。
        """
        address = normalize_email(email)
        supplied = (code or "").strip()
        current = now_ms()
        failure = None
        result = None
        with self.store.connect(write=True) as db:
            failure, account = self._consume_code(db, address, PURPOSE_REGISTER, supplied, current)
            if failure is None:
                db.execute("""UPDATE accounts SET email_verified=1, email_verified_at=?
                    WHERE user_id=?""", (current, account["user_id"]))
                result = {"email": address, "emailVerified": True,
                          "verifiedAtEpochMillis": current}
        if failure is not None:
            raise failure
        return result

    # ---- 密码：找回 / 修改 ----------------------------------------------

    def request_password_reset(self, email):
        """忘记密码：给这个邮箱发一条**重置码**。

        <p>三条口径，每条都是防着某一类事故：

        1. **邮箱没注册过就不发信**（`deliver=False`），但限流的账照记、响应照旧——
           否则这个接口就变成一个"这个邮箱注册过吗"的探测接口，或者替人给陌生地址
           发邮件的通道。
        2. **服务器发不了信时，所有请求返回同一句 503**（在查账号**之前**判）。
           放到查完账号之后再判的话，"注册过的邮箱 → 503、没注册的 → 200"
           又是一个探测接口。
        3. 真发信失败（SMTP 抽风）仍然如实报 503 `MAIL_FAILED`：这时候那个邮箱多半
           真的存在，理论上漏了一点信息，但**让用户干等一封永远不会来的邮件更糟**。
           这条取舍是有意的，写在这里免得以后有人以为漏了。
        """
        address = normalize_email(email)
        complaint = validate_email(address)
        if complaint:
            raise problem(400, "EMAIL_REQUIRED" if not address else "EMAIL_INVALID", complaint)
        if not self.mailer.configured and not self.mailer.dev_echo:
            raise problem(503, "MAIL_NOT_CONFIGURED",
                          "This server cannot send email right now")
        with self.store.connect() as db:
            row = db.execute("SELECT user_id FROM accounts WHERE email=?", (address,)).fetchone()
        if row is None:
            self.send_verification(address, purpose=PURPOSE_RESET, deliver=False)
            return {"sent": True}
        code = self.send_verification(address, purpose=PURPOSE_RESET,
                                      user_id=row["user_id"])
        body = {"sent": True}
        if code is not None:
            body["devCode"] = code
        return body

    def reset_password(self, email, code, password):
        """用重置码改密码。**成功之后所有会话都失效**（包括当前这个）。

        <p>为什么要踢掉所有会话：忘记密码的常见原因之一就是"号被人登了"。
        改完密码却把入侵者的会话留着，等于白改。代价是用户要重新登录一次，
        而这一次登录用新密码就行——客户端那边紧接着自动登一次。

        <p>顺带把邮箱标成已验证：能收到这封信本身就证明了邮箱是他的。
        """
        address = normalize_email(email)
        code_name, complaint = validate_password(password)
        if complaint:
            raise problem(400, code_name, complaint)
        supplied = (code or "").strip()
        current = now_ms()
        failure = None
        result = None
        with self.store.connect(write=True) as db:
            failure, account = self._consume_code(db, address, PURPOSE_RESET, supplied, current)
            if failure is None:
                db.execute("""UPDATE accounts SET password_hash=?, email_verified=1,
                    email_verified_at=? WHERE user_id=?""",
                           (hash_password(password), current, account["user_id"]))
                revoked = db.execute("DELETE FROM account_sessions WHERE user_id=?",
                                     (account["user_id"],)).rowcount
                result = {"passwordChanged": True, "sessionsRevoked": max(0, revoked)}
        if failure is not None:
            raise failure
        return result

    def change_password(self, user_id, current_password, new_password, authorization=None):
        """已登录用户改密码。**只踢掉其它设备**，当前这个会话留着。

        <p>和 {@link #reset_password} 的区别就在这一点上：找回密码是"我进不去了"，
        所以全踢；改密码是"我进来了，顺手换一个"，把当前设备也踢掉只会让人骂人。
        """
        code_name, complaint = validate_password(new_password)
        if complaint:
            raise problem(400, code_name, complaint)
        with self.store.connect(write=True) as db:
            row = db.execute("SELECT * FROM accounts WHERE user_id=?", (user_id,)).fetchone()
            if row is None:
                raise problem(401, "CREDENTIALS", "Sign in required")
            if not verify_password(current_password or "", row["password_hash"]):
                # 401 和"会话过期"同码：客户端那两种情况要做的事一样（重新登录）。
                raise problem(401, "CREDENTIALS", "Current password is not correct")
            keep = None
            if authorization and authorization.startswith("Bearer "):
                keep = hashlib.sha256(authorization[7:].strip().encode()).hexdigest()
            db.execute("UPDATE accounts SET password_hash=? WHERE user_id=?",
                       (hash_password(new_password), user_id))
            if keep:
                revoked = db.execute("""DELETE FROM account_sessions
                    WHERE user_id=? AND token_hash<>?""", (user_id, keep)).rowcount
            else:
                revoked = db.execute("DELETE FROM account_sessions WHERE user_id=?",
                                     (user_id,)).rowcount
        return {"passwordChanged": True, "otherSessionsRevoked": max(0, revoked)}

    # ---- 登录 / 会话 ----------------------------------------------------

    def login(self, identifier, password):
        """邮箱或用户名 + 密码 → 会话。

        **三种失败要分得开**（这是这一版和上一版最大的区别）：
        账号不存在或密码错 → None（调用方转 401，两者必须长得一模一样）；
        邮箱没验证 → 403 `EMAIL_UNVERIFIED`（客户端要跳去验证界面）。
        """
        key = (identifier or "").strip()
        if not key:
            hash_password(password or "")            # 别让空用户名明显更快
            return None
        with self.store.connect() as db:
            if "@" in key:
                row = db.execute("SELECT * FROM accounts WHERE email=?",
                                 (normalize_email(key),)).fetchone()
            else:
                row = db.execute("SELECT * FROM accounts WHERE username=?", (key,)).fetchone()
        if row is None:
            # 还是跑一次哈希：不然「不存在的用户名」会明显更快，能被计时分辨出来。
            hash_password(password or "")
            return None
        if not verify_password(password or "", row["password_hash"]):
            return None

        email = row["email"]
        if email is None:
            # 没有邮箱的账号只有一种合法的：加这一列之前建的（`EMAIL_EPOCH_MILLIS`）。
            if row["created"] >= EMAIL_EPOCH_MILLIS:
                raise problem(403, "EMAIL_REQUIRED", "This account has no email address on file")
        elif not row["email_verified"]:
            raise problem(403, "EMAIL_UNVERIFIED",
                          "Verify your email address before signing in")
        # 会话 + 账号字段一起回：客户端拿一次响应就够，不用再补一次 `/account/me`。
        issued = self.issue(row["user_id"])
        issued.update({"userId": row["user_id"], "username": row["username"],
                       "email": email, "emailVerified": bool(row["email_verified"])})
        return issued

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
            row = db.execute("""SELECT user_id, username, created, email, email_verified
                FROM accounts WHERE user_id=?""", (user_id,)).fetchone()
        return None if row is None else {
            "userId": row["user_id"], "username": row["username"],
            "createdAtEpochMillis": row["created"],
            "email": row["email"], "emailVerified": bool(row["email_verified"])}

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
