"""应用内 AI 智能体：服务端拿**我们自己的** key 去调 DeepSeek。

## 它和中转是两套东西，**账本也必须是两套**

| | 中转 | 智能体（这个模块） |
|---|---|---|
| 用谁的 key | **用户的**（他填的上游 key，存在服务端） | **我们自己的**（`FORUM_AGENT_KEY`） |
| 为什么存在 | 采集用户的编码用量 | 回答问题 |
| 用量归属 | 用户的编码消耗，进他的日汇总/预算/结算 | **agent 自己的开销，绝不能混进用户的编码用量** |

`CONTRACTS.md` §6 明确要求「agent 自己的 token 单独记账（`ownCostThisMonth`），
不和被追踪的编码 agent 混在一起」；大纲 §5 也说「assistant tokens/costs have a
separate ledger, excluded from coding-agent totals and game resources」。

**为什么这条是硬要求，不是洁癖。** 用户问「我这周怎么花了这么多」——如果把智能体
自己调模型的消耗算进他的编码用量，答案里的数字就被问题本身污染了：
问得越多、账越大。而且游戏资源是从编码用量换来的，混进去等于让「多问几句」
能换塔。

所以这个模块**只写 `agent_usage` 表**，一个字段都不碰 `relay_usage`。

## key 只在服务端

大纲 §8 写着 "no client holds a model key"，`data/remote/package-info.java` 也写着
「客户端不持有建议服务调模型用的那个 key」。所以：

- key 从环境变量读（`FORUM_AGENT_KEY`），**不进数据库、不进日志、不进响应**；
- 客户端只发问题，服务端负责调用；
- 没配 key 时接口返回 503 并说明原因，**而不是拿别的东西凑一个答案**。

## 现在这一版只做「一次问答 + 记账」

**没有工具调用循环。** 也就是说这一版回答不了「这周花了多少」这类问题——
那需要把 `/usage/summary` 之类的数据喂给模型（`CONTRACTS.md` §6 的五个只读工具）。

**所以它会在 `missingData` 里如实说明「没有接入用量数据」**，
而不是让模型凭印象编一个数字出来。那正是 `AdviceAnswer.missingData` 存在的理由：
「agent 承认自己不知道」的机制保证。等工具循环接上之后，这一条自然消失。
"""
import json

import httpx
from fastapi import HTTPException

from .agent_tools import SCHEMAS as TOOL_SCHEMAS
from .agent_tools import describe_result, execute, parse_arguments
from .pricing import cost_micros as pricing_cost_micros
from .usage import split_usage
from .usage_store import UTC_TO_FINANCIAL
from .budgets import month_bounds
from .seasons import day_millis_range
from .store import now_ms

SCHEMA = """
-- 智能体自己的开销。**按账号记**（`user_id`），不是按 relay key——
-- 它是「用户的客服」，身份该是账号。早期版本按 relay key 的 sha256 记，
-- 那让 relay key 又当了一次身份，和「它只属于中转」冲突。
CREATE TABLE IF NOT EXISTS agent_usage (
 user_id TEXT NOT NULL, created INTEGER NOT NULL, model TEXT NOT NULL,
 input INTEGER NOT NULL DEFAULT 0, cache_read INTEGER NOT NULL DEFAULT 0,
 cache_write INTEGER NOT NULL DEFAULT 0, output INTEGER NOT NULL DEFAULT 0,
 calls INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS agent_usage_lookup ON agent_usage(user_id, created);
"""

# 默认模型。`DATA_SOURCES.md` §1 末尾那份清单里的名字。
DEFAULT_MODEL = "deepseek-chat"
DEFAULT_ENDPOINT = "https://api.deepseek.com"

# 系统提示词。**这里写的是边界，不是性格。**
#
# 三条都是项目里已经定下的规矩，让模型复述它们比让界面在事后打补丁可靠：
# - 只谈费用和 token，不谈「哪个模型更聪明」（`CompareResult` 的类注释）；
# - 不编用量数字（`Coverage` 那段注释讲的正是这类错的代价）；
# - 不能运行、路由或控制编码 agent（大纲 §1）。
SYSTEM_PROMPT = (
    "You are TokenTrail's in-app assistant. You explain recorded API costs and token "
    "usage for the user's own coding agents.\n"
    "Rules you must follow:\n"
    "1. Compare recorded costs, tokens and cache use only. Never judge which model is "
    "better or smarter, and never rate answer quality.\n"
    "2. Never invent numbers. If you were not given usage data, say you do not have it. "
    "Amounts are estimates, not invoices.\n"
    "3. You cannot run, route or control the user's coding agents.\n"
    "4. If a question mentions a time range without a year, use the current date given "
    "below. Never assume a different year.\n"
    "Answer in the user's language, briefly."
)


def system_prompt(today):
    """把**今天的日期**拼进系统提示词。

    **这一条不是可选的。** 没有它，模型会猜年份——实测过：问「9 月 20 到 27 号」
    它查的是 **2025** 年，于是拿到 0 条记录。那次它运气好，在回答里自己说了
    「或日期不是今年」；但同样的错完全可以表现成一句平静的「这段时间没有用量」，
    而用户会信。日期是**事实**，不该让模型猜。
    """
    return (SYSTEM_PROMPT
            + f"\nToday is {today} (Asia/Shanghai). "
              f"When the user gives a month and day without a year, use {today[:4]}.")


class Agent:
    """一次问答 + 记账。挂在内核的 `Store` 上，用独立的 `agent_usage` 表。"""

    def __init__(self, store, api_key, model=DEFAULT_MODEL, endpoint=DEFAULT_ENDPOINT,
                 client=None, day_provider=None, now_provider=None):
        self.store = store
        self.api_key = api_key
        self.model = model
        self.endpoint = endpoint.rstrip("/")
        self.client = client
        # 「今天」要能注入，和 `seasons.Seasons` 同一个理由：提示词里那句
        # "Today is ..." 直接决定模型把「3 号」算到哪一年，而测试没法改系统时钟。
        # 默认还是真实时区的今天，生产行为一个字不变。
        if day_provider is None:
            from .seasons import today_in_financial_timezone
            day_provider = today_in_financial_timezone
        self.today = day_provider
        # 写入时间也要能注入：注入的"今天"和真实的"现在"不是同一天时（测试里就是
        # 这样），用量会记到真实那一天、而查询按注入的那个月去找——**查出来是 0，
        # 而账其实记上了**。生产上两者永远一致（`now_provider` 是 None），
        # 这里只是让测试里那两件事一起被固定住。
        self.now = now_provider or now_ms
        with store.connect() as db:
            db.executescript(SCHEMA)

    @property
    def configured(self):
        """没配 key 就不该假装能回答。`configured` 是公开可读的（健康检查用）。"""
        return bool(self.api_key)

    def own_cost_ledger(self, digest, since=0):
        """智能体自己的用量合计。**和中转的 `relay_usage` 完全分开。**"""
        with self.store.connect() as db:
            row = db.execute("""SELECT SUM(input) AS input, SUM(cache_read) AS cache_read,
                SUM(cache_write) AS cache_write, SUM(output) AS output, SUM(calls) AS calls
                FROM agent_usage WHERE user_id=? AND created>=?""", (digest, since)).fetchone()
        return {"input": row["input"] or 0, "cacheRead": row["cache_read"] or 0,
                "cacheWrite": row["cache_write"] or 0, "output": row["output"] or 0,
                "calls": row["calls"] or 0}

    def month_cost_micros(self, digest, pricing, month):
        """这个月智能体自己花了多少钱。

        `AdviceRepository.ownCostThisMonth` 要的就是这个数。和编码用量的成本
        用**同一套价目表和同一套换算**，但走的表不同、聚合也不同——
        两边混起来的话，「问 agent 花了多少钱」会把用户的编码开销算进去。
        """
        first, following = month_bounds(month)
        start, _ = day_millis_range(first)
        above, _ = day_millis_range(following)
        with self.store.connect() as db:
            rows = db.execute("""SELECT date(created/1000 + ?, 'unixepoch') AS day,
                model, SUM(input) AS input, SUM(cache_read) AS cache_read,
                SUM(cache_write) AS cache_write, SUM(output) AS output
                FROM agent_usage WHERE user_id=? AND created>=? AND created<?
                GROUP BY day, model""",
                (UTC_TO_FINANCIAL, digest, start, above)).fetchall()
        total = 0
        for row in rows:
            rate = pricing.rate_for("DEEPSEEK", row["model"], row["day"])
            if rate is None:
                # 算不出价就**不猜**。返回 None 让调用方说「价格未知」，
                # 而不是把这一段当 0 加进去（那样总额偏低而看不出来）。
                return None
            amount = cost_of(rate, row)
            if amount is None:
                # 费率行本身缺某个桶。同一个判断，同样不猜。
                return None
            total += amount
        return total

    def ask(self, digest, question, model=None, context=None, max_turns=4):
        """问一句，拿一次回答。**带工具调用循环。**

        `context` 是工具执行需要的东西（usage / pricing / budgets / store / uid）。
        传 None 时**不注册工具**——那样模型只能凭自己答，而且 `missingData` 里会
        明说没有数据（这也是没接工具时那条测试验证的行为）。

        返回 `(答案文本, 合计用量, 模型名, 证据列表, 工具调用记录, 缺失说明)`。
        **每一轮的用量都累加**，因为每一次往返都在花我们的钱；
        `calls` 记的是轮数，不是 1——不然「问一次花多少」会少算。

        **循环有上限（`max_turns`）。** 没有上限的话，一个不断要求工具的模型
        能把我们的 key 刷爆，而且请求永远不返回。
        """
        if not self.configured:
            raise HTTPException(503, "The assistant is not configured on the server")
        text = (question or "").strip()
        if not text:
            raise HTTPException(400, "Question is empty")
        if len(text) > 4000:
            raise HTTPException(400, "Question is too long (maximum 4000 characters)")

        chosen = model or self.model
        headers = {"authorization": "Bearer " + self.api_key,
                   "content-type": "application/json"}
        # 日期必须由服务端给：模型自己猜年份会把整段查询查到别的年份上。
        messages = [{"role": "system", "content": system_prompt(self.today().isoformat())},
                    {"role": "user", "content": text}]
        tools = list(TOOL_SCHEMAS) if context else []

        total = {"input": 0, "cacheRead": 0, "cacheWrite": 0, "output": 0, "calls": 0}
        evidence = []
        records = []
        missing = []
        answer = ""
        used_model = chosen

        for _ in range(max(1, max_turns)):
            payload = {"model": chosen, "messages": messages, "stream": False}
            if tools:
                payload["tools"] = tools
            document = self._call(headers, payload)
            used_model = document.get("model") or chosen
            tokens = split_usage(document.get("usage") or {}, used_model)
            # **只累加四个 token 桶，不累加 `calls`。**
            # `split_usage` 返回的 `calls` 恒为 1（那是「一次上游往返」），
            # 而轮数在这里单独数。两处都加的话每轮被计两次——
            # 踩过：2 轮的请求报出 `calls: 4`，而 **token 数是对的**，
            # 所以只核对 token 的话完全看不出问题。
            for key in ("input", "cacheRead", "cacheWrite", "output"):
                total[key] += tokens[key]
            total["calls"] += 1

            message = (document.get("choices") or [{}])[0].get("message") or {}
            requested = message.get("tool_calls") or []
            if not requested:
                answer = message.get("content") or ""
                break

            # 把模型这一轮的请求原样放回对话，否则下一轮它看不到自己问过什么。
            messages.append({"role": "assistant", "content": message.get("content") or "",
                             "tool_calls": requested})
            for call in requested:
                name = ((call.get("function") or {}).get("name")) or ""
                arguments = parse_arguments((call.get("function") or {}).get("arguments"))
                result, item = execute(name, arguments, **context)
                records.append({"name": name, "arguments": arguments})
                if item is not None:
                    evidence.append(item)
                rows, gap = describe_result(name, result)
                if gap:
                    missing.append(gap)
                messages.append({"role": "tool", "tool_call_id": call.get("id"),
                                 "content": json.dumps(rows, ensure_ascii=False, default=str)})
        else:
            # 用完轮数还没收敛：**如实说**，而不是拿半截对话当答案。
            answer = ""
            missing.append("The assistant kept requesting tools and stopped before "
                           "producing a final answer.")

        if not answer:
            answer = missing[-1] if missing else "The assistant produced no answer."

        with self.store.connect(write=True) as db:
            db.execute("""INSERT INTO agent_usage(user_id,created,model,input,cache_read,
                cache_write,output,calls) VALUES(?,?,?,?,?,?,?,?)""",
                (digest, self.now(), used_model, total["input"], total["cacheRead"],
                 total["cacheWrite"], total["output"], total["calls"]))
        return answer, total, used_model, evidence, records, missing

    def _call(self, headers, payload):
        """一次上游往返，带完整的错误映射。"""
        try:
            response = self._send(headers, payload)
        except httpx.HTTPError:
            # 上游不可达是我们的问题；对用户来说必须能看出来「这次没问成」，
            # 不能返回一个空答案——那会和「问到了，但没内容」混在一起。
            raise HTTPException(502, "The assistant's model is unreachable") from None
        if response.status_code in {401, 403}:
            # 我们的 key 失效了。**不要把上游的错误文本原样回给客户端**——
            # 里面可能带 key 的片段。
            raise HTTPException(503, "The assistant's model credentials were rejected")
        if response.status_code != 200:
            raise HTTPException(502, "The assistant's model returned an error")
        try:
            document = response.json()
            if not isinstance(document, dict) or not document.get("choices"):
                raise ValueError()
            return document
        except (ValueError, TypeError):
            raise HTTPException(502, "The assistant's model returned an unusable response") from None

    def _send(self, headers, payload):
        """发一次请求。测试注入 `client`（同步 httpx.Client）时走那一支。"""
        if self.client is not None:
            return self.client.post(self.endpoint + "/chat/completions",
                                    headers=headers, json=payload)
        with httpx.Client(timeout=60, trust_env=False) as client:
            return client.post(self.endpoint + "/chat/completions",
                               headers=headers, json=payload)


def cost_of(rate, row):
    """按一版费率算一行 `agent_usage` 的成本。

    **复用 `pricing.cost_micros`，不另写一遍乘法。** 那个函数负责
    「缺任何一桶就整体算不出来」那条口径——这里再实现一次的话，
    两处对「什么叫算不出来」的判断迟早会不一样，而症状是同一笔钱两个答案。
    `row` 的列名是下划线（`cache_read`），那个函数要的是契约的驼峰
    （`cacheRead`），所以这里转一次。
    """
    return pricing_cost_micros({
        "input": row["input"], "cacheRead": row["cache_read"],
        "cacheWrite": row["cache_write"], "output": row["output"]}, rate)
