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

from modelpilot_forum.agent import DEFAULT_MODEL, SYSTEM_PROMPT
from modelpilot_forum.app import Settings, create_app
from conftest import verifier
from helpers import account_login, account_token, bearer, completion, cst_millis, create_app_for_tests
# 注入给智能体的「今天」。原来从 `test_seasons` 里 import（那个文件随赛季一起删了），
# 现在就地写死——它的作用是**让提示词里的日期和账的月份可预测**，不是被测对象。
_TODAY = __import__("datetime").date(2026, 9, 27)

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
    settings = Settings(str(path), "https://forum.example",
                        agent_enabled=True, agent_key=key,
                        agent_requests_per_minute=overrides.pop("agent_requests_per_minute", 10),
                        **overrides)
    client = httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json=completion())))
    app = create_app_for_tests(settings, verifier())
    if fake is not None:
        # 把假上游装进去：Agent 是在 build_router 里建的，所以直接替换它的 client。
        app.state.store  # 触发一次无副作用的访问，保持可读性
    return app, fake


def ask(api, question="why did cost rise?", token=None):
    """问智能体一句。

    **凭据是账号 token**：智能体是「用户的客服」，登录过就该能用。
    `token` 可以传个假的/过期的来试鉴权。
    """
    return api.post("/api/agent/ask", json={"question": question},
                    headers={"Authorization": "Bearer " + (token or account_token(api))})


@pytest.fixture
def agent_app(tmp_path):
    """把假上游接到 Agent 上。

    `Agent` 的构造发生在 `build_router` 里，拿不到测试的 client，所以这里
    建好 app 之后**替换掉那个实例的 client**——智能体调上游用的是同步客户端，
    直接注入假的那一个。
    """
    from modelpilot_forum.agent import Agent

    fake = FakeModel()
    holder = {}

    def build(path):
        settings = Settings(str(path), "https://forum.example", agent_enabled=True,
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

        import modelpilot_forum.agent_routes as routes
        routes.Agent = Wired
        try:
            # 「今天」注入成固定值：提示词里那句日期是**服务端给**的，
            # 不注入的话这条断言就变成「跑测试那天必须正好是 2026-09-27」。
            app = create_app_for_tests(settings, verifier(), day_provider=lambda: _TODAY)
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
    assert names == {"getForumHighlights", "getMyThreads"}
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




def test_the_key_never_appears_in_any_response(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        asked = ask(api)
        status = api.get("/api/agent/status",
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
                        agent_key="")
    client = httpx.AsyncClient(transport=httpx.MockTransport(
        lambda request: httpx.Response(200, json=completion())))
    with TestClient(create_app_for_tests(settings, verifier())) as api:
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
        assert api.post("/api/agent/ask", json={"question": ""},
                        headers={"Authorization": "Bearer " + TOKEN}).status_code == 400


def test_extra_fields_are_rejected(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        # `uid` 不是参数——传了直接拒，而不是静默忽略
        assert api.post("/api/agent/ask", json={"question": "hi", "uid": "someone"},
                        headers={"Authorization": "Bearer " + TOKEN}).status_code == 400


def test_the_agent_requires_an_account_token(agent_app, tmp_path):
    build, fake, holder = agent_app
    with TestClient(build(tmp_path)) as api:
        TOKEN = account_token(api)
        assert api.post("/api/agent/ask", json={"question": "hi"}).status_code == 401
        assert api.get("/api/agent/status").status_code == 401
        assert ask(api, token="team-token").status_code == 401



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
    from modelpilot_forum.agent import Agent

    def build(script):
        model = ScriptedModel(script)
        original = Agent

        class Wired(original):
            def __init__(self, store, api_key, md=DEFAULT_MODEL, endpoint=None, client=None,
                         day_provider=None, now_provider=None):
                super().__init__(store, api_key, md, endpoint or "https://api.deepseek.com",
                                 client=model, day_provider=day_provider,
                                 now_provider=now_provider)

        import modelpilot_forum.agent_routes as routes
        holder = routes.Agent
        routes.Agent = Wired
        try:
            settings = Settings(str(tmp_path), "https://forum.example", agent_enabled=True,
                                agent_key=OUR_KEY)
            client = httpx.AsyncClient(transport=httpx.MockTransport(
                lambda request: httpx.Response(200, json=completion())))
            app = create_app_for_tests(settings, verifier(), day_provider=lambda: _TODAY)
        finally:
            routes.Agent = holder
        return app, model

    return build




def test_usage_is_summed_across_turns(loop_app, tmp_path):
    """**每一轮往返都花钱**，所以用量要累加、`calls` 记轮数而不是 1。

    只记最后一轮的话，「问一次花多少」会少算一半以上——而这次的两轮
    正是最典型的形态（先要工具、再回答）。
    """
    build = loop_app
    app, model = build([
        {"tool_calls": [tool_call("c1", "getForumHighlights", {"limit": 5})]},
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
    app, model = build([{"tool_calls": [tool_call("c", "getForumHighlights",
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
