"""APP 账号系统的测试：注册、登录、会话、密码存储。

**这是整个项目里唯一存密码的地方**，所以这里盯的几件事比别处更要紧：

1. 密码**绝不落明文**（也不落裸 sha256）；
2. 用户名错和密码错**返回同一个结果**——区分开等于送人一个用户名探测接口；
3. 会话 token 库里**只存 sha256**；
4. 论坛认的是**我们自己发的 token**，不是外部服务转手的。
"""
import hashlib
import httpx
import pytest
from fastapi.testclient import TestClient

from tokentrail_forum.accounts import (Accounts, TOKEN_PREFIX, hash_password,
                                       verify_password)
from tokentrail_forum.app import Settings, create_app
from tokentrail_forum.store import Store

PASSWORD = "correct horse battery"


def make_app(path):
    """一个**不注入 verifier** 的 app——也就是生产形态：用自己的 accounts 表。"""
    return create_app(Settings(str(path), "https://forum.example"))


def register(api, username="alice", password=PASSWORD):
    return api.post("/api/account/register", json={"username": username, "password": password})


def login(api, username="alice", password=PASSWORD):
    return api.post("/api/account/login", json={"username": username, "password": password})


def auth(token):
    return {"Authorization": "Bearer " + token}


def session(api, username="alice", password=PASSWORD):
    register(api, username, password)
    return login(api, username, password).json()["token"]


# ---- 密码怎么存 ---------------------------------------------------------

def test_password_is_never_stored_in_plaintext(tmp_path):
    store = Store(str(tmp_path), "/api")
    accounts = Accounts(store)
    accounts.register("alice", PASSWORD)
    blob = (tmp_path / "forum.sqlite3").read_bytes()
    assert PASSWORD.encode() not in blob, "plaintext password must never reach the database"
    # 也不该是裸 sha256——那太快了，密码字典一撞就开
    assert hashlib.sha256(PASSWORD.encode()).hexdigest().encode() not in blob


def test_the_same_password_gets_different_hashes(tmp_path):
    """每个用户一份随机盐。不加盐的话，两个用同一个密码的用户哈希一样——
    看到哈希就知道「这两个人密码相同」。"""
    store = Store(str(tmp_path), "/api")
    accounts = Accounts(store)
    accounts.register("alice", PASSWORD)
    accounts.register("bob", PASSWORD)
    with store.connect() as db:
        hashes = [row[0] for row in db.execute("SELECT password_hash FROM accounts")]
    assert len(set(hashes)) == 2, hashes


def test_verification_round_trips_and_rejects_wrong_password():
    stored = hash_password(PASSWORD)
    assert verify_password(PASSWORD, stored)
    assert not verify_password(PASSWORD + "x", stored)
    assert not verify_password("", stored)


def test_a_corrupt_hash_record_fails_verification_instead_of_crashing():
    """存量记录坏了 → 验证失败。抛异常会变成 500，让整个登录接口看起来挂了。"""
    for broken in ("", "garbage", "scrypt$bad", "bcrypt$1$2$3$aa$bb"):
        assert verify_password(PASSWORD, broken) is False


def test_the_stored_format_carries_its_parameters():
    """参数一起存，将来调大了还能验老密码。不存的话改了参数老用户全登不进来。"""
    stored = hash_password(PASSWORD)
    algorithm, n, r, p, salt, derived = stored.split("$")
    assert algorithm == "scrypt"
    assert int(n) >= 2 ** 14 and int(r) >= 8
    assert len(salt) == 32 and len(derived) == 64


# ---- 注册 ---------------------------------------------------------------

def test_register_returns_a_user_id_and_no_token(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        response = register(api)
        assert response.status_code == 201, response.text
        body = response.json()
        assert body["userId"].startswith("u_")
        assert body["username"] == "alice"
        # **不自动登录**：注册接口被脚本刷的话，自动登录等于顺手建了一堆可用会话
        assert "token" not in body


def test_a_taken_username_is_refused(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        assert register(api).status_code == 201
        assert register(api).status_code == 409


@pytest.mark.parametrize("username,password", [
    ("ab", PASSWORD),                    # 用户名太短
    ("a" * 65, PASSWORD),                # 太长
    ("has space", PASSWORD),             # 空格
    ("alice", "short"),                  # 密码太短
    ("alice", "x" * 201),                # 密码太长
])
def test_bad_registrations_are_refused(tmp_path, username, password):
    with TestClient(make_app(tmp_path)) as api:
        assert register(api, username, password).status_code == 400


def test_user_ids_are_random_not_sequential(tmp_path):
    """自增会把「你是第几个注册的」泄出去，迁移时也容易撞。"""
    with TestClient(make_app(tmp_path)) as api:
        ids = set()
        for index in range(5):
            ids.add(register(api, f"user{index}").json()["userId"])
    assert len(ids) == 5
    assert all(len(value) == 34 for value in ids), ids      # u_ + 32 hex


# ---- 登录 ---------------------------------------------------------------

def test_login_issues_a_session_token(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        register(api)
        body = login(api).json()
        assert body["token"].startswith(TOKEN_PREFIX)
        assert body["userId"].startswith("u_")
        assert body["expiresAtEpochMillis"] > 0
        # 拿到 token 就该能用
        me = api.get("/api/account/me", headers=auth(body["token"]))
        assert me.status_code == 200
        assert me.json()["username"] == "alice"


def test_a_wrong_password_and_an_unknown_username_look_identical(tmp_path):
    """**这条是防止用户名枚举的。** 两者返回不同的状态码或不同的文案，
    就等于送人一个「这个用户名存在吗」的探测接口。"""
    with TestClient(make_app(tmp_path)) as api:
        register(api)
        wrong_password = login(api, "alice", "wrong password here")
        unknown_user = login(api, "nobody", PASSWORD)
    assert wrong_password.status_code == unknown_user.status_code == 401
    assert wrong_password.json() == unknown_user.json()


def test_the_session_token_is_only_stored_hashed(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        token = session(api)
    blob = (tmp_path / "forum.sqlite3").read_bytes()
    # token 泄露等于别人能用你的账号，没理由把原文留着
    assert token.encode() not in blob
    assert hashlib.sha256(token.encode()).hexdigest().encode() in blob


def test_tokens_are_unique_per_login(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        register(api)
        first = login(api).json()["token"]
        second = login(api).json()["token"]
    assert first != second
    # 两个都有效（多设备登录是正常的）
    with TestClient(make_app(tmp_path)) as api:
        assert api.get("/api/account/me", headers=auth(first)).status_code == 200
        assert api.get("/api/account/me", headers=auth(second)).status_code == 200


# ---- 会话失效 -----------------------------------------------------------

def test_logout_revokes_only_that_session(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        register(api)
        phone = login(api).json()["token"]
        laptop = login(api).json()["token"]
        assert api.post("/api/account/logout", headers=auth(phone)).status_code == 200
        assert api.get("/api/account/me", headers=auth(phone)).status_code == 401
        # 另一台设备的会话不该被牵连
        assert api.get("/api/account/me", headers=auth(laptop)).status_code == 200


def test_logout_is_idempotent(tmp_path):
    """已经失效的 token 再退一次也返回成功——不然 App 在「过期后点退出」
    会看到一个莫名其妙的错误。"""
    with TestClient(make_app(tmp_path)) as api:
        token = session(api)
        api.post("/api/account/logout", headers=auth(token))
        assert api.post("/api/account/logout", headers=auth(token)).status_code == 200
        assert api.post("/api/account/logout").status_code == 200


def test_an_invented_token_is_refused(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        register(api)
        for bad in (TOKEN_PREFIX + "made-up", "Bearer-less", "tt_test_something"):
            assert api.get("/api/account/me", headers=auth(bad)).status_code == 401
        assert api.get("/api/account/me").status_code == 401


def test_an_expired_session_is_refused(tmp_path):
    """过期的会话要真的失效——不能只看表里有没有这一行。"""
    store = Store(str(tmp_path), "/api")
    accounts = Accounts(store)
    created = accounts.register("alice", PASSWORD)
    issued = accounts.issue(created["userId"])
    token_hash = hashlib.sha256(issued["token"].encode()).hexdigest()
    from tokentrail_forum.store import now_ms
    with store.connect(write=True) as db:
        db.execute("UPDATE account_sessions SET expires=? WHERE token_hash=?",
                   (now_ms() - 1000, token_hash))
    assert accounts.resolve("Bearer " + issued["token"]) is None


# ---- 论坛认的是我们自己的 token ------------------------------------------

def test_the_forum_accepts_our_own_token(tmp_path):
    """**这一条是「和外部账号服务隔离」的判据。**

    之前论坛的 token 是那台机器上另一个项目签发的；现在是我们自己签的，
    签完就能发帖、就能读自己的帖子。
    """
    with TestClient(make_app(tmp_path)) as api:
        token = session(api)
        published = api.post("/api/forum/posts", headers={**auth(token), "Idempotency-Key": "k1"},
                             json={"body": "hello from our own account"})
        assert published.status_code == 201, published.text
        author = published.json()["authorUid"]
        # 帖子的作者就是账号的 user_id（**同一个 ID，没有中间层**）
        assert author == login(api).json()["userId"]
        # 作者名是账号的用户名
        assert published.json()["authorName"] == "alice"


def test_two_accounts_do_not_see_each_other_as_author(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        alice = session(api, "alice")
        bob = session(api, "bob")
        post = api.post("/api/forum/posts", headers={**auth(alice), "Idempotency-Key": "k1"},
                        json={"body": "alice's post"}).json()
        # 帖子是公共的（谁都能读），但作者是 alice
        seen = api.get(f"/api/forum/posts/{post['id']}", headers=auth(bob)).json()
        assert seen["authorName"] == "alice"


def test_a_relay_key_does_not_work_as_an_account_token(tmp_path):
    """**两套凭据必须互不通用。** relay key 是给信息收集那条路的，
    它不该能当论坛的登录态用——那正是之前把设计带偏的那个混淆。"""
    with TestClient(make_app(tmp_path)) as api:
        for bad in ("tt_" + "a" * 40, "tt_test_" + "b" * 40):
            assert api.get("/api/forum/posts", headers=auth(bad)).status_code == 401
            assert api.get("/api/account/me", headers=auth(bad)).status_code == 401


# ---- 给别的测试模块用的辅助（relay key 现在必须有账号）-------------------

def make_account(api, username="owner", password=PASSWORD):
    """注册并登录一个账号，返回它的 token。"""
    api.post("/api/account/register", json={"username": username, "password": password})
    body = api.post("/api/account/login", json={"username": username, "password": password}).json()
    return body["token"]


def account_headers(api, username="owner", password=PASSWORD):
    return {"Authorization": "Bearer " + make_account(api, username, password)}
