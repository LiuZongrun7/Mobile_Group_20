"""邮箱注册与验证码：**这一版账号系统的核心判据**。

盯住的是几件"写错了也不会报错、只是防线悄悄没了"的事：

1. **没验证邮箱就登不进来**——不拦的话，填谁的邮箱都能注册，验证码只是装饰；
2. **发信失败必须回滚账号**——不然那个邮箱被一个谁也进不去的号占住，
   用户重试只会看到"邮箱已被注册"，而他什么都没做成；
3. **重发和验证都不许泄露"这个邮箱注册过没有"**；
4. **码本身不许落明文、不许无限试**（过期 + 5 次上限 + 60 秒重发冷却）；
5. **历史账号照旧能登录**，而且"能登录"的判据是**建号时间**，
   不是"email 是不是空"——后者会让任何绕过注册接口塞进来的一行免验证。
"""
import hashlib
import sqlite3

import pytest
from fastapi.testclient import TestClient

from helpers import RecordingMailer, create_app_for_tests, latest_code
from modelpilot_forum.app import create_app
from modelpilot_forum.accounts import Accounts, EMAIL_EPOCH_MILLIS, code_digest
from modelpilot_forum.app import Settings
from modelpilot_forum.store import Store, now_ms

PASSWORD = "correct horse battery"
ADDRESS = "alice@example.com"


class FailingMailer:
    """能配、但发不出去（网络抖、授权码过期）。接口和真发信口一样。"""

    dev_echo = False
    configured = True

    def send_code(self, email, code, minutes, purpose="register"):
        raise OSError("smtp is down")


class SilentMailer:
    """什么都不做，也不报错——用来量"注册到底有没有经过发信这一步"。"""

    dev_echo = False
    configured = True

    def __init__(self):
        self.calls = []

    def send_code(self, email, code, minutes, purpose="register"):
        self.calls.append((email, code, purpose))


def app_for(tmp_path, mailer=None, **overrides):
    settings = Settings(str(tmp_path), "https://forum.example", **overrides)
    return create_app_for_tests(settings, mailer=mailer or RecordingMailer())


def register(api, email=ADDRESS, username="alice", password=PASSWORD):
    return api.post("/api/account/register",
                    json={"email": email, "username": username, "password": password})


def login(api, identifier=ADDRESS, password=PASSWORD):
    return api.post("/api/account/login", json={"identifier": identifier, "password": password})


def verify(api, email=ADDRESS, code=None):
    return api.post("/api/account/verify", json={"email": email, "code": code or latest_code(email)})


def error_code(response):
    return response.json().get("code")


def sign_up_verified(api, email=ADDRESS, username="alice", password=PASSWORD):
    assert register(api, email, username, password).status_code == 201
    assert verify(api, email).status_code == 200
    return login(api, email, password)


# ---- 注册要邮箱，而且要真的发出去 ----------------------------------------

def test_register_without_an_email_is_refused(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        missing = api.post("/api/account/register", json={"username": "alice", "password": PASSWORD})
        assert missing.status_code == 400
        assert error_code(missing) == "EMAIL_REQUIRED"


@pytest.mark.parametrize("bad", [
    "alice",                # 没有 @
    "alice@@example.com",   # 两个 @
    "@example.com",         # 没有本地部分
    "alice@example",        # 域名没有点
    "alice@.com",           # 域名以点开头
    "alice@example..com",   # 点点相连
    "ali ce@example.com",   # 空格
    "alice@" + "x" * 260,   # 太长
    ".alice@example.com",   # 本地部分以点开头
])
def test_obviously_wrong_emails_are_refused(tmp_path, bad):
    with TestClient(app_for(tmp_path)) as api:
        response = register(api, email=bad)
        assert response.status_code == 400, bad
        assert error_code(response) == "EMAIL_INVALID"


def test_register_sends_exactly_one_code_and_no_token(tmp_path):
    mailer = SilentMailer()
    with TestClient(app_for(tmp_path, mailer=mailer)) as api:
        response = register(api)
        assert response.status_code == 201, response.text
        body = response.json()
        assert body["email"] == ADDRESS
        assert body["emailVerified"] is False
        # 注册**不自动登录**（和以前一样）：被脚本刷时，自动登录等于送一堆会话
        assert "token" not in body
        assert [address for address, _, _ in mailer.calls] == [ADDRESS]
        assert len(mailer.calls[0][1]) == 6 and mailer.calls[0][1].isdigit()
        assert mailer.calls[0][2] == "register", "注册那封信的用途必须是 register"


def test_the_code_is_not_stored_in_plaintext(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        code = latest_code(ADDRESS)
        path = tmp_path / "modelpilot.sqlite3"
    blob = path.read_bytes()
    assert code.encode() not in blob, "验证码不该明文落在库里"
    with sqlite3.connect(path) as db:
        stored = db.execute("SELECT code_hash FROM email_codes").fetchone()[0]
    assert stored == code_digest(ADDRESS, "register", code)
    assert len(stored) == 64


def test_a_taken_email_and_a_taken_username_are_told_apart(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        assert register(api).status_code == 201
        same_username = register(api, email="other@example.com")
        same_email = register(api, email=ADDRESS, username="alice2")
        assert same_username.status_code == same_email.status_code == 409
        assert error_code(same_username) == "USERNAME_TAKEN"
        assert error_code(same_email) == "EMAIL_TAKEN"


def test_email_case_does_not_create_a_second_account(tmp_path):
    """`Alice@` 和 `alice@` 是同一个人——不然会出现两个只差大小写的账号。"""
    with TestClient(app_for(tmp_path)) as api:
        assert register(api).status_code == 201
        assert register(api, email="Alice@Example.COM", username="alice2").status_code == 409


# ---- 验证之前登不进来 ----------------------------------------------------

def test_login_before_verification_is_refused_with_its_own_code(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        refused = login(api)
        assert refused.status_code == 403
        assert error_code(refused) == "EMAIL_UNVERIFIED"
        # 码还没用掉，验证之后立刻能登
        assert verify(api).status_code == 200
        assert login(api).status_code == 200


def test_the_wrong_password_still_looks_identical_to_an_unknown_account(tmp_path):
    """**验证那道闸门不许把"这个账号存在吗"漏出去。**
    密码不对时先回 401（和不存在一样），而不是先抱怨"你没验证邮箱"。"""
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        wrong = login(api, password="not the password")
        unknown = login(api, identifier="nobody@example.com")
        assert wrong.status_code == unknown.status_code == 401
        assert wrong.json() == unknown.json()


def test_verification_unlocks_login_by_both_email_and_username(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        body = sign_up_verified(api).json()
        assert body["token"].startswith("tt_app_")
        assert body["email"] == ADDRESS and body["emailVerified"] is True
        by_username = login(api, identifier="alice")
        assert by_username.status_code == 200
        by_upper_email = login(api, identifier="ALICE@example.com")
        assert by_upper_email.status_code == 200
        me = api.get("/api/account/me", headers={"Authorization": "Bearer " + body["token"]})
        assert me.json()["emailVerified"] is True
        assert me.json()["email"] == ADDRESS


# ---- 验证码本身 ----------------------------------------------------------

def test_a_wrong_code_increments_attempts_and_then_asks_for_a_resend(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        for attempt in range(5):
            wrong = verify(api, code="000000" if latest_code(ADDRESS) != "000000" else "111111")
            assert wrong.status_code == 400, wrong.text
            assert error_code(wrong) == "CODE_INVALID"
        # 第 6 次不再让你试：这时候正确的码也不收，必须重发
        exhausted = verify(api)
        assert exhausted.status_code == 429
        assert error_code(exhausted) == "CODE_ATTEMPTS"
        with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
            attempts = db.execute("SELECT attempts FROM email_codes").fetchone()[0]
        assert attempts == 5


def test_an_expired_code_is_told_apart_from_a_wrong_one(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        # 把过期时间拨到过去：**测的是"过期"这条判据**，不是等 10 分钟
        with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
            db.execute("UPDATE email_codes SET expires=?", (now_ms() - 1000,))
            db.commit()
        expired = verify(api)
        assert expired.status_code == 400
        assert error_code(expired) == "CODE_EXPIRED"


def test_a_resend_replaces_the_old_code(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        first = latest_code(ADDRESS)
        # 撞 60 秒冷却：**这是生产行为**，测试不该绕过它
        too_soon = api.post("/api/account/verify/resend", json={"email": ADDRESS})
        assert too_soon.status_code == 429
        assert error_code(too_soon) == "RATE_LIMIT"
        assert too_soon.headers.get("retry-after") == "60"
        # 把上一封的时间往回拨，模拟"过了一分钟"
        with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
            db.execute("UPDATE email_codes SET created=created-120000")
            db.commit()
        again = api.post("/api/account/verify/resend", json={"email": ADDRESS})
        assert again.status_code == 200
        second = latest_code(ADDRESS)
        assert second != first
        # 旧码作废：只有最新那条能被核销
        assert error_code(verify(api, code=first)) == "CODE_INVALID"
        assert verify(api, code=second).status_code == 200


def test_at_most_five_codes_an_hour(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        for _ in range(4):
            with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
                db.execute("UPDATE email_codes SET created=created-120000")
                db.commit()
            assert api.post("/api/account/verify/resend",
                            json={"email": ADDRESS}).status_code == 200
        with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
            db.execute("UPDATE email_codes SET created=created-120000")
            db.commit()
        capped = api.post("/api/account/verify/resend", json={"email": ADDRESS})
        assert capped.status_code == 429
        assert error_code(capped) == "RATE_LIMIT"


# ---- 不许泄露"这个邮箱注册过没有" ----------------------------------------

def test_resend_looks_the_same_for_a_known_and_an_unknown_email(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
            db.execute("UPDATE email_codes SET created=created-120000")
            db.commit()
        known = api.post("/api/account/verify/resend", json={"email": ADDRESS})
        unknown = api.post("/api/account/verify/resend", json={"email": "nobody@example.com"})
        assert known.status_code == unknown.status_code == 200
        assert known.json() == unknown.json() == {"sent": True}


def test_verify_for_an_unknown_email_says_the_code_is_wrong(tmp_path):
    """不能回"这个邮箱没注册"——那就是一个探测接口。"""
    with TestClient(app_for(tmp_path)) as api:
        response = api.post("/api/account/verify", json={"email": "nobody@example.com", "code": "123456"})
        assert response.status_code == 400
        assert error_code(response) == "CODE_INVALID"


# ---- 发信失败：账号不许留下 ----------------------------------------------

def test_registration_without_a_mail_service_creates_nothing(tmp_path):
    """没配发信 → 503，而且**那个邮箱仍然可以注册**（没被死号占住）。

    这里**不注入**记录型发信口：要测的就是"真的没配发信服务"这条路。
    """
    settings = Settings(str(tmp_path), "https://forum.example")     # 没有 SMTP 配置
    with TestClient(create_app(settings)) as api:
        refused = register(api)
        assert refused.status_code == 503
        assert error_code(refused) == "MAIL_NOT_CONFIGURED"
        assert login(api).status_code == 401
        # 邮箱和用户名都还空着
        assert register(api, email="other@example.com", username="alice").status_code == 503
    with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
        assert db.execute("SELECT COUNT(*) FROM accounts").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM email_codes").fetchone()[0] == 0


def test_a_failed_send_rolls_the_account_back(tmp_path):
    with TestClient(app_for(tmp_path, mailer=FailingMailer())) as api:
        refused = register(api)
        assert refused.status_code == 503
        assert error_code(refused) == "MAIL_FAILED"
        assert login(api).status_code == 401
    with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
        assert db.execute("SELECT COUNT(*) FROM accounts").fetchone()[0] == 0


def test_health_says_whether_mail_is_configured(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        body = api.get("/health").json()
        assert body["mailConfigured"] is False
        assert body["mailDevEcho"] is False
    configured = Settings(str(tmp_path), "https://forum.example",
                          mail_host="smtp.example.com", mail_user="robot@example.com",
                          mail_password="secret")
    with TestClient(create_app_for_tests(configured)) as api:
        body = api.get("/health").json()
        assert body["mailConfigured"] is True
        # **健康检查里永远不出现凭据**
        assert "secret" not in api.get("/health").text


# ---- 开发回显只在测试区 --------------------------------------------------

def test_dev_echo_is_only_allowed_in_the_test_area(tmp_path):
    echoed = Settings(str(tmp_path / "test"), "https://forum.example",
                      test_sessions_enabled=True, public_api_prefix="/test-api",
                      mail_dev_echo=True)
    with TestClient(create_app(echoed)) as api:
        # **服务内部认的永远是 `/api`**：测试区的公开路径 `/test-api/...` 是 nginx
        # 重写进来的（见 `app.py` 里 `API_PREFIX` 那段注释），所以这里走 helper 的 /api
        # 就等于从外面走 /test-api。
        body = register(api).json()
        assert body["devCode"], "测试区：注册响应里就该带着验证码，联调不用等邮件"
        check = verify(api, code=body["devCode"])
        assert check.status_code == 200, check.text
        resent = api.post("/api/account/verify/resend", json={"email": ADDRESS})
        # 60 秒冷却对回显同样有效：它不是"调试开关"，是限流
        assert resent.status_code == 429

    prod = Settings(str(tmp_path / "prod"), "https://forum.example", mail_dev_echo=True)
    with TestClient(create_app(prod)) as api:
        # 正式服务：env 里写了 1 也按掉（判据在 Settings.mailer），而且要能看见警告
        assert api.get("/health").json()["mailDevEcho"] is False
        refused = register(api)
        assert refused.status_code == 503
        assert "devCode" not in refused.json()


# ---- 历史账号平滑过渡 ----------------------------------------------------

def insert_legacy_account(path, username="legacy", password=PASSWORD, created=None):
    """直接往库里塞一行"加邮箱之前建的账号"：没有 email，created 早于分界线。"""
    from modelpilot_forum.accounts import hash_password
    with sqlite3.connect(path) as db:
        db.execute("INSERT INTO accounts(user_id, username, password_hash, created)"
                   " VALUES(?,?,?,?)",
                   ("u_" + "0" * 32, username, hash_password(password),
                    created if created is not None else EMAIL_EPOCH_MILLIS - 86_400_000))
        db.commit()


def test_a_legacy_account_without_an_email_can_still_sign_in(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        insert_legacy_account(tmp_path / "modelpilot.sqlite3")
        response = login(api, identifier="legacy")
        assert response.status_code == 200, response.text
        me = api.get("/api/account/me",
                     headers={"Authorization": "Bearer " + response.json()["token"]}).json()
        assert me["username"] == "legacy"
        assert me["email"] is None and me["emailVerified"] is False


def test_an_account_created_after_the_epoch_without_an_email_cannot_sign_in(tmp_path):
    """判据是**建号时间**，不是"email 是不是空"：绕过注册接口塞进来的行也得验证。"""
    with TestClient(app_for(tmp_path)) as api:
        insert_legacy_account(tmp_path / "modelpilot.sqlite3", created=now_ms())
        refused = login(api, identifier="legacy")
        assert refused.status_code == 403
        assert error_code(refused) == "EMAIL_REQUIRED"


def test_a_nonce_email_registration_is_still_refused_on_a_second_start(tmp_path):
    """重启一次再登：`_upgrade` 是幂等的，不会把状态搞坏。"""
    with TestClient(app_for(tmp_path)) as api:
        register(api)
    with TestClient(app_for(tmp_path)) as api:
        assert login(api).status_code == 403
        assert verify(api).status_code == 200
        assert login(api).status_code == 200


def test_the_schema_upgrade_is_idempotent_on_a_legacy_database(tmp_path):
    """老库（没有 email 三列）启动两次，列只加一次，数据不丢。"""
    store = Store(str(tmp_path), "/api")
    with store.connect(write=True) as db:
        # 手工造一个"上一版"的 accounts 表
        db.executescript("""
            CREATE TABLE accounts (user_id TEXT PRIMARY KEY, username TEXT UNIQUE NOT NULL,
                                   password_hash TEXT NOT NULL, created INTEGER NOT NULL);
            CREATE TABLE account_sessions (token_hash TEXT PRIMARY KEY,
                                           user_id TEXT NOT NULL REFERENCES accounts(user_id),
                                           created INTEGER NOT NULL, expires INTEGER NOT NULL);
        """)
    insert_legacy_account(tmp_path / "modelpilot.sqlite3")
    Accounts(store, RecordingMailer())
    Accounts(store, RecordingMailer())
    with store.connect() as db:
        columns = {row[1] for row in db.execute("PRAGMA table_info(accounts)")}
        rows = db.execute("SELECT username, email FROM accounts").fetchall()
    assert {"email", "email_verified", "email_verified_at"} <= columns
    assert [(row["username"], row["email"]) for row in rows] == [("legacy", None)]
