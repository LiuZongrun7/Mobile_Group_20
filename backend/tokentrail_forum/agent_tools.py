"""agent 的只读工具：定义、分发、执行。

## 两条硬约束（`CONTRACTS.md` §6）

1. **全部只读，按白名单分发，名单外一律拒绝。** 名单是 `contract/tool/AgentTool.java`
   那五个函数名。这里不额外放行任何东西——模型能调的只有这五个。
2. **`uid` 不作为任何工具的参数。** 由服务端从请求凭据解出来传进来
   （`execute` 的 `digest` 参数），模型传什么都不影响算谁的账。
   **工具的 JSON Schema 里也没有 `uid`**——模型看得见的参数表里不存在这个东西，
   所以它没有机会「传一个 uid 试试」。

## 哪些工具能给出真实数据（这一步的诚实边界）

| 工具 | 状态 |
|---|---|
| `getUsageSummary` | ✅ 走 `summary.summarize`，和 HTTP 接口同一套聚合 |
| `compareAgentCosts` | ✅ 走 `summary.compare`，同上 |
| `getBudgetStatus` | ✅ 走 `Budgets.status`（花销现算，算不出价会说明） |
| `getForumHighlights` | ✅ 官方帖（我们采集的资讯）+ 社区热帖，**两类各自带 `source`** |
| `getMyThreads` | ✅ 靠 `users.team_uid` 的绑定关系查；没绑过会如实说「查不到」 |

**`getMyThreads` 的身份是两套，靠 `users.team_uid` 连起来。** 智能体用 relay key，
而论坛帖子的 `author_uid` 是**团队账号**的 uid（团队后端给的，改不了）。
所以绑定关系是必需的，没有它查不了。**没绑过时返回结构化的「不知道」而不是空列表**：
空列表读起来是「你没发过帖子」，真相是「我查不到」。

## 覆盖度必须带出来

每个返回数字的工具都要带 `coverage`，服务端把它拼进答案的 `missingData`。
这是「agent 承认自己不知道」的**机制保证**，不靠提示词祈祷模型老实。
"""
import json
from dataclasses import dataclass
from datetime import date

from fastapi import HTTPException

from .budgets import Budgets, month_bounds
from .pricing import cost_micros
from .seasons import day_millis_range
from .store import reply_json

# 工具白名单。**必须和 `contract/tool/AgentTool.java` 的 functionName 一一对应。**
# 多一个少一个都会让「模型问了个不存在的工具」变成一句内部错误。
ALLOWED = ("getUsageSummary", "getBudgetStatus", "compareAgentCosts",
           "getForumHighlights", "getMyThreads")

# 给模型的工具定义（OpenAI function-calling 格式，DeepSeek 兼容）。
#
# **注意每个 schema 里都没有 `uid`**：模型看不见它，也就传不了它。
SCHEMAS = (
    {"type": "function", "function": {
        "name": "getUsageSummary",
        "description": "查一段日期区间的用量汇总，按天 / 模型 / 提供方分组。"
                       "返回四类 token 和成本，并说明区间里哪些天没有记录。",
        "parameters": {"type": "object", "properties": {
            "from": {"type": "string", "description": "起始日，yyyy-MM-dd，含当天"},
            "to": {"type": "string", "description": "结束日，yyyy-MM-dd，含当天"},
            "groupBy": {"type": "string", "enum": ["DAY", "MODEL", "PROVIDER"],
                        "description": "分组维度，默认 MODEL"},
        }, "required": ["from", "to"], "additionalProperties": False}}},
    {"type": "function", "function": {
        "name": "getBudgetStatus",
        "description": "查某个月的预算上限、现算花销、覆盖度和是否越过警告线。",
        "parameters": {"type": "object", "properties": {
            "month": {"type": "string", "description": "月份，yyyy-MM"},
        }, "required": ["month"], "additionalProperties": False}}},
    {"type": "function", "function": {
        "name": "compareAgentCosts",
        "description": "在同一区间里对比各提供方与模型的成本、token 用量和单价。"
                       "只比成本和用量，不评价回答质量。",
        "parameters": {"type": "object", "properties": {
            "from": {"type": "string", "description": "起始日，yyyy-MM-dd，含当天"},
            "to": {"type": "string", "description": "结束日，yyyy-MM-dd，含当天"},
            "metric": {"type": "string", "enum": ["COST", "TOKENS", "COST_PER_1M"],
                       "description": "排序指标，默认 COST"},
        }, "required": ["from", "to"], "additionalProperties": False}}},
    {"type": "function", "function": {
        "name": "getForumHighlights",
        "description": "读官方发布的资讯（模型与价格公告）以及社区里的热门帖子。"
                       "两类都返回，各自带 source 标出是哪一类。",
        "parameters": {"type": "object", "properties": {
            "limit": {"type": "integer", "minimum": 1, "maximum": 20},
            "since": {"type": "string", "description": "起始日，yyyy-MM-dd，可选"},
        }, "required": [], "additionalProperties": False}}},
    {"type": "function", "function": {
        "name": "getMyThreads",
        "description": "查当前用户自己发过的帖子以及收到的回复。",
        "parameters": {"type": "object", "properties": {
            "limit": {"type": "integer", "minimum": 1, "maximum": 20},
        }, "required": [], "additionalProperties": False}}},
)


@dataclass(frozen=True)
class ToolContext:
    """工具执行需要的东西。

    §2026-02 改§ 原来这里写的是「`digest` 和 `uid` 是两套身份，别混」——
    `digest` 是 relay key 的 sha256，`uid` 是团队账号 uid。**现在它们必然相等**：
    身份统一成账号之后，服务端解出来的就是账号的 `user_id`，而
    `posts.author_uid` 存的也是它，所以 `getMyThreads` 直接 join，不需要绑定表。
    两个字段都留着是因为调用点很多，改签名换不来什么，但**新的代码只该用 `uid`**。


    - `digest` —— relay 身份（`sha256(relay key)`）。用量、预算、赛季都按它过滤。
    - `uid` —— 账号的 `user_id`。帖子的 `author_uid` 就是这个（同一个值）。
      `CONTRACTS.md` §5 明确把两套分开，所以 `getMyThreads` 现在查不了（见下）。
    """
    digest: str
    uid: str | None
    relay: object
    pricing: object
    seasons: object
    budgets: object
    store: object


def execute(name, arguments, **context):
    """执行一个工具。**名单外一律拒绝**（不是「返回空」，是拒绝）。

    `digest` 是 relay 身份（用量和预算都按它过滤）；`uid` 是**论坛身份**
    （团队账号），目前只有 `getForumHighlights` 需要它（读公共帖），
    `getMyThreads` 需要但拿不到（见模块开头）。

    返回值是 `(给模型看的 JSON, 给用户看的 evidence 条目或 None)`。
    """
    if name not in ALLOWED:
        # 白名单外：**报错而不是返回空**。返回空会让模型以为「查了，没数据」，
        # 而真相是「它调了一个不存在的东西」。
        raise HTTPException(400, f"Unknown tool: {name}")
    handler = _HANDLERS[name]
    return handler(arguments or {}, **context)


def _range(arguments):
    from_day = arguments.get("from")
    to_day = arguments.get("to")
    if not isinstance(from_day, str) or not isinstance(to_day, str):
        raise HTTPException(400, "from and to are required (yyyy-MM-dd)")
    return from_day, to_day


def _coverage_of(payload):
    """把工具结果里的 `coverage` 摘出来。返回 `(coverage, 缺失说明或 None)`。"""
    coverage = payload.get("coverage")
    if not isinstance(coverage, dict):
        return None, None
    missing = coverage.get("daysMissing") or []
    if not missing:
        return coverage, None
    # 只报天数和首尾，别把三十个日期塞进回答——模型会照抄进正文。
    summary = f"{coverage['from']} 到 {coverage['to']} 之间有 {len(missing)} 天没有记录"
    return coverage, summary


def _usage_summary(arguments, **context):
    from .summary import summarize
    from_day, to_day = _range(arguments)
    payload = summarize(context["relay"], context["pricing"], context["digest"],
                        from_day, to_day, arguments.get("groupBy") or "MODEL")
    coverage, gap = _coverage_of(payload)
    # 算不出价这件事也要说出去（§4：算不出价和花了 0 元要分得开）
    if not payload.get("pricingComplete", True):
        gap = (gap + "；" if gap else "") + \
            f"有些模型没有价目（{', '.join(payload.get('unpricedModels') or [])}），成本不完整"
    return payload, {
        "display": f"{payload['from']} 到 {payload['to']} 共 {payload['totals']['input']} 输入 token，"
                   f"成本 {payload['costMicros']} 微美元",
        "basis": gap or "区间内每天都有记录",
        "fromTool": "getUsageSummary"}


def _budget_status(arguments, **context):
    month = arguments.get("month")
    if not isinstance(month, str) or len(month) != 7:
        raise HTTPException(400, "month is required (yyyy-MM)")
    from .seasons import today_in_financial_timezone
    payload = context["budgets"].status(context["digest"], month,
                                        today_in_financial_timezone())
    gaps = []
    if payload.get("unpricedDays"):
        gaps.append(f"{len(payload['unpricedDays'])} 天算不出价，所以花销不完整")
    coverage = payload.get("coverage") or {}
    if coverage.get("daysMissing"):
        gaps.append(f"{len(coverage['daysMissing'])} 天没有记录")
    return payload, {
        "display": f"{month} 上限 {payload.get('capMicros')} 微美元，"
                   f"现算花销 {payload.get('spentMicros')} 微美元"
                   + ("（未设预算）" if not payload.get("configured") else ""),
        "basis": "；".join(gaps) if gaps else "本月每天都有记录且都能算价",
        "fromTool": "getBudgetStatus"}


def _compare(arguments, **context):
    from .summary import compare
    from_day, to_day = _range(arguments)
    payload = compare(context["relay"], context["pricing"], context["digest"],
                      from_day, to_day, arguments.get("metric") or "COST")
    coverage, gap = _coverage_of(payload)
    if not payload.get("pricingComplete", True):
        gap = (gap + "；" if gap else "") + \
            f"这些模型没有价目：{', '.join(payload.get('unpricedModels') or [])}"
    leaders = payload.get("rows") or []
    top = leaders[0] if leaders else None
    return payload, {
        "display": (f"{payload['from']} 到 {payload['to']} 共 {len(leaders)} 组，"
                    f"按 {payload['metric']} 排首位的是 "
                    f"{top['model'] if top else '（没有数据）'}")
                   if top else f"{payload['from']} 到 {payload['to']} 没有可用记录",
        "basis": gap or "区间内每天都有记录且都能算价",
        "fromTool": "compareAgentCosts"}


def _forum_highlights(arguments, **context):
    """官方帖 + 社区热帖。**两类都要给，而且各自带 `source`。**

    `ForumHighlights` 的注释写的是「官方帖 + 热帖」，而两者的**可信度不同**：
    官方帖可以当价格/版本的事实来源，社区帖只能当经验分享。所以回答里
    必须分得清——模型看不到 `source` 的话会把社区经验当成官方口径讲出来。
    """
    from .store import official_posts
    limit = min(int(arguments.get("limit") or 5), 20)
    since = arguments.get("since")
    since_millis = 0
    if isinstance(since, str):
        try:
            since_millis, _ = day_millis_range(since)
        except ValueError:
            raise HTTPException(400, "since must be yyyy-MM-dd") from None

    digest = context["digest"]
    store = context["store"]
    # 官方帖：我们自己的资讯（`news` 表），就是论坛里 `source=OFFICIAL` 那一类。
    with store.connect() as db:
        official = official_posts(db, limit)
    # 社区热帖：帖子是公共内容（`CONTRACTS.md` §5），不需要论坛身份也能读。
    with store.connect() as db:
        rows = db.execute("""SELECT id FROM posts WHERE created>=?
            ORDER BY (SELECT COUNT(*) FROM likes WHERE post_id=posts.id) * 1
                   + (SELECT COUNT(*) FROM replies WHERE post_id=posts.id) * 2 DESC,
            created DESC LIMIT ?""", (since_millis, limit)).fetchall()
        community = [store.post(db, row["id"], digest, "") for row in rows]

    payload = {"posts": official + community, "since": since,
               "officialCount": len(official), "communityCount": len(community)}
    return payload, {
        "display": f"官方帖 {len(official)} 条、社区热帖 {len(community)} 条",
        "basis": "官方帖来自我们自己采集的资讯（可作事实来源）；"
                 "社区帖是用户经验分享，不能当官方口径",
        "fromTool": "getForumHighlights"}


def _my_threads(arguments, **context):
    """「我发过的帖子，以及反响」——**只有本人能查**。

    身份要两样东西才能对上：

    1. **relay key 的 sha256**（`digest`）—— 谁在问；
    2. **账号的 `user_id`** —— 帖子记的就是它（`posts.author_uid`）。

    两者由 `users.team_uid` 那张绑定关系连起来。**没绑过就如实说「不知道你在论坛
    是谁」，而不是回空列表**——空列表读起来是「你没发过帖子」，而真相是
    「我查不到」，正是 `Coverage` 那段注释在防的那类错。

    返回帖子时带上点赞数和评论数，这正是提问者想要的（「多少人关注」）。
    """
    # **不需要任何绑定表**：`digest` 现在就是账号的 user_id，
    # 而 `posts.author_uid` 存的也是它——同一个 ID，直接 join。
    user_id = context["digest"]
    if not user_id:
        raise HTTPException(401, "Sign in to your account first")
    limit = min(int(arguments.get("limit") or 10), 20)
    store = context["store"]
    with store.connect() as db:
        rows = db.execute("""SELECT id FROM posts WHERE author_uid=?
            ORDER BY created DESC, id DESC LIMIT ?""", (user_id, limit)).fetchall()
        threads = []
        for row in rows:
            post = store.post(db, row["id"], user_id, "")
            replies = [reply_json(reply) for reply in db.execute(
                "SELECT * FROM replies WHERE post_id=? ORDER BY created, id LIMIT 50",
                (row["id"],))]
            threads.append({"post": post, "replies": replies, "hasOfficialReply": False})

    total_likes = sum(item["post"]["likeCount"] for item in threads)
    total_replies = sum(item["post"]["commentCount"] for item in threads)
    return {"threads": threads, "since": None, "asOfEpochMillis": None}, {
        "display": f"你发过 {len(threads)} 个帖子，共收到 {total_likes} 个赞、"
                   f"{total_replies} 条回复",
        "basis": "只统计你已经发布出去的帖子（草稿中的图片不算）",
        "fromTool": "getMyThreads"}


_HANDLERS = {
    "getUsageSummary": _usage_summary,
    "getBudgetStatus": _budget_status,
    "compareAgentCosts": _compare,
    "getForumHighlights": _forum_highlights,
    "getMyThreads": _my_threads,
}


def parse_arguments(raw):
    """模型给的 `arguments` 是 JSON **字符串**。解析失败返回 `{}`。

    不抛异常：模型偶尔会给出截断的 JSON，那时候**让它继续**比整个请求失败好——
    工具会用空参数报一个可读的错，模型看到之后能自己纠正。
    """
    if isinstance(raw, dict):
        return raw
    if not isinstance(raw, str) or not raw.strip():
        return {}
    try:
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {}
    except ValueError:
        return {}


# 回给模型的字段白名单。**必须瘦身**：日汇总可能有几十行、热帖正文可能几千字，
# 原样塞回去会让下一轮的上下文爆掉，而且花的还是我们的钱。
_RESULT_FIELDS = {
    "getUsageSummary": ("from", "to", "groupBy", "totals", "costMicros",
                        "pricingComplete", "unpricedModels", "rateVersions", "coverage"),
    "getBudgetStatus": ("month", "configured", "capMicros", "warnAtRatio", "spentMicros",
                        "pricingAvailable", "unpricedDays", "coverage"),
    "compareAgentCosts": ("from", "to", "metric", "rows", "coverage",
                          "pricingComplete", "unpricedModels"),
    "getForumHighlights": ("posts", "since"),
    "getMyThreads": ("threads", "unavailable", "reason"),
}

# 行数上限。超了只留前几条 + 一个总数——模型要的是「多少、多少」，不是全量。
_ROW_LIMIT = 20


def describe_result(name, payload):
    """把工具结果压成「给模型看的那一份」，并摘出缺失说明。

    返回 `(瘦身后的结果, 缺失说明或 None)`。

    **压缩不是省事，是必须的**：`getUsageSummary` 按天分组时可能返回几十行，
    每一行四个桶加成本——原样回给模型会让上下文翻好几倍，而它真正需要的
    只是合计和前几行。`Coverage` 和 `pricingComplete` 这类**判断依据不能丢**，
    丢了模型就会把不完整的数当完整的讲。
    """
    if not isinstance(payload, dict):
        return payload, None
    fields = _RESULT_FIELDS.get(name)
    trimmed = {key: payload[key] for key in (fields or ()) if key in payload}

    for key in ("rows", "posts", "threads"):
        if isinstance(trimmed.get(key), list) and len(trimmed[key]) > _ROW_LIMIT:
            total = len(trimmed[key])
            trimmed[key] = trimmed[key][:_ROW_LIMIT]
            trimmed[f"{key}Truncated"] = f"showing {_ROW_LIMIT} of {total}"

    gap = None
    coverage = payload.get("coverage")
    if isinstance(coverage, dict) and coverage.get("daysMissing"):
        gap = (f"{coverage['from']} 到 {coverage['to']} 之间有 "
               f"{len(coverage['daysMissing'])} 天没有记录")
    if payload.get("pricingComplete") is False or payload.get("pricingAvailable") is False:
        models = ", ".join(payload.get("unpricedModels") or [])
        detail = f"（{models}）" if models else ""
        gap = (gap + "；" if gap else "") + f"有些用量没有价目{detail}，成本不完整"
    if payload.get("unavailable"):
        # `getMyThreads` 那种「查不到」——**必须让模型知道这不是「没有」**。
        gap = (gap + "；" if gap else "") + "有一项数据查不到，不是空的"
    return trimmed, gap
