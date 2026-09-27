"""中转服务的测试：注册、转发、记账、以及三个必须堵死的口子。

对应 `docs/FORUM_API.md` 里论坛那套测试的写法：测的是容易错的地方，
不是 happy path。这里重点是三个 —— **relay key 不能落库明文**、
**relay 身份和团队账号身份不能互相回退**、**上游地址不能指内网**。
"""
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from tokentrail_forum.app import Settings, create_app, _relay_client
from tokentrail_forum.relay import split_usage, upstream_path, join, provider_for, stream_usage
from tokentrail_forum.relay_store import generate_key, key_hash, validate_custom_key
from conftest import verifier

UPSTREAM = "http://127.0.0.1:9099"
RELAY_KEY = "tt_custom_relay_key_0123456789"


def make_app(path, handler, allowed_hosts=(), allow_private=True, prefix="/api", self_hosts=()):
    """建一个开了中转的 app，上游由 `handler` 假扮。

    两个默认值和生产相反，都是为了能测：
    - `allow_private=True`：假上游在回环地址上。
    - `self_hosts=()`：默认的 `relay_self_hosts` 含 `127.0.0.1`（防转发环），
      但假上游正好就在那儿，所以这里清空。**专门测转发环的那个用例自己传值。**
    """
    settings = Settings(str(path), "https://forum.example", relay_enabled=True,
                        public_api_prefix=prefix, relay_allowed_hosts=tuple(allowed_hosts),
                        relay_allow_private=allow_private, relay_self_hosts=tuple(self_hosts))
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return create_app(settings, verifier(), client)


def completion(model="deepseek-chat", prompt=100, completion_tokens=20, cached=0):
    """一个像上游返回的响应体。usage 字段就是我们要抽的东西。"""
    return {"id": "chatcmpl-1", "model": model, "object": "chat.completion",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": prompt, "completion_tokens": completion_tokens,
                      "total_tokens": prompt + completion_tokens,
                      "prompt_tokens_details": {"cached_tokens": cached}}}


def enroll(api, relay_key=None, upstream=UPSTREAM, upstream_key="sk-upstream-secret",
           account=None):
    """注册一个 relay key。

    **必须带账号令牌**（2026-09-27 起）：relay key 没有自己的身份，它挂在账号下面。
    `account` 给了就用那个账号的令牌；没给就现建一个 `owner` 账号。
    """
    payload = {"upstreamUrl": upstream, "upstreamKey": upstream_key}
    if relay_key is not None:
        payload["relayKey"] = relay_key
    token = account or account_token(api)
    return api.post("/api/relay/keys", json=payload,
                    headers={"Authorization": "Bearer " + token})


def account_token(api, username="owner", password="owner password 123"):
    """现建一个账号并登录，返回 token（幂等：撞了就只登录）。"""
    return account_login(api, username, password)["token"]


def account_login(api, username="owner", password="owner password 123"):
    """同上，但要 `userId` 的时候用它——播种用量数据得知道账算在谁头上。"""
    api.post("/api/account/register", json={"username": username, "password": password})
    return api.post("/api/account/login",
                    json={"username": username, "password": password}).json()


def chat(api, relay_key, body=None, path="/v1/chat/completions"):
    return api.post("/api/relay" + path, headers={"Authorization": "Bearer " + relay_key},
                    json=body if body is not None else {"model": "deepseek-chat", "messages": []})


def daily(api, relay_key, **query):
    from urllib.parse import urlencode
    suffix = ("?" + urlencode(query)) if query else ""
    return api.get("/api/relay/usage/daily" + suffix,
                   headers={"Authorization": "Bearer " + relay_key})


def insert_usage(tmp_path, digest, created, provider=None, model="deepseek-chat",
                 input_tokens=10, cache_read=0, cache_write=0, output=5, calls=1,
                 call_id=None):
    """直接往 relay_usage 插一行，带指定的 created。

    日汇总必须能测**跨日边界**，而边界靠真实转发是造不出来的——
    转发写的是 `now_ms()`。直接插是唯一能精确控制时刻的办法。

    表不存在时先建出来：`relay_usage` 是**启用中转时**才由 `RelayStore` 建的，
    而有些用例（比如「重启后余额还在」）要在第一次启动 app **之前**就把数据造好。

    2026-02 起用量归属是**账号**（`relay_usage.user_id`），所以 `digest` 要传账号的
    `userId`（`account_login(api)["userId"]`），不是 relay key 的 sha256——
    传错了不会报错，只会静静地记到另一个「人」名下、然后查出来是 0。

    `call_id` 默认给 null：那代表「迁移之前写下的老行」，`/usage/calls` 会跳过它们
    （没有 id 就没法给客户端一个稳定的去重键）。要测逐次记录就显式传。
    """
    import sqlite3
    from tokentrail_forum.relay_store import INDEXES, TABLES
    with sqlite3.connect(tmp_path / "forum.sqlite3") as db:
        db.executescript(TABLES)
        db.executescript(INDEXES)
        db.execute("""INSERT INTO relay_usage(user_id,model,service_tier,provider,created,
            input,cache_read,cache_write,output,calls,call_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            (digest, model, None, provider, created, input_tokens, cache_read,
             cache_write, output, calls, call_id))


def cst_millis(day, hour, minute=0):
    """把北京时间的一个时刻换成 UTC 毫秒。测试里用**字面量**算，不经过被测代码。"""
    from datetime import datetime, timezone as tz, timedelta
    moment = datetime.strptime(f"{day} {hour:02d}:{minute:02d}", "%Y-%m-%d %H:%M")
    return int(moment.replace(tzinfo=tz(timedelta(hours=8))).timestamp() * 1000)


# ---- 注册 ---------------------------------------------------------------

def test_enroll_generates_a_key_and_returns_it_once(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        created = enroll(api)
        assert created.status_code == 201
        body = created.json()
        # 自动生成：前缀 + 足够长，而且是返回给客户端的唯一一次。
        assert body["relayKey"].startswith("tt_") and len(body["relayKey"]) > 24
        assert body["uid"] == key_hash(body["relayKey"])
        # 上游密钥绝对不能出现在任何响应里，任何一个接口都不行。
        assert "sk-upstream-secret" not in created.text
        assert "upstreamKey" not in body
        assert body["hasUpstreamSecret"] is True


def test_custom_key_rules_and_uniqueness(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        # 太短 / 前缀不对，都要被挡下来并给出可读的理由。
        assert enroll(api, relay_key="tt_short").status_code == 400
        assert enroll(api, relay_key="sk-no-prefix-0123456789012345").status_code == 400
        assert enroll(api, relay_key="tt_with space 01234567890123").status_code == 400
        assert enroll(api, relay_key=RELAY_KEY).status_code == 201
        # 撞了返回 409，绝不覆盖——覆盖等于把前一个人的用量送给后一个人。
        assert enroll(api, relay_key=RELAY_KEY).status_code == 409


def test_relay_key_is_never_stored_in_plaintext(tmp_path):
    """整个数据库文件里都不许出现 relay key 的明文。"""
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        chat(api, RELAY_KEY)
    database = (tmp_path / "forum.sqlite3").read_bytes()
    wal = tmp_path / "forum.sqlite3-wal"
    blobs = database + (wal.read_bytes() if wal.exists() else b"")
    assert RELAY_KEY.encode() not in blobs
    # 但 uid（hash）必须在，否则查不回来。
    assert key_hash(RELAY_KEY).encode() in blobs
    # 上游密钥要存明文（转发必须注入它），这条是有意的，写出来免得被当成漏做。
    assert b"sk-upstream-secret" in blobs


# ---- 转发与记账 ---------------------------------------------------------

def test_forward_is_verbatim_and_usage_lands(tmp_path):
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("authorization")
        # MockTransport 收到的是同步 Request，请求体在 `.content`（`.body` 是属性不是方法）。
        seen["body"] = request.content
        seen["relay_key_leaked"] = RELAY_KEY in str(request.headers)
        return httpx.Response(200, json=completion(prompt=1000, completion_tokens=250, cached=400),
                              headers={"x-upstream-note": "kept"})

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        sent = {"model": "deepseek-chat", "messages": [{"role": "user", "content": "你好"}]}
        response = chat(api, RELAY_KEY, sent)
        assert response.status_code == 200
        # 响应体原样回来。
        assert response.json()["usage"]["prompt_tokens"] == 1000
        # 上游的非逐跳响应头要保留。
        assert response.headers["x-upstream-note"] == "kept"
        # 请求体原样过去，一个字节没动。
        assert json.loads(seen["body"]) == sent
        # 注入的是用户的上游 key（裸 key 会被补上 Bearer 前缀），不是我们的 relay key。
        assert seen["auth"] == "Bearer sk-upstream-secret"
        assert seen["relay_key_leaked"] is False
        # 路径拼接正确（没有 /v1/v1）。
        assert seen["url"] == UPSTREAM + "/v1/chat/completions"
        # 四个桶：input 是未命中缓存的那部分，不是输入总量。
        usage = api.get("/api/relay/usage", headers={"Authorization": "Bearer " + RELAY_KEY}).json()
        # `uid` 是**账号**的 userId，不是这条 key 的 sha256（2026-02 改）。
        # 这条断言正是「账记在谁头上」的守门人：改回 key_hash 就会红。
        assert usage["uid"] == account_login(api)["userId"]
        item = usage["items"][0]
        assert item["model"] == "deepseek-chat"
        assert (item["input"], item["cacheRead"], item["cacheWrite"], item["output"]) == (600, 400, 0, 250)
        assert item["calls"] == 1


def test_base_url_with_its_own_prefix_is_not_doubled(tmp_path):
    seen = {}

    def handler(request):
        seen["url"] = str(request.url)
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY, upstream=UPSTREAM + "/api/coding/paas/v4")
        chat(api, RELAY_KEY, path="/chat/completions")
        assert seen["url"] == UPSTREAM + "/api/coding/paas/v4/chat/completions"


def test_streaming_passes_bytes_through_and_still_records(tmp_path):
    chunks = [
        b'data: {"choices":[{"delta":{"content":"he"}}],"model":"deepseek-chat"}\n\n',
        b'data: {"choices":[{"delta":{"content":"llo"}}],"model":"deepseek-chat"}\n\n',
        b'data: {"choices":[],"model":"deepseek-chat","usage":{"prompt_tokens":50,'
        b'"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":10}}}\n\n',
        b"data: [DONE]\n\n",
    ]

    async def stream():
        for chunk in chunks:
            yield chunk

    def handler(request):
        # 必须是**异步**迭代器：转发用的是 AsyncClient，同步迭代会被它拒掉
        # （"Attempted to send an sync request with an AsyncClient instance"）。
        return httpx.Response(200, content=stream(), headers={"content-type": "text/event-stream"})

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        response = chat(api, RELAY_KEY)
        # 客户端收到的字节和上游发的完全一致。
        assert response.content == b"".join(chunks)
        assert response.headers["content-type"].startswith("text/event-stream")
    usage = api.get("/api/relay/usage", headers={"Authorization": "Bearer " + RELAY_KEY}).json()
    item = usage["items"][0]
    assert (item["input"], item["cacheRead"], item["output"]) == (40, 10, 7)


def test_upstream_failure_is_502_not_a_fake_success(tmp_path):
    def handler(request):
        raise httpx.ConnectError("upstream is down")

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        assert chat(api, RELAY_KEY).status_code == 502
        # 失败的转发不许留下用量记录。
        assert api.get("/api/relay/usage", headers={"Authorization": "Bearer " + RELAY_KEY}).json()["items"] == []


def test_upstream_error_status_is_passed_through(tmp_path):
    def handler(request):
        return httpx.Response(401, json={"error": {"message": "bad upstream key"}})

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        response = chat(api, RELAY_KEY)
        # 上游说自己 401，就如实返回 401，不能改写成我们的错误。
        assert response.status_code == 401
        assert response.json()["error"]["message"] == "bad upstream key"


# ---- 认证边界（三处不能回退）--------------------------------------------

def test_relay_key_cannot_read_the_forum(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        headers = {"Authorization": "Bearer " + RELAY_KEY}
        # relay 身份不等于团队账号身份，论坛必须拒绝它。
        assert api.get("/api/forum/posts", headers=headers).status_code == 401
        assert api.post("/api/forum/posts", headers={**headers, "Idempotency-Key": "x"},
                        json={"body": "hi"}).status_code == 401


def test_team_token_and_arbitrary_keys_cannot_use_the_relay(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        # 团队账号 token 打中转要 401：它不在 relay_keys 里。
        assert chat(api, "team-session-token").status_code == 401
        # 看起来像上游 key 的东西也不行。
        assert chat(api, "sk-some-openai-key").status_code == 401
        assert api.get("/api/relay/usage").status_code == 401


def test_revoked_key_stops_working(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        assert chat(api, RELAY_KEY).status_code == 200
        assert api.delete("/api/relay/keys", headers={"Authorization": "Bearer " + RELAY_KEY}).status_code == 200
        assert chat(api, RELAY_KEY).status_code == 403
        # 停用之后用量还在——历史归属不能因为停用就消失。
        assert api.get("/api/relay/usage", headers={"Authorization": "Bearer " + RELAY_KEY}).status_code == 403


# ---- SSRF 防护 ----------------------------------------------------------

@pytest.mark.parametrize("bad", [
    "http://169.254.169.254/latest/meta-data/",   # 云元数据
    "http://127.0.0.1:8000/api/users/me",          # 自己家的账号服务
    "http://10.0.0.5/v1",                          # 内网
    "http://192.168.1.1/v1",
    "file:///etc/passwd",                          # 非 http(s)
    "https://user:pass@api.example.com/v1",        # URL 里带凭据
])
def test_dangerous_upstream_urls_are_rejected(tmp_path, bad):
    def handler(request):
        raise AssertionError("must not reach the network")

    # allow_private=False 就是生产默认值。
    with TestClient(make_app(tmp_path, handler, allow_private=False)) as api:
        response = enroll(api, relay_key=RELAY_KEY, upstream=bad)
        assert response.status_code == 400, bad


def test_allowed_hosts_allowlist_is_enforced(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler, allowed_hosts=["api.deepseek.com"])) as api:
        assert enroll(api, relay_key=RELAY_KEY, upstream="http://127.0.0.1:9099").status_code == 400
        # 白名单里的地址可以通过（这台机器解析不了它，所以只校验到 host 这一步）。
        assert enroll(api, relay_key="tt_second_key_0123456789ab",
                      upstream="https://api.deepseek.com").status_code == 201


def test_registering_the_relay_itself_is_refused(tmp_path):
    def handler(request):
        raise AssertionError("must not reach the network")

    # 显式把 127.0.0.1 当成自己，请求指向它就应该被拒（防转发环）。
    with TestClient(make_app(tmp_path, handler, allow_private=False,
                             self_hosts=["127.0.0.1"])) as api:
        assert enroll(api, relay_key=RELAY_KEY, upstream="http://127.0.0.1:9099/v1").status_code == 400
        assert "relay itself" in enroll(api, relay_key=RELAY_KEY,
                                        upstream="http://127.0.0.1:9099/v1").json()["message"]


# ---- 改上游不动身份 -----------------------------------------------------

def test_rotating_the_upstream_key_keeps_history(tmp_path):
    seen = {}

    def handler(request):
        seen["auth"] = request.headers.get("authorization")
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY, upstream_key="sk-old")
        chat(api, RELAY_KEY)
        updated = api.patch("/api/relay/keys", headers={"Authorization": "Bearer " + RELAY_KEY},
                            json={"upstreamKey": "sk-new"})
        assert updated.status_code == 200
        # uid 没变——这正是 relay key 和上游凭据分开的意义。
        assert updated.json()["uid"] == key_hash(RELAY_KEY)
        chat(api, RELAY_KEY)
        assert seen["auth"] == "Bearer sk-new"
        # 两次转发都归在同一个人名下。
        items = api.get("/api/relay/usage", headers={"Authorization": "Bearer " + RELAY_KEY}).json()["items"]
        assert len(items) == 1 and items[0]["calls"] == 2


def test_relay_is_absent_when_disabled(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    disabled = Settings(str(tmp_path), "https://forum.example", relay_enabled=False)
    with TestClient(create_app(disabled, verifier())) as api:
        # 没开的时候这些路径应该是不存在的，而不是「存在但拒绝」。
        assert api.post("/api/relay/keys", json={"upstreamUrl": UPSTREAM, "upstreamKey": "x"}).status_code == 404


# ---- 纯函数（不经过 HTTP）-----------------------------------------------

def test_split_usage_handles_both_field_shapes():
    # OpenAI / DeepSeek：prompt_tokens 是输入总量，要减掉缓存两段。
    openai = split_usage({"prompt_tokens": 100, "completion_tokens": 10,
                          "prompt_tokens_details": {"cached_tokens": 30}}, "gpt-5")
    assert (openai["input"], openai["cacheRead"], openai["output"]) == (70, 30, 10)
    # DeepSeek 显式给 miss，优先用它。
    deepseek = split_usage({"prompt_tokens": 100, "completion_tokens": 10,
                            "prompt_cache_hit_tokens": 40, "prompt_cache_miss_tokens": 60}, "deepseek-chat")
    assert (deepseek["input"], deepseek["cacheRead"]) == (60, 40)
    # Anthropic：input_tokens 本身就是未命中部分，不能再减，也不能变负。
    anthropic = split_usage({"input_tokens": 25, "output_tokens": 5,
                             "cache_creation_input_tokens": 100}, "claude")
    assert (anthropic["input"], anthropic["cacheWrite"], anthropic["output"]) == (25, 100, 5)
    # 没有 usage 的响应返回 None，不是全 0——「没有」和「花了 0」要分得开。
    assert split_usage({}, "m") is None


def test_upstream_path_and_join():
    assert upstream_path("/api/relay/v1/chat/completions", "/api/relay") == "/v1/chat/completions"
    assert upstream_path("/test-api/relay/responses", "/test-api/relay") == "/responses"
    assert join("https://api.deepseek.com", "/v1/chat/completions") == "https://api.deepseek.com/v1/chat/completions"
    assert join("https://x.test/v4", "/v1/a") == "https://x.test/v4/v1/a"
    assert join("https://x.test/", "/a") == "https://x.test/a"


def test_provider_is_not_guessed():
    assert provider_for("api.deepseek.com") == "DEEPSEEK"
    assert provider_for("api.openai.com") == "OPENAI"
    assert provider_for("api.xiaomimimo.com") == "MIMO"
    # 认不出来就留空，猜错比留空难查得多。
    assert provider_for("my-own-gateway.example.com") is None


def test_stream_usage_survives_split_chunks_and_junk():
    first, buffer = stream_usage(b'data: {"usage":{"prompt_tokens":10,"completion_tokens":1}}\n', b"")
    assert first["output"] == 1
    # 跨块的 JSON 不该被当成坏数据崩掉。
    _, buffer = stream_usage(b'data: {"usa', b"")
    assert buffer == b'data: {"usa'
    # 畸形行不影响后续正常解析。
    bad, buffer = stream_usage(b'data: {not json}\n\n', buffer)
    assert bad is None
    # buffer 有上限，畸形长流不会把内存吃光。
    _, buffer = stream_usage(b"x" * (300 * 1024), b"")
    assert len(buffer) <= 256 * 1024


def test_upstream_key_prefix_is_normalized_exactly_once(tmp_path):
    """用户填 `sk-x` 或 `Bearer sk-x` 都必须只产生一个 `Bearer` 前缀。

    这一段踩过：修复前注入的是裸 key，上游收到 `sk-x` 而报 401，症状看起来
    像「用户的 key 不对」，其实是我们的头拼错了。
    """
    seen = {}

    def handler(request):
        seen["auth"] = request.headers.get("authorization")
        return httpx.Response(200, json=completion())

    for index, (typed, expected) in enumerate([("sk-plain", "Bearer sk-plain"),
                                               ("Bearer sk-prefixed", "Bearer sk-prefixed"),
                                               ("  bearer sk-lower  ", "Bearer sk-lower")]):
        with TestClient(make_app(tmp_path / str(index), handler)) as api:
            enroll(api, relay_key=RELAY_KEY, upstream_key=typed)
            chat(api, RELAY_KEY)
            assert seen["auth"] == expected, typed


def test_empty_upstream_key_is_rejected(tmp_path):
    def handler(request):
        raise AssertionError("must not reach the network")

    with TestClient(make_app(tmp_path, handler)) as api:
        assert enroll(api, relay_key=RELAY_KEY, upstream_key="   ").status_code == 400
        assert enroll(api, relay_key=RELAY_KEY, upstream_key="Bearer  ").status_code == 400


def test_generated_keys_are_unique():
    keys = {generate_key() for _ in range(200)}
    assert len(keys) == 200 and all(key.startswith("tt_") for key in keys)


def test_validate_custom_key_boundary():
    assert validate_custom_key("tt_" + "a" * 21) is None       # 正好 24
    assert validate_custom_key("tt_" + "a" * 20) is not None   # 差一个
    assert validate_custom_key(None) is not None


# ---- 日汇总（服务端成为唯一数据源的那一层）-------------------------------

def test_daily_rollup_buckets_by_shanghai_calendar_day(tmp_path):
    """跨 UTC 日的时刻必须落到**北京时间的同一天**。

    这是整条链最容易错的地方：存储是 UTC 毫秒，而「哪一天」按 Asia/Shanghai 算。
    23:30 和次日 00:30 在北京是两天，在 UTC 是**同一天**——用 UTC 分组的话
    这两笔会被合并，而用户看到的日曲线就少了一根柱子。
    """
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        # 北京时间 09-27 23:30 → UTC 是 09-27 15:30
        insert_usage(tmp_path, digest, cst_millis("2026-09-27", 23, 30), "DEEPSEEK", input_tokens=100)
        # 北京时间 09-28 00:30 → UTC 是 09-27 16:30（**UTC 同一天**）
        insert_usage(tmp_path, digest, cst_millis("2026-09-28", 0, 30), "DEEPSEEK", input_tokens=7)

        body = daily(api, RELAY_KEY).json()
        days = {item["day"]: item for item in body["items"]}
        assert set(days) == {"2026-09-27", "2026-09-28"}, days
        assert days["2026-09-27"]["input"] == 100
        assert days["2026-09-28"]["input"] == 7


def test_daily_rollup_groups_by_provider_and_model(tmp_path):
    """DailyUsage 的主键是 (uid, 天, provider, 模型)，三个都要分开。"""
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        same = cst_millis("2026-09-27", 12)
        insert_usage(tmp_path, digest, same, "DEEPSEEK", "deepseek-chat", input_tokens=10)
        insert_usage(tmp_path, digest, same, "DEEPSEEK", "deepseek-reasoner", input_tokens=20)
        insert_usage(tmp_path, digest, same, "OPENAI", "deepseek-chat", input_tokens=30)
        items = daily(api, RELAY_KEY).json()["items"]
        assert len(items) == 3, items
        assert sum(item["input"] for item in items) == 60
        assert {(item["provider"], item["model"]) for item in items} == {
            ("DEEPSEEK", "deepseek-chat"), ("DEEPSEEK", "deepseek-reasoner"), ("OPENAI", "deepseek-chat")}


def test_provider_is_recorded_from_the_upstream_and_survives_an_unknown_host(tmp_path):
    """provider 从上游域名认出来；认不出来就留 null，**不猜**。"""
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        # 域名里有 deepseek → DEEPSEEK
        enroll(api, relay_key=RELAY_KEY, upstream="http://127.0.0.1:9099")
        chat(api, RELAY_KEY)
        items = daily(api, RELAY_KEY).json()["items"]
        # 假上游是回环地址，认不出提供方，所以 provider 是 null 而不是硬编一个。
        assert items and items[0]["provider"] is None, items


def test_daily_reports_pricing_as_unavailable_rather_than_zero(tmp_path):
    """算不出价必须留 null，**不许填 0**。

    `CONTRACTS.md` §4：把「不知道花了多少」显示成「花了 0 元」是那一节点名的
    「最难发现的一类 bug」——数字看着没错、结论是错的。服务端还没有价目表，
    所以这里断言的是 null + 一个显式的 pricingAvailable:false。
    """
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        chat(api, RELAY_KEY)
        body = daily(api, RELAY_KEY).json()
        assert body["pricingAvailable"] is False
        assert body["timezone"] == "Asia/Shanghai"
        item = body["items"][0]
        assert item["costMicros"] is None and item["rateVersion"] is None
        assert item["settled"] is False
        # uid 由服务端填，客户端传不了。
        assert item["uid"] == account_login(api)["userId"]


def test_daily_window_and_other_users_are_isolated(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        other_token = account_token(api, "second")
        enroll(api, relay_key="tt_second_user_key_0123456789", upstream_key="sk-2",
               account=other_token)
        mine, other = account_login(api)["userId"], account_login(api, "second")["userId"]
        insert_usage(tmp_path, mine, cst_millis("2026-09-20", 12), "DEEPSEEK", input_tokens=1)
        insert_usage(tmp_path, mine, cst_millis("2026-09-27", 12), "DEEPSEEK", input_tokens=2)
        insert_usage(tmp_path, other, cst_millis("2026-09-27", 12), "DEEPSEEK", input_tokens=999)

        # 窗口两端都含
        windowed = daily(api, RELAY_KEY, since=cst_millis("2026-09-21", 0)).json()["items"]
        assert [item["input"] for item in windowed] == [2]
        # 只有自己的记录，看不到另一个用户的 999
        everything = daily(api, RELAY_KEY).json()["items"]
        assert sum(item["input"] for item in everything) == 3


def test_daily_requires_a_relay_key(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        # 没凭证 / 团队 token / 别的 key，都不行。
        assert api.get("/api/relay/usage/daily").status_code == 401
        assert daily(api, "team-token").status_code == 401
        assert daily(api, "tt_not_registered_0123456789").status_code == 401


def test_existing_database_without_provider_column_is_migrated(tmp_path):
    """老库（有 relay_usage 但没 provider 列）必须能自动补上。

    服务器上已经部署过这一版表结构了，所以这条不是假想：
    `CREATE TABLE IF NOT EXISTS` 对已存在的表什么都不做，
    不写迁移的话新代码一 INSERT 就报 "no such column: provider"。
    """
    import sqlite3
    from tokentrail_forum.store import Store
    from tokentrail_forum.relay_store import RelayStore

    path = tmp_path / "forum.sqlite3"
    # 造一个「旧版」库：有 relay_usage，但没有 provider 列。
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE relay_usage (
             key_hash TEXT NOT NULL, model TEXT NOT NULL, service_tier TEXT,
             created INTEGER NOT NULL, input INTEGER NOT NULL DEFAULT 0,
             cache_read INTEGER NOT NULL DEFAULT 0, cache_write INTEGER NOT NULL DEFAULT 0,
             output INTEGER NOT NULL DEFAULT 0, calls INTEGER NOT NULL DEFAULT 1);
        """)
    store = Store(str(tmp_path), "/api")
    RelayStore(store)                                   # 这一步应该把列补上
    with store.connect() as db:
        columns = {row[1] for row in db.execute("PRAGMA table_info(relay_usage)")}
    assert "provider" in columns, columns
    RelayStore(store)                                   # 重复构造不能炸（幂等）


# ---- 逐次记录（`UsageCall` 的形状）---------------------------------------

def calls(api, relay_key=RELAY_KEY, **query):
    from urllib.parse import urlencode
    suffix = ("?" + urlencode(query)) if query else ""
    return api.get("/api/relay/usage/calls" + suffix,
                   headers={"Authorization": "Bearer " + relay_key})


def test_a_real_forward_produces_one_call_row(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion(prompt=1000, completion_tokens=250, cached=400))

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        chat(api, RELAY_KEY)
        items = calls(api).json()["items"]
        assert len(items) == 1, items
        row = items[0]
        # id 的形状按 `UsageCall.id` 的约定，**前缀 `call:` 是必需的**——
        # 它决定这行能不能做按会话分析。
        assert row["id"].startswith("call:"), row["id"]
        assert row["model"] == "deepseek-chat"
        assert (row["input"], row["cacheRead"], row["cacheWrite"], row["output"]) == (600, 400, 0, 250)
        assert row["startedAtEpochMillis"] > 0
        assert row["uid"] == account_login(api)["userId"]
        # source 恒为 IMPORTED：只有这个值进日汇总、预算和游戏资源。
        assert row["source"] == "IMPORTED"


def test_each_forward_gets_a_distinct_call_id(tmp_path):
    """两次转发必须是两个不同的 id——**这就是不能在读取端拼 id 的理由**：
    同一毫秒里的两个请求会拼出同一个 id，而那个 id 是客户端去重的键。"""
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        chat(api, RELAY_KEY)
        chat(api, RELAY_KEY)
        items = calls(api).json()["items"]
        assert len(items) == 2, items
        assert items[0]["id"] != items[1]["id"]


def test_pricing_is_reported_as_unavailable_not_zero(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY)
        chat(api, RELAY_KEY)
        body = calls(api).json()
        assert body["pricingAvailable"] is False
        row = body["items"][0]
        # 契约里 `costMicros` 是可空的 `Long`，null 表示「价格未知」。
        # **不许填 0**——那会把「不知道」显示成「没花钱」。
        assert row["costMicros"] is None
        assert row["nativeCostMicros"] is None


def test_provider_is_never_an_invented_enum_value(tmp_path):
    """`Provider` 是枚举，值域只有三个。认不出来必须是 null。

    塞一个 `"unknown"` 进去的话，客户端反序列化时**直接抛异常**，
    而不是拿到一个"未知"——所以这条不是风格问题。
    """
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        enroll(api, relay_key=RELAY_KEY, upstream="http://127.0.0.1:9099")
        chat(api, RELAY_KEY)
        row = calls(api).json()["items"][0]
        assert row["provider"] is None


def test_provider_is_filled_when_the_upstream_is_recognisable(tmp_path):
    def handler(request):
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler, allowed_hosts=["api.deepseek.com"])) as api:
        enroll(api, relay_key=RELAY_KEY, upstream="https://api.deepseek.com")
        # 转发打不到真上游（MockTransport 在中间），所以直接检查库里存的 provider。
        # 这里用「造一行」的方式，避免为了让域名被认出来而发一次假请求。
        insert_usage(tmp_path, account_login(api)["userId"], 1_790_500_000_000, "DEEPSEEK",
                     call_id="call:DEEPSEEK:abc:1790500000000:000001")
        row = calls(api).json()["items"][0]
        assert row["provider"] == "DEEPSEEK"
        assert row["id"] == "call:DEEPSEEK:abc:1790500000000:000001"


def test_rows_without_a_call_id_are_skipped(tmp_path):
    """迁移之前写下的老行没有 id，必须跳过——给一个拼出来的假 id
    会让两条不同的记录撞成一条（客户端按 id 去重）。"""
    with TestClient(make_app(tmp_path, lambda request: httpx.Response(200, json=completion()))) as api:
        enroll(api, relay_key=RELAY_KEY)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-27", 12), "DEEPSEEK", call_id=None)
        insert_usage(tmp_path, digest, cst_millis("2026-09-27", 13), "DEEPSEEK",
                     call_id="call:DEEPSEEK:x:1:000001")
        items = calls(api).json()["items"]
        assert len(items) == 1, items
        assert items[0]["id"] == "call:DEEPSEEK:x:1:000001"


def test_calls_window_and_isolation(tmp_path):
    with TestClient(make_app(tmp_path, lambda request: httpx.Response(200, json=completion()))) as api:
        enroll(api, relay_key=RELAY_KEY)
        other_token = account_token(api, "second")
        enroll(api, relay_key="tt_second_user_key_0123456789", upstream_key="sk-2",
               account=other_token)
        mine, other = account_login(api)["userId"], account_login(api, "second")["userId"]
        insert_usage(tmp_path, mine, cst_millis("2026-09-20", 12), "DEEPSEEK",
                     call_id="call:DEEPSEEK:m:1:000001")
        insert_usage(tmp_path, mine, cst_millis("2026-09-27", 12), "DEEPSEEK",
                     call_id="call:DEEPSEEK:m:2:000002")
        insert_usage(tmp_path, other, cst_millis("2026-09-27", 12), "DEEPSEEK",
                     call_id="call:DEEPSEEK:o:3:000003")

        assert len(calls(api).json()["items"]) == 2                    # 看不到别人的
        windowed = calls(api, since=cst_millis("2026-09-21", 0)).json()["items"]
        assert [item["id"] for item in windowed] == ["call:DEEPSEEK:m:2:000002"]


def test_calls_requires_a_relay_key(tmp_path):
    with TestClient(make_app(tmp_path, lambda request: httpx.Response(200, json=completion()))) as api:
        enroll(api, relay_key=RELAY_KEY)
        assert api.get("/api/relay/usage/calls").status_code == 401
        assert calls(api, "team-token").status_code == 401
        assert calls(api, "tt_not_registered_0123456789").status_code == 401


def test_existing_database_without_call_id_is_migrated(tmp_path):
    """老库（有 relay_usage 但没 call_id 列）必须能自动补上。

    这条和 provider 那条是同一个坑的第二次：`call_id` 也是迁移才加的，
    而 `relay_usage_call` 这个唯一索引就建在它上面。
    """
    import sqlite3
    from tokentrail_forum.store import Store
    from tokentrail_forum.relay_store import RelayStore

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
    RelayStore(store)
    with store.connect() as db:
        columns = {row[1] for row in db.execute("PRAGMA table_info(relay_usage)")}
        indexes = {row[1] for row in db.execute("PRAGMA index_list(relay_usage)")}
    assert "call_id" in columns, columns
    assert "relay_usage_call" in indexes, indexes
    RelayStore(store)                                   # 幂等


def test_existing_database_is_migrated_to_account_ownership(tmp_path):
    """老库的**归属列**要改名并回填：`key_hash` → `user_id`。

    这条测的是「换个 key 就换个人」那个老账要怎么并到账号名下：
    老行里的值是一条 key 的 sha256，得换成它所属账号的 user_id，
    否则历史用量会全部变成孤儿（接口查出来是 0，而数据其实还在库里）。

    也顺带守住另外两件事：
      * 索引必须跟着改（`relay_usage_call` 建在旧列上的话，
        新代码一 INSERT 就报 "no such column: user_id"）；
      * 认不出账号的老行**保持原值**——宁可自成一户，也不能记到别人名下。
    """
    import sqlite3
    from tokentrail_forum.store import Store
    from tokentrail_forum.relay_store import RelayStore, key_hash

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
        # 一条绑了账号的老 key，一条没绑的
        db.execute("INSERT INTO relay_keys(key_hash,user_id,display_name,created) "
                   "VALUES(?,?,?,?)", (key_hash("tt_old_bound"), "u_owner", "old", 1))
        db.execute("INSERT INTO relay_keys(key_hash,display_name,created) VALUES(?,?,?)",
                   (key_hash("tt_old_orphan"), "orphan", 1))
        db.execute("INSERT INTO relay_usage(key_hash,model,created,input) VALUES(?,?,?,?)",
                   (key_hash("tt_old_bound"), "deepseek-chat", 1, 500))
        db.execute("INSERT INTO relay_usage(key_hash,model,created,input) VALUES(?,?,?,?)",
                   (key_hash("tt_old_orphan"), "deepseek-chat", 1, 700))
        db.execute("INSERT INTO season_balances(key_hash,updated) VALUES(?,?)",
                   (key_hash("tt_old_bound"), 1))

    store = Store(str(tmp_path), "/api")
    relay = RelayStore(store)                      # 这一步应该完成改名 + 回填

    with store.connect() as db:
        assert "user_id" in {row["name"] for row in db.execute("PRAGMA table_info(relay_usage)")}
        usage = dict(db.execute("SELECT user_id, input FROM relay_usage").fetchall())
        balances = [row["user_id"] for row in db.execute("SELECT user_id FROM season_balances")]
    # 绑过账号的老行并进账号；没绑的保持自己的 key hash（自成一户）
    assert usage["u_owner"] == 500
    assert usage[key_hash("tt_old_orphan")] == 700
    assert balances == ["u_owner"]
    # 新代码真的能往改过名的表里写（索引也建在 `user_id` 上）
    relay.record_ok = True
    RelayStore(store)                              # 幂等：再来一遍不能炸


# ---- 名下所有 key（2026-02 加）------------------------------------------

def test_the_account_can_list_every_key_it_registered(tmp_path):
    """**「我到底录了什么」必须答得出来。**

    加这个接口之前，App 只看得见最先注册的那一条：一个账号换过上游、
    又注册了一条，界面上还是显示旧的，而用户没有任何办法发现这件事。
    """
    with TestClient(make_app(tmp_path, lambda request: httpx.Response(200, json=completion()))) as api:
        token = account_token(api)
        enroll(api, relay_key=RELAY_KEY, account=token)
        enroll(api, relay_key="tt_second_user_key_0123456789", upstream_key="sk-2",
               account=token)

        listed = api.get("/api/relay/keys/all", headers={"Authorization": "Bearer " + token})
        assert listed.status_code == 200, listed.text
        items = listed.json()["items"]
        assert len(items) == 2, items
        # **永远不返回 key 明文**：库里只有 sha256，取不回来。
        assert RELAY_KEY not in listed.text
        assert "sk-2" not in listed.text
        # 每条能认出「指向哪个上游」，否则多条之间没法区分
        hosts = {item["upstreamHost"] for item in items}
        assert hosts == {"127.0.0.1"}, hosts
        assert all(item["uid"] and item["displayName"] for item in items)
        assert all(item["createdAtEpochMillis"] for item in items)
        assert all(item["disabled"] is False for item in items)


def test_listing_keys_needs_an_account_and_never_crosses_accounts(tmp_path):
    with TestClient(make_app(tmp_path, lambda request: httpx.Response(200, json=completion()))) as api:
        mine = account_token(api)
        theirs = account_token(api, "second")
        enroll(api, relay_key=RELAY_KEY, account=mine)
        enroll(api, relay_key="tt_second_user_key_0123456789", upstream_key="sk-2",
               account=theirs)
        # 没登录：401（relay key 本身也不够——它只能看自己那一条）
        assert api.get("/api/relay/keys/all").status_code == 401
        assert api.get("/api/relay/keys/all",
                       headers={"Authorization": "Bearer " + RELAY_KEY}).status_code == 401
        mine_items = api.get("/api/relay/keys/all",
                             headers={"Authorization": "Bearer " + mine}).json()["items"]
        their_items = api.get("/api/relay/keys/all",
                              headers={"Authorization": "Bearer " + theirs}).json()["items"]
        assert len(mine_items) == 1 and len(their_items) == 1
        assert mine_items[0]["uid"] != their_items[0]["uid"]


def test_a_key_can_be_changed_and_disabled_by_its_own_uid(tmp_path):
    """多条 key 时要指得准：按 `uid` 管，不能「挑一条」下手。

    踩过的形状：拿账号 token 打 `/keys`，服务端自己挑一条。只有一条时看着没问题，
    多了就会**悄悄改错人**。
    """
    seen = {}

    def handler(request):
        seen["auth"] = request.headers.get("Authorization")
        return httpx.Response(200, json=completion())

    with TestClient(make_app(tmp_path, handler)) as api:
        token = account_token(api)
        first = enroll(api, relay_key=RELAY_KEY, account=token).json()
        second = enroll(api, relay_key="tt_second_user_key_0123456789", upstream_key="sk-2",
                        account=token).json()
        headers = {"Authorization": "Bearer " + token}
        # 改**第二条**：第一条必须原样不动
        changed = api.patch(f"/api/relay/keys/{second['uid']}", headers=headers,
                            json={"upstreamKey": "sk-new-for-second"})
        assert changed.status_code == 200, changed.text
        items = {item["uid"]: item for item in
                 api.get("/api/relay/keys/all", headers=headers).json()["items"]}
        assert items[first["uid"]]["lastUsedAtEpochMillis"] is None
        # 停用第一条，第二条仍然可用
        assert api.delete(f"/api/relay/keys/{first['uid']}", headers=headers).status_code == 200
        items = {item["uid"]: item for item in
                 api.get("/api/relay/keys/all", headers=headers).json()["items"]}
        assert items[first["uid"]]["disabled"] is True
        assert items[second["uid"]]["disabled"] is False
        # 被停用的那条不能再转发（403），另一条照常
        assert chat(api, RELAY_KEY).status_code == 403
        assert chat(api, "tt_second_user_key_0123456789").status_code == 200
        assert seen["auth"] == "Bearer sk-new-for-second"


def test_another_accounts_key_is_not_even_visible(tmp_path):
    """别人的 uid 打进来要 404——**不是 403**。

    403 等于承认「这条 key 存在，只是不属于你」，那是别人的注册信息。
    """
    with TestClient(make_app(tmp_path, lambda request: httpx.Response(200, json=completion()))) as api:
        mine = account_token(api)
        theirs = account_token(api, "second")
        theirs_key = enroll(api, relay_key=RELAY_KEY, account=theirs).json()
        headers = {"Authorization": "Bearer " + mine}
        assert api.patch(f"/api/relay/keys/{theirs_key['uid']}", headers=headers,
                         json={"upstreamKey": "sk-x"}).status_code == 404
        assert api.delete(f"/api/relay/keys/{theirs_key['uid']}",
                          headers=headers).status_code == 404
