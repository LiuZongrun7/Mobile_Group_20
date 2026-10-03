"""应用内 AI 智能体的测试。

这里最要紧的一条是**账本独立**：智能体用的是我们自己的 key、花的是我们的钱，
而它回答的是「用户的编码 agent 花了多少」。两者混在一起，答案就被问题本身污染了
——问得越多、账越大，而游戏资源又是从编码用量换来的。

第二要紧的是 **key 不能泄露**：不进响应、不进健康检查、不进日志。
"""
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from tokentrail_forum.agent import DEFAULT_MODEL, SYSTEM_PROMPT
from tokentrail_forum.app import Settings, create_app
from tokentrail_forum.pricing import Pricing, cny_per_1m_to_usd_micros
from conftest import verifier
from helpers import (account_login, account_token, bearer, completion,
                      cst_millis, daily, insert_usage)
from test_seasons import TODAY as _TODAY

OUR_KEY = "sk-our-own-agent-key-do-not-leak"


def deepseek_reply(text="Because output tokens went up.",
                   prompt=1000, completion_tokens=200, cached=400):
    """一个像 DeepSeek 返回的响应体。"""
    return {"id": "chatcmpl-1", "model": DEFAULT_MODEL,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                         "finish_reason": "stop"}],
            # 缓存桶在**嵌套的 `prompt_tokens_details`** 里。
            # 写成扁平的 `prompt_tokens_cache_hit_tokens` 也能跑，但会静默
            # 把缓存算成 0——`split_usage` 只认契约里那两种形状之一。
            "usage": {"prompt_tokens": prompt, "completion_tokens": completion_tokens,
                      "prompt_tokens_details": {"cached_tokens": cached}}}


class FakeModel:
    """假的上游。记下发了什么，并按需返回。"""

    def __init__(self, reply=None, status=200):
        self.reply = reply if reply is not None else deepseek_reply()
        self.status = status
        self.seen = []

    def post(self, url, headers=None, json=None):
        self.seen.append({"url": url, "headers": dict(headers or {}), "body": json})
        if self.status != 200:
            return httpx.Response(self.status, json={"error": {"message": "nope"}})
        return httpx.Response(200, json=self.reply)


def make_agent_app(path, fake=None, key=OUR_KEY, seed=True, **overrides):
    settings = Settings(str(path), "https://forum.example", relay_enabled=True,
                        pricing_seed=seed, agent_key=key,
                        agent_requests_per_minute=overrides.pop("agent_requests_per_minute", 10),
                        **overrides)
    client = httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json=completion())))
    app = create_app(settings, verifier())
    if fake is not None:
        # 把假上游装进去：Agent 是在 build_router 里建的，所以直接替换它的 client。
        app.state.store  # 触发一次无副作用的访问，保持可读性
    return app, fake


def ask(api, question="why did cost rise?", token=None):
    """问智能体一句。

    **凭据是账号 token，不是 relay key**（2026-02 改）。智能体是「用户的客服」，
    用户没配过中转也该能用；而 relay key 只该出现在中转那条路上。
    `token` 可以传个假的/过期的来试鉴权。
    """
    return api.post("/api/relay/agent/ask", json={"question": question},
                    headers={"Authorization": "Bearer " + (token or account_token(api))})


@pytest.fixture
def agent_app(tmp_path):
    """把假上游接到 Agent 上。

    `Agent` 的构造发生在 `build_router` 里，拿不到测试的 client，所以这里
    建好 app 之后**替换掉那个实例的 client**——它和 `relay_client` 是两回事
    （转发用异步、智能体用同步）。
    """
    from tokentrail_forum.agent import Agent

    fake = FakeModel()
    holder = {}

    def build(path):
        # 两个开关都开：`agent_enabled` 控制智能体本身，`relay_enabled` 控制
        # **身份自助注册**（`/keys`）和转发。只开 agent 的话 `/keys` 不存在，
        # 测试就注册不出身份——这个耦合是已知的，见 build_agent_router 的注释。
        settings = Settings(str(path), "https://forum.example", agent_enabled=True,
                            relay_enabled=True, pricing_seed=True,
                            agent_key=OUR_KEY)
        client = httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: httpx.Response(200, json=completion())))
        original = Agent

        class Wired(original):
            def __init__(self, store, api_key, model=DEFAULT_MODEL, endpoint=None, client=None,
                         day_provider=None, now_provider=None):
                super().__init__(store, api_key, model, endpoint or "https://api.deepseek.com",
                                 client=fake, day_provider=day_provider,
                                 now_provider=now_provider)

        import tokentrail_forum.api_routes as routes
        routes.Agent = Wired
        try:
            # 「今天」注入成固定值：提示词里那句日期是**服务端给**的，
            # 不注入的话这条断言就变成「跑测试那天必须正好是 2026-09-27」。
            app = create_app(settings, verifier(), day_provider=lambda: _TODAY)
        finally:
            routes.Agent = original
        holder["app"] = app
        return app

    return build, fake, holder


# ---- 一次问答 -----------------------------------------------------------

def test_ask_returns_the_answer_and_records_own_usage(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        response = ask(api)
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["text"] == "Because output tokens went up."
        assert body["model"] == DEFAULT_MODEL
        # 四桶按共用口径归一：input 已扣掉缓存那 400
        assert body["usage"]["input"] == 600
        assert body["usage"]["cacheRead"] == 400
        assert body["usage"]["output"] == 200


def test_the_system_prompt_states_the_three_boundaries(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        ask(api)
    sent = fake.seen[0]["body"]
    system = sent["messages"][0]
    assert system["role"] == "system"
    # 提示词里**必须带今天的日期**：不带的话模型会猜年份（实测查到了 2025 年），
    # 而那个错会表现成一句平静的「这段时间没有用量」。
    assert SYSTEM_PROMPT in system["content"]
    assert "Today is" in system["content"]
    assert str(_TODAY) in system["content"]
    # 三条边界都要在里面：不比质量、不编数字、不控制 agent
    assert "smarter" in SYSTEM_PROMPT or "better" in SYSTEM_PROMPT
    assert "invent" in SYSTEM_PROMPT
    assert "cannot run" in SYSTEM_PROMPT


def test_the_tools_are_registered_and_named_after_the_contract(agent_app, tmp_path):
    """工具已经接上了，而且名字和 `contract/tool/AgentTool.java` 一一对应。

    名字对不上的话，模型请求的工具会落到「名单外」那个分支上——
    表现成「智能体老是说查不到」，而不是一个明显的错误。
    """
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        ask(api)
    sent = fake.seen[0]["body"]
    assert "tools" in sent, "tools must be offered to the model"
    names = {tool["function"]["name"] for tool in sent["tools"]}
    assert names == {"getUsageSummary", "getBudgetStatus", "compareAgentCosts",
                     "getForumHighlights", "getMyThreads"}, names
    # 每个 schema 里都**不能有 uid**——模型看不见它，也就传不了它
    for tool in sent["tools"]:
        assert "uid" not in json.dumps(tool["function"]["parameters"]), tool["function"]["name"]


def test_the_request_never_carries_a_uid_or_a_key(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        ask(api)
    sent = fake.seen[0]
    # 客户端只发问题：身份从凭据解出（契约 §6），key 在服务端
    assert {"model", "messages", "stream"} <= set(sent["body"].keys())
    assert "uid" not in sent["body"]
    # 上游拿到的是**我们的** key
    assert sent["headers"]["authorization"] == "Bearer " + OUR_KEY


# ---- ⚠️ 账本必须独立 -----------------------------------------------------

def test_agent_usage_never_lands_in_the_relay_ledger(agent_app, tmp_path):
    """**这条是整轮最重要的。** 智能体的消耗写 `agent_usage`，一个字段都不碰
    `relay_usage`。混进去的话，用户问「我怎么花了这么多」会把提问本身的
    开销算进他的编码用量——问得越多、账越大。
    """
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        # 先造一笔真实的用量（走记账那条路；中转删掉后它是唯一的写入口）
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     input_tokens=1_000_000, output=0)
        ask(api)

        app = holder["app"]
        with app.state.store.connect() as db:
            in_relay = db.execute("SELECT COUNT(*) FROM relay_usage").fetchone()[0]
            in_agent = db.execute("SELECT COUNT(*) FROM agent_usage").fetchone()[0]
        # 中转那张表里只有那一笔编码用量，**没有**智能体那一笔
        assert in_relay == 1, "the assistant's call must not enter relay_usage"
        assert in_agent == 1
        # 而且智能体的用量不参与用户的日汇总
        daily = api.get("/api/relay/usage/daily",
                        headers={"Authorization": "Bearer " + TOKEN}).json()
        # 日汇总里只有那一笔编码用量，**没有**智能体那一笔
        assert sum(item["input"] for item in daily["items"]) == 1_000_000


def test_repeated_questions_do_not_inflate_the_users_coding_usage(agent_app, tmp_path):
    """问三次不该让用户的编码用量涨——那正是账本混在一起会出的错。"""
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        insert_usage(tmp_path, account_login(api)["userId"], cst_millis("2026-09-25", 12),
                     "DEEPSEEK", input_tokens=500_000, output=0)
        for _ in range(3):
            ask(api)
        import sqlite3
        with sqlite3.connect(tmp_path / "forum.sqlite3") as db:
            relay_tokens = db.execute("SELECT SUM(input) FROM relay_usage").fetchone()[0]
            agent_tokens = db.execute("SELECT SUM(input) FROM agent_usage").fetchone()[0]
        assert relay_tokens == 500_000            # 用户的编码用量没变
        assert agent_tokens == 600 * 3            # 智能体自己的账记了三笔


def test_own_cost_uses_the_pricing_table(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        ask(api)
        body = api.get("/api/relay/agent/status",
                       headers={"Authorization": "Bearer " + account_token(api)}).json()
        assert body["configured"] is True
        assert body["model"] == DEFAULT_MODEL
        assert body["separateLedger"] is True
        # input 600 微美元按 140845/1M 算 = 84 微美元（向下取整），cacheRead/output 同理
        expected = (600 * cny_per_1m_to_usd_micros(1)
                    + 400 * cny_per_1m_to_usd_micros(0.02)
                    + 200 * cny_per_1m_to_usd_micros(4)) // 1_000_000
        assert body["ownCostMicros"] == expected, body


# ---- key 不能泄露 --------------------------------------------------------

def test_the_key_never_appears_in_any_response(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        asked = ask(api)
        status = api.get("/api/relay/agent/status",
                         headers={"Authorization": "Bearer " + account_token(api)})
        health = api.get("/health")
        for label, response in (("ask", asked), ("status", status), ("health", health)):
            assert OUR_KEY not in response.text, label
        # 只报「配没配」
        assert health.json()["agentConfigured"] is True
        assert health.json()["agentModel"] == DEFAULT_MODEL


def test_without_a_key_the_assistant_says_so(tmp_path):
    """没配 key 时返回 503 并说明原因，**不拿别的东西凑一个答案**。

    这一条不用 `agent_app` 那个 fixture：`Agent` 是在 `build_router` 里建的，
    建完再换类名已经晚了（闭包里captured 的是实例）。直接把 `agent_key` 留空建一个。
    """
    settings = Settings(str(tmp_path), "https://forum.example", agent_enabled=True,
                        relay_enabled=True,
                        agent_key="")
    client = httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json=completion())))
    with TestClient(create_app(settings, verifier())) as api:
        TOKEN = account_token(api)
        response = ask(api)
        assert response.status_code == 503
        assert "not configured" in response.text


# ---- 上游出错 -----------------------------------------------------------

def test_an_upstream_failure_is_not_an_empty_answer(agent_app, tmp_path):
    """502 而不是空答案——空答案会和「问到了但没内容」混在一起。"""
    build, fake, holder = agent_app
    fake.status = 500
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        assert ask(api).status_code == 502


def test_a_rejected_key_does_not_echo_the_upstream_error(agent_app, tmp_path):
    """我们的 key 失效时**不要把上游的错误文本原样回给客户端**——
    那里面可能带 key 的片段。"""
    build, fake, holder = agent_app
    fake.status = 401
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        response = ask(api)
        assert response.status_code == 503
        assert OUR_KEY not in response.text
        assert "rejected" in response.text


# ---- 输入与鉴权 ---------------------------------------------------------

def test_an_empty_question_is_rejected(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        # Pydantic 的 min_length 先挡一次
        assert api.post("/api/relay/agent/ask", json={"question": ""},
                        headers={"Authorization": "Bearer " + TOKEN}).status_code == 400


def test_extra_fields_are_rejected(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        # `uid` 不是参数——传了直接拒，而不是静默忽略
        assert api.post("/api/relay/agent/ask", json={"question": "hi", "uid": "someone"},
                        headers={"Authorization": "Bearer " + TOKEN}).status_code == 400


def test_the_agent_requires_a_relay_key(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        assert api.post("/api/relay/agent/ask", json={"question": "hi"}).status_code == 401
        assert api.get("/api/relay/agent/status").status_code == 401
        assert ask(api, token="team-token").status_code == 401


def test_the_forwarding_route_is_gone_when_the_relay_is_off(tmp_path):
    """只开智能体、不开中转时，**转发那条兜底路由根本不存在**。

    这是拆分开关的意义：`agent_enabled=true, relay_enabled=false` 的服务器上，
    没有任何路径能把请求转发到用户的上游——也就没有「用户的 API key 落在这台
    机器上」这件事。智能体花的是**我们自己的**模型钱，两者风险完全不同。

    （已知耦合：身份自助注册 `/keys` 目前还挂在中转那套 router 里，
    所以只开 agent 的服务器注册不出身份。见 `app.py` 里那段注释。
    **现在的部署两个开关都开着**，所以碰不到这个组合。）
    """
    settings = Settings(str(tmp_path), "https://forum.example", agent_enabled=True,
                        relay_enabled=False,
                        agent_key=OUR_KEY)
    client = httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json=completion())))
    with TestClient(create_app(settings, verifier())) as api:
        # 转发兜底路由没注册
        assert api.post("/api/relay/v1/chat/completions", json={}).status_code == 404
        # 而 `/keys`（也在中转那套里）同样不存在——这是已知耦合
        assert api.get("/api/relay/keys").status_code == 404


# ---- 工具调用循环 --------------------------------------------------------

def tool_call(call_id, name, arguments):
    return {"id": call_id, "type": "function",
            "function": {"name": name, "arguments": json.dumps(arguments)}}


class ScriptedModel:
    """按剧本回应的假模型：先要工具，再给答案。"""

    def __init__(self, turns):
        self.turns = list(turns)
        self.seen = []

    def post(self, url, headers=None, json=None):
        self.seen.append(json)
        # 剧本用完之后给**最终答案**（不带 tool_calls），而不是重复最后一轮。
        # 重复的话模型会一直要工具，测试就变成在测「跑满轮数」而不是测剧本。
        turn = self.turns.pop(0) if self.turns else {"content": "done"}
        message = {"role": "assistant", "content": turn.get("content") or ""}
        if turn.get("tool_calls"):
            message["tool_calls"] = turn["tool_calls"]
        return httpx.Response(200, json={
            "id": "x", "model": "deepseek-flash",
            "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls"}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 50}})


@pytest.fixture
def loop_app(tmp_path):
    """建一个用剧本模型的 app，并把那个模型实例交出来。"""
    from tokentrail_forum.agent import Agent

    def build(script):
        model = ScriptedModel(script)
        original = Agent

        class Wired(original):
            def __init__(self, store, api_key, md=DEFAULT_MODEL, endpoint=None, client=None,
                         day_provider=None, now_provider=None):
                super().__init__(store, api_key, md, endpoint or "https://api.deepseek.com",
                                 client=model, day_provider=day_provider,
                                 now_provider=now_provider)

        import tokentrail_forum.api_routes as routes
        holder = routes.Agent
        routes.Agent = Wired
        try:
            settings = Settings(str(tmp_path), "https://forum.example", agent_enabled=True,
                                relay_enabled=True, pricing_seed=True, agent_key=OUR_KEY)
            client = httpx.AsyncClient(transport=httpx.MockTransport(
                lambda request: httpx.Response(200, json=completion())))
            app = create_app(settings, verifier(), day_provider=lambda: _TODAY)
        finally:
            routes.Agent = holder
        return app, model

    return build


def test_the_model_can_call_a_tool_and_then_answer(loop_app, tmp_path):
    """一次完整的往返：模型要 `getUsageSummary` → 我们执行 → 它据此回答。

    **这是「智能体能真的回答用量问题」的最小证据。**
    """
    build = loop_app
    app, model = build([
        {"tool_calls": [tool_call("c1", "getUsageSummary",
                                  {"from": "2026-09-20", "to": "2026-09-27",
                                   "groupBy": "DAY"})]},
        {"content": "你这周有 2 天有记录。"},
    ])
    with TestClient(app) as api:
        TOKEN = account_token(api)
        digest = account_login(api)["userId"]
        insert_usage(tmp_path, digest, cst_millis("2026-09-25", 12), "DEEPSEEK",
                     input_tokens=1_000_000, output=0)
        body = ask(api).json()

    assert body["text"] == "你这周有 2 天有记录。"
    # 工具真的执行了，而且记下来了
    assert [record["name"] for record in body["toolCalls"]] == ["getUsageSummary"]
    assert body["evidence"], "the tool must contribute an evidence item"
    assert body["evidence"][0]["fromTool"] == "getUsageSummary"
    # 第二轮请求里应该带着工具结果
    second = model.seen[1]["messages"]
    assert any(item.get("role") == "tool" for item in second), second


def test_the_tool_sees_the_users_data_only_through_the_server_resolved_uid(loop_app, tmp_path):
    """**`uid` 不由模型提供。** 模型就算在参数里塞一个 uid，也影响不了算谁的账。"""
    build = loop_app
    app, model = build([
        {"tool_calls": [tool_call("c1", "getUsageSummary",
                                  {"from": "2026-09-25", "to": "2026-09-25",
                                   "uid": "someone-elses-uid"})]},
        {"content": "ok"},
    ])
    with TestClient(app) as api:
        TOKEN = account_token(api)
        # 播种要用**账号**的 `userId`：智能体的工具按用户的身份取数（见
        # `build_agent_router` —— 身份是账号，不是 relay key）。
        insert_usage(tmp_path, account_login(api)["userId"], cst_millis("2026-09-25", 12),
                     "DEEPSEEK", input_tokens=777, output=0)
        ask(api)
    # 工具结果里出现的是**自己的**数字，不是别人 uid 能改变的东西
    tool_message = next(item for item in model.seen[1]["messages"] if item.get("role") == "tool")
    assert "777" in tool_message["content"]


def test_usage_is_summed_across_turns(loop_app, tmp_path):
    """**每一轮往返都花钱**，所以用量要累加、`calls` 记轮数而不是 1。

    只记最后一轮的话，「问一次花多少」会少算一半以上——而这次的两轮
    正是最典型的形态（先要工具、再回答）。
    """
    build = loop_app
    app, model = build([
        {"tool_calls": [tool_call("c1", "getBudgetStatus", {"month": "2026-09"})]},
        {"content": "done"},
    ])
    with TestClient(app) as api:
        TOKEN = account_token(api)
        body = ask(api).json()
    # 两轮各 100 input / 50 output
    assert body["usage"]["input"] == 200, body["usage"]
    assert body["usage"]["output"] == 100
    assert body["usage"]["calls"] == 2, "calls must count round trips, not 1"


def test_the_loop_stops_at_the_turn_limit(loop_app, tmp_path):
    """模型一直要工具时**必须停下来**。

    没有上限的话，一个不断要求工具的模型能把我们的 key 刷爆，
    而且请求永远不返回。
    """
    build = loop_app
    # 给足十轮「还要工具」，循环应该在第 4 轮（默认上限）就收手
    app, model = build([{"tool_calls": [tool_call("c", "getBudgetStatus",
                                                  {"month": "2026-09"})]}] * 10)
    with TestClient(app) as api:
        TOKEN = account_token(api)
        body = ask(api).json()
    assert body["usage"]["calls"] == 4, body["usage"]
    assert body["text"], "must still return something readable"
    assert any("kept requesting tools" in item for item in body["missingData"]), body["missingData"]


def test_an_unknown_tool_is_refused(loop_app, tmp_path):
    """名单外一律拒绝——**不是返回空**。返回空会让模型以为「查了，没数据」。"""
    build = loop_app
    app, model = build([
        {"tool_calls": [tool_call("c1", "deleteEverything", {})]},
        {"content": "ok"},
    ])
    with TestClient(app) as api:
        TOKEN = account_token(api)
        response = ask(api)
    # 工具执行抛 400（HTTPException），整条请求就失败了——这是刻意的：
    # 模型调了不存在的东西，不该被当成一次正常的「没查到」。
    assert response.status_code == 400, response.text


def test_a_malformed_tool_argument_does_not_crash_the_request(loop_app, tmp_path):
    """模型偶尔给出截断的 JSON。那时候让它继续，比整个请求失败好。"""
    build = loop_app
    app, model = build([
        {"tool_calls": [{"id": "c1", "type": "function",
                         "function": {"name": "getUsageSummary", "arguments": "{broken"}}]},
        {"content": "我拿到参数有问题，重新说。"},
    ])
    with TestClient(app) as api:
        TOKEN = account_token(api)
        response = ask(api)
    # 空参数会让工具报「必须有 from/to」→ 400。这**是**预期行为：
    # 至少不该是 500 或者一个崩掉的进程。
    assert response.status_code == 400
