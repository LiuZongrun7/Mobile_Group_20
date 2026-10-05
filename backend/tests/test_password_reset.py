"""找回密码与修改密码。

这一组盯的是**"忘了密码"和"号被人登了"这两种处境不能互相拖累**：

1. **找回密码要能进得去**：邮箱收到重置码 → 改密码 → 所有旧会话失效
   （入侵者的会话也在里面，不然改密码等于没改）；
2. **改密码不要把人踢得太狠**：当前设备留着，只踢其它设备；
3. **两条路都不许变成探测接口**：邮箱存不存在、账号验证没验证过，
   从响应里看不出来；
4. **注册码和重置码不能互相顶用**——两种权限不一样，混用等于悄悄放宽。
"""
import sqlite3

import pytest
from fastapi.testclient import TestClient

from helpers import OUTBOX, RecordingMailer, create_app_for_tests, latest_code
from modelpilot_forum.app import Settings

PASSWORD = "correct horse battery"
NEW_PASSWORD = "a brand new battery"
ADDRESS = "alice@example.com"


def app_for(tmp_path, **overrides):
    settings = Settings(str(tmp_path), "https://forum.example", **overrides)
    return create_app_for_tests(settings)


def register(api, email=ADDRESS, username="alice", password=PASSWORD):
    return api.post("/api/account/register",
                    json={"email": email, "username": username, "password": password})


def verify(api, email=ADDRESS, code=None):
    return api.post("/api/account/verify", json={"email": email, "code": code or latest_code(email)})


def forgot(api, email=ADDRESS):
    return api.post("/api/account/password/forgot", json={"email": email})


def reset(api, email=ADDRESS, code=None, password=NEW_PASSWORD):
    return api.post("/api/account/password/reset",
                    json={"email": email, "code": code or latest_code(email), "password": password})


def login(api, identifier=ADDRESS, password=PASSWORD):
    return api.post("/api/account/login", json={"identifier": identifier, "password": password})


def bearer(token):
    return {"Authorization": "Bearer " + token}


def verified_session(api, email=ADDRESS, username="alice", password=PASSWORD):
    """注册 + 验证 + 登录，返回 token。"""
    assert register(api, email, username, password).status_code == 201
    assert verify(api, email).status_code == 200
    return login(api, email, password).json()["token"]


# ---- 忘记密码：发码那一端 -------------------------------------------------

def test_forgot_sends_a_code_to_a_registered_email(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        response = forgot(api)
        assert response.status_code == 200 and response.json()["sent"] is True
        assert len(latest_code(ADDRESS)) == 6
    with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
        purposes = [row[0] for row in db.execute("SELECT purpose FROM email_codes ORDER BY created")]
    assert purposes == ["register", "reset"], "重置码要和注册码分开存（用途字段）"


def test_forgot_for_an_unknown_email_looks_identical_and_sends_nothing(tmp_path):
    """**不许变成探测接口，也不许替人给陌生地址发信。**

    两个邮箱各请求一次（不是同一个邮箱点两次，那样会撞 60 秒冷却），
    比较响应体；再确认那封"信"根本没发出去。
    """
    # 用一个**这次运行独有**的地址：OUTBOX 是模块级的，别的用例用过同名地址的话，
    # 这条断言会因为别人的信而失败（第一版就是这么挂的）。
    stranger = f"stranger-{tmp_path.name}@example.com"
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        known = forgot(api)
        unknown = api.post("/api/account/password/forgot", json={"email": stranger})
        assert known.status_code == unknown.status_code == 200
        assert known.json() == unknown.json() == {"sent": True}
    assert stranger not in OUTBOX, "没注册过的邮箱不该收到任何邮件"


def test_forgot_and_resend_have_separate_cooldowns(tmp_path):
    """注册码的冷却不该把重置码也挡住（用途是分开记的）。"""
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        # 注册那封刚发过，立刻重发注册码会 429
        assert api.post("/api/account/verify/resend", json={"email": ADDRESS}).status_code == 429
        # 但重置码是另一回事：同一次请求里应该能发出去
        assert forgot(api).status_code == 200


def test_forgot_twice_within_a_minute_is_rate_limited(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        assert forgot(api).status_code == 200
        again = forgot(api)
        assert again.status_code == 429
        assert again.json()["code"] == "RATE_LIMIT"
        assert again.headers.get("retry-after") == "60"


def test_forgot_rejects_an_obviously_bad_email(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        assert api.post("/api/account/password/forgot", json={"email": ""}).json()["code"] == "EMAIL_REQUIRED"
        assert api.post("/api/account/password/forgot",
                        json={"email": "not-an-email"}).json()["code"] == "EMAIL_INVALID"


def test_without_a_mail_service_every_forgot_looks_the_same(tmp_path):
    """服务器发不了信时，**注册过的和没注册过的邮箱返回同一句 503**。
    先查账号再判发信能力的话，这两者就会不一样，那就是一个探测接口。"""
    settings = Settings(str(tmp_path), "https://forum.example")     # 没有 SMTP 配置
    from modelpilot_forum.app import create_app
    with TestClient(create_app(settings)) as api:
        register_without_mail = api.post("/api/account/register",
                                         json={"email": ADDRESS, "username": "alice",
                                               "password": PASSWORD})
        assert register_without_mail.status_code == 503           # 注册在这一样发不出去
    known = api.post("/api/account/password/forgot", json={"email": ADDRESS})
    unknown = api.post("/api/account/password/forgot", json={"email": "nobody@example.com"})
    assert known.status_code == unknown.status_code == 503
    assert known.json() == unknown.json()
    assert known.json()["code"] == "MAIL_NOT_CONFIGURED"


# ---- 重设密码：改的那一端 -------------------------------------------------

def test_reset_changes_the_password_and_lets_you_back_in(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        verify(api)
        assert login(api).status_code == 200
        assert forgot(api).status_code == 200
        changed = reset(api)
        assert changed.status_code == 200 and changed.json()["passwordChanged"] is True
        # 旧密码作废、新密码能进
        assert login(api).status_code == 401
        assert login(api, password=NEW_PASSWORD).status_code == 200


def test_reset_revokes_every_session_including_a_stolen_one(tmp_path):
    """**这条是"号被人登了"那条路的判据。** 改完密码把旧会话留着等于白改。"""
    with TestClient(app_for(tmp_path)) as api:
        stolen = verified_session(api)                 # 想象这个 token 在别人手里
        mine = login(api).json()["token"]
        forgot(api)
        result = reset(api).json()
        assert result["sessionsRevoked"] >= 2
        assert api.get("/api/account/me", headers=bearer(stolen)).status_code == 401
        assert api.get("/api/account/me", headers=bearer(mine)).status_code == 401


def test_reset_also_counts_as_email_verification(tmp_path):
    """能收到这封信本身就证明邮箱是他的——所以重置成功顺带把邮箱标成已验证。"""
    with TestClient(app_for(tmp_path)) as api:
        register(api)                                   # 故意不验证
        assert login(api).json()["code"] == "EMAIL_UNVERIFIED"
        forgot(api)
        assert reset(api).status_code == 200
        # 现在能登进去了（新密码）
        assert login(api, password=NEW_PASSWORD).status_code == 200


def test_reset_needs_the_code_and_the_right_one(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        forgot(api)
        wrong = reset(api, code="000000" if latest_code(ADDRESS) != "000000" else "111111")
        assert wrong.status_code == 400 and wrong.json()["code"] == "CODE_INVALID"
        assert login(api, password=NEW_PASSWORD).status_code == 401     # 没改成
        assert reset(api).status_code == 200                            # 正确码才行


def test_an_expired_reset_code_is_told_apart(tmp_path):
    from modelpilot_forum.store import now_ms
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        forgot(api)
        with sqlite3.connect(tmp_path / "modelpilot.sqlite3") as db:
            db.execute("UPDATE email_codes SET expires=? WHERE purpose='reset'", (now_ms() - 1000,))
            db.commit()
        expired = reset(api)
        assert expired.status_code == 400 and expired.json()["code"] == "CODE_EXPIRED"


def test_a_used_reset_code_cannot_be_used_again(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        forgot(api)
        code = latest_code(ADDRESS)
        assert reset(api, code=code).status_code == 200
        again = reset(api, code=code, password="yet another battery")
        assert again.status_code == 400 and again.json()["code"] == "CODE_INVALID"


def test_reset_for_an_unknown_email_says_the_code_is_wrong(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        response = api.post("/api/account/password/reset",
                            json={"email": "nobody@example.com", "code": "123456",
                                  "password": NEW_PASSWORD})
        assert response.status_code == 400 and response.json()["code"] == "CODE_INVALID"


def test_the_two_kinds_of_code_do_not_interchange(tmp_path):
    """**注册码不能改密码，重置码不能验证邮箱。** 两种权限不一样。"""
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        register_code = latest_code(ADDRESS)
        refused = reset(api, code=register_code)
        assert refused.status_code == 400 and refused.json()["code"] == "CODE_INVALID"

    with TestClient(app_for(tmp_path / "second")) as api:
        register(api)
        forgot(api)
        reset_code = latest_code(ADDRESS)
        refused = verify(api, code=reset_code)
        assert refused.status_code == 400 and refused.json()["code"] == "CODE_INVALID"


def test_a_short_new_password_is_refused_before_touching_anything(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        register(api)
        forgot(api)
        short = reset(api, password="short")
        assert short.status_code == 400 and short.json()["code"] == "PASSWORD_INVALID"
        assert login(api, password=NEW_PASSWORD).status_code == 401


# ---- 已登录改密码 ---------------------------------------------------------

def test_change_password_keeps_this_session_and_drops_the_others(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        here = verified_session(api)
        elsewhere = login(api).json()["token"]
        changed = api.post("/api/account/password/change",
                           json={"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD},
                           headers=bearer(here))
        assert changed.status_code == 200 and changed.json()["passwordChanged"] is True
        assert changed.json()["otherSessionsRevoked"] >= 1
        # **当前设备还在用**（否则用户会莫名其妙被踢回登录页）
        assert api.get("/api/account/me", headers=bearer(here)).status_code == 200
        assert api.get("/api/account/me", headers=bearer(elsewhere)).status_code == 401
        assert login(api, password=NEW_PASSWORD).status_code == 200
        assert login(api).status_code == 401


def test_change_password_needs_the_current_one(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        token = verified_session(api)
        refused = api.post("/api/account/password/change",
                           json={"currentPassword": "not it", "newPassword": NEW_PASSWORD},
                           headers=bearer(token))
        assert refused.status_code == 401 and refused.json()["code"] == "CREDENTIALS"
        assert login(api, password=PASSWORD).status_code == 200      # 没被改
        assert login(api, password=NEW_PASSWORD).status_code == 401


def test_change_password_needs_a_session_and_a_valid_new_password(tmp_path):
    with TestClient(app_for(tmp_path)) as api:
        token = verified_session(api)
        assert api.post("/api/account/password/change",
                        json={"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD}
                        ).status_code == 401
        short = api.post("/api/account/password/change",
                         json={"currentPassword": PASSWORD, "newPassword": "tiny"},
                         headers=bearer(token))
        assert short.status_code == 400 and short.json()["code"] == "PASSWORD_INVALID"


def test_the_new_password_is_hashed_like_any_other(tmp_path):
    """改密码不是"悄悄存一份明文"的例外。"""
    with TestClient(app_for(tmp_path)) as api:
        token = verified_session(api)
        api.post("/api/account/password/change",
                 json={"currentPassword": PASSWORD, "newPassword": NEW_PASSWORD},
                 headers=bearer(token))
    blob = (tmp_path / "modelpilot.sqlite3").read_bytes()
    assert NEW_PASSWORD.encode() not in blob
    assert PASSWORD.encode() not in blob
