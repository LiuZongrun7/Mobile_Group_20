"""agent 的只读工具：定义、分发、执行。

## 两条硬约束（`CONTRACTS.md` §6）

1. **全部只读，按白名单分发，名单外一律拒绝。** 这里不额外放行任何东西——
   模型能调的只有 `ALLOWED` 里列出的那些（2026-09-30 起是论坛那两个）。
2. **`uid` 不作为任何工具的参数。** 由服务端从请求凭据解出来传进来
   （`execute` 的 `digest` 参数），模型传什么都不影响算谁的账。
   **工具的 JSON Schema 里也没有 `uid`**——模型看得见的参数表里不存在这个东西，
   所以它没有机会「传一个 uid 试试」。

## 现在还剩哪些工具（2026-09-30 收缩之后）

| 工具 | 状态 |
|---|---|
| `getForumHighlights` | ✅ 官方帖（我们采集的资讯）+ 社区热帖，**两类各自带 `source`** |
| `getMyThreads` | ✅ 按账号的 `user_id` 查自己发过的帖子与收到的回复 |

另外三个（`getUsageSummary` / `getBudgetStatus` / `compareAgentCosts`）读的是用量、
预算、价目——那三组接口与表随记账一起删掉了。**没有数据源的工具留着只会让模型编
数字**，所以整块删了；将来"后端替用户调模型"那条路写出来、账重新开始记之后，
再按当时的表结构重新设计它们。

**`getMyThreads` 查不到时返回结构化的「不知道」，而不是空列表**：
空列表读起来是「你没发过帖子」，真相是「我查不到」。

## 查不到就说查不到

这是「agent 承认自己不知道」的**机制保证**，不靠提示词祈祷模型老实。
以前每个带数字的工具还要带 `coverage`，那些工具删掉之后，这条规矩只剩
`getMyThreads` 的 `unavailable` / `reason` 一个落点。
"""
import json
from dataclasses import dataclass
from datetime import date

from fastapi import HTTPException

from .store import reply_json

# 工具白名单。**必须和 `contract/tool/AgentTool.java` 的 functionName 一一对应。**
# 多一个少一个都会让「模型问了个不存在的工具」变成一句内部错误。
# **2026-09-30 起只剩论坛那两个。** 另外三个（getUsageSummary / getBudgetStatus /
# compareAgentCosts）读的是用量、预算、价目——那三组接口与表随记账一起删了，
# 留一个没有数据源的工具只会让模型编数字。将来"后端替用户调模型"那条路写出来、
# 账重新开始记之后，再按当时的表结构重新设计它们（别照抄现在这份）。
ALLOWED = ("getForumHighlights", "getMyThreads")

# 给模型的工具定义（OpenAI function-calling 格式，DeepSeek 兼容）。
#
# **注意每个 schema 里都没有 `uid`**：模型看不见它，也就传不了它。
SCHEMAS = (
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
    `digest` 是**账号的 `user_id`**（2026-09-30 之前是 relay key 的 sha256，中转删掉
    之后没有第二种凭据了）。`uid` 也是同一个值——`posts.author_uid` 存的就是它，
    所以 `getMyThreads` 直接查，不需要绑定表。两个字段都留着是因为调用点很多，
    改签名换不来什么，但**新的代码只该用 `uid`**。


    - `digest` —— 账号身份（`user_id`）。工具的过滤条件都按它。
    - `uid` —— 同一个值；帖子的 `author_uid` 存的就是它，`getMyThreads` 直接查。
    """
    digest: str
    uid: str | None
    store: object
    # 「今天」由路由层注入（`build_agent_router` 里那个 `today`）。给默认值是为了
    # 单独调工具时也能跑：那时退回真实时区的今天，生产路径永远走注入的那个。
    today: object = None


def execute(name, arguments, **context):
    """执行一个工具。**名单外一律拒绝**（不是「返回空」，是拒绝）。

    `digest` 是账号身份（用量和预算都按它过滤）；`uid` 是**论坛身份**
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

    1. **账号的 `user_id`**（`digest`）—— 谁在问；
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
    "getForumHighlights": ("posts", "since"),
    "getMyThreads": ("threads", "unavailable", "reason"),
}

# 行数上限。超了只留前几条 + 一个总数——模型要的是「多少、多少」，不是全量。
_ROW_LIMIT = 20


def describe_result(name, payload):
    """把工具结果压成「给模型看的那一份」，并摘出缺失说明。

    返回 `(瘦身后的结果, 缺失说明或 None)`。

    **压缩不是省事，是必须的**：热帖正文可能几千字，原样回给模型会让上下文翻几倍，
    而它真正需要的只是前几条。**判断依据不能跟着压掉**——比如 `getMyThreads`
    的「查不到」，丢了它模型就会把「我查不到」讲成「你没有」。
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
    # 2026-09-30：原来这里还有两段——`coverage.daysMissing`（哪些天没记录）与
    # `pricingComplete` / `unpricedModels`（哪些用量算不出价）。带数字的工具
    # （用量汇总、预算、对比）随记账一起删了，那两段永远走不到，删掉。
    # **将来重新做 Insights 时，这两条判断要一起回来**：不完整的数当完整的讲，
    # 是这套系统最容易犯也最难发现的错。
    if payload.get("unavailable"):
        # `getMyThreads` 那种「查不到」——**必须让模型知道这不是「没有」**。
        gap = (gap + "；" if gap else "") + "有一项数据查不到，不是空的"
    return trimmed, gap
