"""用量记账的测试：四个桶怎么归一、按天怎么分桶、逐次记录怎么来、迁移怎么写。

**这些原来在 `test_relay.py` 里**——那时候"中转"还在，用量是转发时顺手记下来的。
2026-09-30 中转整块删掉之后，产生用量的路只剩"用户提问、后端调模型"这一条，
而这一批用例测的是**记账那一侧**，和中转在不在没关系，所以整段搬过来继续守着。

随功能删掉的：注册与 relay key 的规则、原样透传、SSRF 防护、流式解析、
"relay key 不能读论坛"这类认证边界。它们守的东西已经不存在了。
"""
from fastapi.testclient import TestClient

from tokentrail_forum.app import Settings, create_app
from tokentrail_forum.store import Store
from tokentrail_forum.usage import split_usage
from tokentrail_forum.usage_store import UsageStore
from conftest import verifier
from helpers import account_login, account_token, bearer, cst_millis, insert_usage


def make_app(path, enabled=True):
    """开（或关）记账接口的 app。

    **不再需要假上游**：这里不测转发。`relay_enabled` 这个名字是历史遗留，
    它现在管的是"用量/预算/价目这几组接口注册不注册"（见 `app.Settings`）。
    """
    settings = Settings(str(path), "https://forum.example", relay_enabled=enabled)
    return create_app(settings, verifier())


def daily(api, token, **query):
    from urllib.parse import urlencode
    suffix = ("?" + urlencode(query)) if query else ""
    return api.get("/api/relay/usage/daily" + suffix, headers=bearer(token))


def calls(api, token, **query):
    from urllib.parse import urlencode
    suffix = ("?" + urlencode(query)) if query else ""
    return api.get("/api/relay/usage/calls" + suffix, headers=bearer(token))


# ---- 纯函数：四个桶的归一化 ---------------------------------------------

def test_split_usage_handles_both_field_shapes():
    """`input` 只装**未命中缓存**的部分（`CONTRACTS.md` §8）。

    两种字段形状都要认：OpenAI/DeepSeek 的 `prompt_tokens` 是输入总量、要把缓存减掉，
    而 Anthropic 的 `input_tokens` 本身就已经是未命中部分——再减一次会出负数，
    而负数金额不会报错，只会让账对不上。
    """
    openai = split_usage({"prompt_tokens": 100, "completion_tokens": 10,
                          "prompt_tokens_details": {"cached_tokens": 30}}, "gpt-5")
    assert (openai["input"], openai["cacheRead"], openai["output"]) == (70, 30, 10)
    deepseek = split_usage({"prompt_tokens": 100, "completion_tokens": 10,
                            "prompt_cache_hit_tokens": 40,
                            "prompt_cache_miss_tokens": 60}, "deepseek-chat")
    assert (deepseek["input"], deepseek["cacheRead"]) == (60, 40)
    anthropic = split_usage({"input_tokens": 25, "output_tokens": 5,
                             "cache_creation_input_tokens": 100}, "claude")
    assert (anthropic["input"], anthropic["cacheWrite"], anthropic["output"]) == (25, 100, 5)
    # 没有 usage 的响应返回 None，不是全 0——「没有」和「花了 0」要分得开。
    assert split_usage({}, "m") is None


# ---- 关了开关就没有这些接口 ----------------------------------------------

def test_usage_endpoints_are_absent_when_disabled(tmp_path):
    """没开的时候这些路径应该是不存在的，而不是「存在但拒绝」。"""
    with TestClient(make_app(tmp_path, enabled=False)) as api:
        token = account_token(api)
        assert api.get("/api/relay/usage", headers=bearer(token)).status_code == 404
        assert daily(api, token).status_code == 404


# ---- 日汇总（服务端成为唯一数据源的那一层）-------------------------------

def test_daily_rollup_buckets_by_shanghai_calendar_day(tmp_path):
    """跨 UTC 日的时刻必须落到**北京时间的同一天**。

    这是整条链最容易错的地方：存储是 UTC 毫秒，而「哪一天」按 Asia/Shanghai 算。
    23:30 和次日 00:30 在北京是两天，在 UTC 是**同一天**——用 UTC 分组的话
    这两笔会被合并，而用户看到的日曲线就少了一根柱子。
    """
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-27", 23, 30), "DEEPSEEK",
                     input_tokens=100)
        insert_usage(tmp_path, digest, cst_millis("2026-09-28", 0, 30), "DEEPSEEK",
                     input_tokens=7)

        body = daily(api, token).json()
        days = {item["day"]: item for item in body["items"]}
        assert set(days) == {"2026-09-27", "2026-09-28"}, days
        assert days["2026-09-27"]["input"] == 100
        assert days["2026-09-28"]["input"] == 7


def test_daily_rollup_groups_by_provider_and_model(tmp_path):
    """DailyUsage 的主键是 (uid, 天, provider, 模型)，三个都要分开。"""
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        digest = account_login(api)["userId"]
        same = cst_millis("2026-09-27", 12)
        insert_usage(tmp_path, digest, same, "DEEPSEEK", "deepseek-chat", input_tokens=10)
        insert_usage(tmp_path, digest, same, "DEEPSEEK", "deepseek-reasoner", input_tokens=20)
        insert_usage(tmp_path, digest, same, "OPENAI", "deepseek-chat", input_tokens=30)
        items = daily(api, token).json()["items"]
        assert len(items) == 3, items
        assert sum(item["input"] for item in items) == 60
        assert {(item["provider"], item["model"]) for item in items} == {
            ("DEEPSEEK", "deepseek-chat"), ("DEEPSEEK", "deepseek-reasoner"),
            ("OPENAI", "deepseek-chat")}


def test_provider_is_never_an_invented_enum_value(tmp_path):
    """认不出提供方就留 null，**不猜**。

    以前 provider 是从上游域名猜出来的；那条路随转发删掉之后，provider 由**写入方**
    给（将来是路由那一层，它本来就知道自己调的是谁）。这里直接写一行 provider=None
    的量，断言读回来还是 None，而不是被填成某个枚举值。
    """
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-27", 12), None, input_tokens=11)
        items = daily(api, token).json()["items"]
        assert items and items[0]["provider"] is None, items


def test_daily_reports_pricing_as_unavailable_rather_than_zero(tmp_path):
    """算不出价必须留 null，**不许填 0**。

    `CONTRACTS.md` §4：把「不知道花了多少」显示成「花了 0 元」是那一节点名的
    「最难发现的一类 bug」——数字看着没错、结论是错的。服务端还没有这个模型的价，
    所以这里断言的是 null + 一个显式的 pricingAvailable:false。
    """
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-27", 12), "DEEPSEEK",
                     "no-such-model-anywhere", input_tokens=1000)
        body = daily(api, token).json()
        assert body["pricingAvailable"] is False
        assert body["items"][0]["costMicros"] is None
        assert body["items"][0]["rateVersion"] is None


def test_daily_window_and_other_users_are_isolated(tmp_path):
    """`since` 是窗口起点；**别人的用量一行都看不见**。"""
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        mine = account_login(api)["userId"]
        other = account_login(api, "second")["userId"]
        insert_usage(tmp_path, mine, cst_millis("2026-09-20", 12), "DEEPSEEK", input_tokens=1)
        insert_usage(tmp_path, mine, cst_millis("2026-09-27", 12), "DEEPSEEK", input_tokens=2)
        insert_usage(tmp_path, other, cst_millis("2026-09-27", 12), "DEEPSEEK", input_tokens=400)

        windowed = daily(api, token, since=cst_millis("2026-09-21", 0)).json()["items"]
        assert [item["day"] for item in windowed] == ["2026-09-27"]
        assert windowed[0]["input"] == 2
        everything = daily(api, token).json()["items"]
        assert sum(item["input"] for item in everything) == 3          # 别人的 400 不在内


def test_daily_needs_an_account_token(tmp_path):
    """**身份只有账号 token 一种。** 没带、带错、带旧格式的 relay key 一律 401。

    最后那一条是这次删中转的守门人：`tt_...` 那种 key 曾经是合法的读用量凭据，
    现在必须被拒——不然"中转已经删掉"这句话在鉴权层就是假的。
    """
    with TestClient(make_app(tmp_path)) as api:
        assert api.get("/api/relay/usage/daily").status_code == 401
        assert daily(api, "tt_not_a_thing_0123456789").status_code == 401
        assert daily(api, "tt_app_not_a_real_session").status_code == 401


# ---- 逐次记录（`UsageCall` 的形状）---------------------------------------

def test_recording_a_call_produces_one_row(tmp_path):
    """`UsageStore.record()` 是**唯一**的写入口，写出来的行要能当 `UsageCall` 用。

    以前这一行是转发成功之后顺手写的；转发删掉之后它暂时没有调用方
    （"用户提问、后端调模型"那条路还没写），所以这里直接调它——
    将来那条路接上时，这个测试就是它的地基。
    """
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        digest = account_login(api)["userId"]
        store = api.app.state.store
        with store.connect(write=True) as db:
            UsageStore(store).record(db, digest, {
                "model": "deepseek-chat", "provider": "DEEPSEEK",
                "input": 600, "cacheRead": 400, "cacheWrite": 0, "output": 250, "calls": 1})
        items = calls(api, token).json()["items"]
        assert len(items) == 1, items
        row = items[0]
        assert row["provider"] == "DEEPSEEK" and row["model"] == "deepseek-chat"
        assert (row["input"], row["cacheRead"], row["output"]) == (600, 400, 250)
        # id 的形状是契约的一部分：没有它客户端没法去重。
        assert row["id"].startswith("call:DEEPSEEK:")


def test_each_recording_gets_a_distinct_call_id(tmp_path):
    """同一毫秒里的两次调用不能撞 id——撞了客户端会把两条合成一条。"""
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        digest = account_login(api)["userId"]
        store = api.app.state.store
        for _ in range(2):
            with store.connect(write=True) as db:
                UsageStore(store).record(db, digest, {
                    "model": "deepseek-chat", "provider": "DEEPSEEK",
                    "input": 1, "cacheRead": 0, "cacheWrite": 0, "output": 1, "calls": 1})
        items = calls(api, token).json()["items"]
        assert len(items) == 2
        assert len({item["id"] for item in items}) == 2


def test_pricing_is_reported_as_unavailable_not_zero(tmp_path):
    """逐次记录里的 `costMicros` 同样是 null 而不是 0（和日汇总同一条底线）。"""
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-27", 12), "DEEPSEEK",
                     "no-such-model-anywhere", call_id="call:DEEPSEEK:x:1:000001")
        items = calls(api, token).json()["items"]
        assert len(items) == 1
        assert items[0]["costMicros"] is None


def test_rows_without_a_call_id_are_skipped(tmp_path):
    """迁移之前写下的老行没有 id，必须跳过——给一个拼出来的假 id
    会让两条不同的记录撞成一条（客户端按 id 去重）。"""
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-27", 12), "DEEPSEEK", call_id=None)
        insert_usage(tmp_path, digest, cst_millis("2026-09-27", 13), "DEEPSEEK",
                     call_id="call:DEEPSEEK:x:1:000001")
        items = calls(api, token).json()["items"]
        assert len(items) == 1, items
        assert items[0]["id"] == "call:DEEPSEEK:x:1:000001"


def test_calls_window_and_isolation(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        token = account_token(api)
        mine, other = account_login(api)["userId"], account_login(api, "second")["userId"]
        insert_usage(tmp_path, mine, cst_millis("2026-09-20", 12), "DEEPSEEK",
                     call_id="call:DEEPSEEK:m:1:000001")
        insert_usage(tmp_path, mine, cst_millis("2026-09-27", 12), "DEEPSEEK",
                     call_id="call:DEEPSEEK:m:2:000002")
        insert_usage(tmp_path, other, cst_millis("2026-09-27", 12), "DEEPSEEK",
                     call_id="call:DEEPSEEK:o:3:000003")

        assert len(calls(api, token).json()["items"]) == 2                    # 看不到别人的
        windowed = calls(api, token, since=cst_millis("2026-09-21", 0)).json()["items"]
        assert [item["id"] for item in windowed] == ["call:DEEPSEEK:m:2:000002"]


def test_calls_needs_an_account_token(tmp_path):
    with TestClient(make_app(tmp_path)) as api:
        assert api.get("/api/relay/usage/calls").status_code == 401
        assert calls(api, "team-token").status_code == 401
        assert calls(api, "tt_not_registered_0123456789").status_code == 401


# ---- 迁移 -----------------------------------------------------------------

def test_existing_database_without_provider_column_is_migrated(tmp_path):
    """老库（有 relay_usage 但没 provider 列）必须能自动补上。

    服务器上已经部署过这一版表结构了，所以这条不是假想：
    `CREATE TABLE IF NOT EXISTS` 对已存在的表什么都不做，
    不写迁移的话新代码一 INSERT 就报 "no such column: provider"。
    """
    import sqlite3

    path = tmp_path / "forum.sqlite3"
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE relay_usage (
             key_hash TEXT NOT NULL, model TEXT NOT NULL, service_tier TEXT,
             created INTEGER NOT NULL, input INTEGER NOT NULL DEFAULT 0,
             cache_read INTEGER NOT NULL DEFAULT 0, cache_write INTEGER NOT NULL DEFAULT 0,
             output INTEGER NOT NULL DEFAULT 0, calls INTEGER NOT NULL DEFAULT 1);
        """)
    store = Store(str(tmp_path), "/api")
    UsageStore(store)                                   # 这一步应该把列补上
    with store.connect() as db:
        columns = {row[1] for row in db.execute("PRAGMA table_info(relay_usage)")}
    assert "provider" in columns, columns
    UsageStore(store)                                   # 重复构造不能炸（幂等）


def test_existing_database_without_call_id_is_migrated(tmp_path):
    """老库（有 relay_usage 但没 call_id 列）必须能自动补上。

    这条和 provider 那条是同一个坑的第二次：`call_id` 也是迁移才加的，
    而 `relay_usage_call` 这个唯一索引就建在它上面。
    """
    import sqlite3

    path = tmp_path / "forum.sqlite3"
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE relay_usage (
             key_hash TEXT NOT NULL, model TEXT NOT NULL, service_tier TEXT,
             provider TEXT, created INTEGER NOT NULL, input INTEGER NOT NULL DEFAULT 0,
             cache_read INTEGER NOT NULL DEFAULT 0, cache_write INTEGER NOT NULL DEFAULT 0,
             output INTEGER NOT NULL DEFAULT 0, calls INTEGER NOT NULL DEFAULT 1);
        """)
    store = Store(str(tmp_path), "/api")
    UsageStore(store)
    with store.connect() as db:
        columns = {row[1] for row in db.execute("PRAGMA table_info(relay_usage)")}
        indexes = {row[1] for row in db.execute("PRAGMA index_list(relay_usage)")}
    assert "call_id" in columns, columns
    assert "relay_usage_call" in indexes, indexes
    UsageStore(store)                                   # 幂等


def test_existing_database_is_migrated_to_account_ownership(tmp_path):
    """老库的**归属列**要改名并回填：`key_hash` → `user_id`。

    这条测的是「换个 key 就换个人」那个老账要怎么并到账号名下：
    老行里的值是一条 key 的 sha256，得换成它所属账号的 user_id，
    否则历史用量会全部变成孤儿（接口查出来是 0，而数据其实还在库里）。
    也顺带守住：认不出账号的老行**保持原值**——宁可自成一户，也不能记到别人名下。

    `relay_keys` 是这张老库自带的表（代码已经不再建它），回填正是靠它——
    这也是 `migrate_ownership` 里那句"只有 relay_keys 还在才回填"要处理的情况。
    """
    import hashlib
    import sqlite3

    def legacy_hash(secret):
        return hashlib.sha256(secret.encode()).hexdigest()

    path = tmp_path / "forum.sqlite3"
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE relay_keys (
             key_hash TEXT PRIMARY KEY, user_id TEXT, display_name TEXT NOT NULL,
             created INTEGER NOT NULL, last_used INTEGER,
             request_count INTEGER NOT NULL DEFAULT 0, disabled INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE relay_usage (
             call_id TEXT, key_hash TEXT NOT NULL, model TEXT NOT NULL, service_tier TEXT,
             provider TEXT, created INTEGER NOT NULL, input INTEGER NOT NULL DEFAULT 0,
             cache_read INTEGER NOT NULL DEFAULT 0, cache_write INTEGER NOT NULL DEFAULT 0,
             output INTEGER NOT NULL DEFAULT 0, calls INTEGER NOT NULL DEFAULT 1);
            CREATE TABLE season_balances (
             key_hash TEXT PRIMARY KEY, last_settled_day TEXT, input INTEGER NOT NULL DEFAULT 0,
             cache INTEGER NOT NULL DEFAULT 0, output INTEGER NOT NULL DEFAULT 0,
             updated INTEGER NOT NULL);
        """)
        db.execute("INSERT INTO relay_keys(key_hash,user_id,display_name,created) "
                   "VALUES(?,?,?,?)", (legacy_hash("tt_old_bound"), "u_owner", "old", 1))
        db.execute("INSERT INTO relay_keys(key_hash,display_name,created) VALUES(?,?,?)",
                   (legacy_hash("tt_old_orphan"), "orphan", 1))
        db.execute("INSERT INTO relay_usage(key_hash,model,created,input) VALUES(?,?,?,?)",
                   (legacy_hash("tt_old_bound"), "deepseek-chat", 1, 500))
        db.execute("INSERT INTO relay_usage(key_hash,model,created,input) VALUES(?,?,?,?)",
                   (legacy_hash("tt_old_orphan"), "deepseek-chat", 1, 700))
        db.execute("INSERT INTO season_balances(key_hash,updated) VALUES(?,?)",
                   (legacy_hash("tt_old_bound"), 1))

    store = Store(str(tmp_path), "/api")
    UsageStore(store)                      # 这一步应该完成改名 + 回填

    with store.connect() as db:
        assert "user_id" in {row["name"] for row in db.execute("PRAGMA table_info(relay_usage)")}
        usage = dict(db.execute("SELECT user_id, input FROM relay_usage").fetchall())
        balances = [row["user_id"] for row in db.execute("SELECT user_id FROM season_balances")]
    # 绑过账号的老行并进账号；没绑的保持自己的 key hash（自成一户）
    assert usage["u_owner"] == 500
    assert usage[legacy_hash("tt_old_orphan")] == 700
    assert balances == ["u_owner"]
    UsageStore(store)                      # 幂等：再来一遍不能炸


def test_relay_tables_are_dropped_from_an_old_database(tmp_path):
    """老库里的 `relay_keys` / `relay_secrets` 要被删掉。

    两张表里装的是"这个人的上游 API key"（明文）与发出去的凭据。中转删掉之后
    没有任何代码读它们，留着等于把一份没人管的凭据留在生产库里——
    所以 `UsageStore` 起来时顺手清掉（排在 `migrate_ownership` 之后，
    因为回填要靠 `relay_keys`）。
    """
    import sqlite3

    path = tmp_path / "forum.sqlite3"
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE relay_keys (key_hash TEXT PRIMARY KEY, user_id TEXT,
             display_name TEXT NOT NULL, created INTEGER NOT NULL, last_used INTEGER,
             request_count INTEGER NOT NULL DEFAULT 0, disabled INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE relay_secrets (key_hash TEXT PRIMARY KEY, upstream_url TEXT NOT NULL,
             upstream_secret TEXT NOT NULL, created INTEGER NOT NULL);
        """)
        db.execute("INSERT INTO relay_secrets VALUES('deadbeef','https://api.example','sk-x',1)")

    store = Store(str(tmp_path), "/api")
    UsageStore(store)
    with store.connect() as db:
        tables = {row["name"] for row in
                  db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert "relay_keys" not in tables and "relay_secrets" not in tables, tables
    UsageStore(store)                      # 幂等
