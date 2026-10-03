"""服务端对 App 暴露的唯一一组业务路由：应用内智能体。

**2026-09-30 之前这里叫 `api_routes.py`（再之前是 `relay_routes.py`）**，装着
用量、预算、价目、赛季五组接口加一个中转转发兜底。那一整套按新大纲删掉了：
Insights 的数据要等"后端替用户调模型"那条路写出来才有东西可记，而在此之前
那些接口既没有生产者也没有消费者——**空转的接口比没有接口更糟**，
它会让人以为账已经在记了（见 `docs/SERVER_API.md` 开头那段）。

现在服务端只剩：账号（`account_routes.py`）、论坛与新闻（`app.py` + `store.py`）、
以及这里的智能体。智能体留着的理由：它用的是**我们自己的**模型 key，
问的是论坛与产品本身，和"用户用量从哪来"没有关系。
"""
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from .accounts import Accounts
from .agent import Agent, DEFAULT_ENDPOINT as AGENT_ENDPOINT, DEFAULT_MODEL as AGENT_MODEL
from .agent_tools import ToolContext
from .schema import day_millis_range, month_bounds, today_in_financial_timezone
from .store import now_ms, rate_limit


class AskRequest(BaseModel):
    """只收问题。**没有 uid、没有 key**——身份从凭据来，key 在服务端。"""
    model_config = ConfigDict(extra="forbid")
    question: str = Field(min_length=1, max_length=4000)


def build_agent_router(settings, store, day_provider=None):
    """应用内 AI 智能体的路由。**和转发那套完全分开。**

    为什么要单独一个 router，而不是塞进 `build_router`：

    1. **它们是两个功能，该有两个开关。** 一开始挂在 `relay_enabled` 下面，
       结果「只想开智能体」的人必须连中转一起开——而中转一开，用户的 API key
       就落在这台机器上了。那是两件风险完全不同的事，不能捆在一起。
    2. **账本分开**（`agent_usage` vs `relay_usage`），路由分开之后
       这一点在结构上就能看出来。
    3. 兜底转发路由的注册顺序有讲究（见 `build_router` 的注释），
       把智能体混在里面会让那个顺序更难推理。

    **身份用账号 token，不用 relay key**（2026-02 改）。智能体是「用户的客服」：
    用户可能压根没配过中转，但一定登录过 App。用 relay key 当身份有两个坏处——
    没开中转就用不了智能体，而且那份 key 会跑到中转之外的板块里去（用户明确要求
    「relay key 只应该在中转那里出现」）。改用账号 token 之后，`uid` 和 `userId`
    是同一个值，`getMyThreads` 直接拿它查 `posts.author_uid`，**不再需要绑定表**。
    """
    # 「今天」和 `build_router` 里同一个来源（见那边的注释）：提示词里那句日期、
    # `agent_status` 报的月份、预算覆盖度，**一处都不能读系统时钟**，
    # 否则测试只能靠「跑测试那天正好是某个日子」蒙对。
    today = day_provider or today_in_financial_timezone
    # 注入的「今天」如果和真实的「现在」不是同一天（只有测试会这样），
    # 智能体自己那本账的时间戳也得跟着走，否则记录落在真实那天、查询按注入的
    # 月份找，读出来是 0。
    now_provider = None
    if day_provider is not None:
        from .schema import day_millis_range as _range
        now_provider = lambda: _range(today().isoformat())[0] + 12 * 3600 * 1000  # noqa: E731
    agent = Agent(store, settings.agent_key, settings.agent_model, settings.agent_endpoint,
                  day_provider=today, now_provider=now_provider)
    router = APIRouter()

    accounts = Accounts(store)

    def identify(authorization):
        """账号 token → `user_id`。**这里不收 relay key**，理由见函数开头的说明。

        注意 `resolve` 的入参是**整个 Authorization 头**（它自己拆 `Bearer `），
        返回值是 `(user_id, username)` 二元组——两处都别想当然。
        早先这里试过「先取 relay key 再看前缀」，那正是要拆掉的耦合。
        """
        identity = accounts.resolve(authorization)
        if identity is None:
            raise HTTPException(401, "Sign in to your account first",
                                headers={"WWW-Authenticate": "Bearer"})
        user_id = identity[0]
        return user_id

    @router.post("/ask")
    def agent_ask(payload: AskRequest, authorization: str | None = Header(default=None)):
        """问一句，拿一次回答。

        **用的是我们自己的模型 key**（`MODELPILOT_AGENT_KEY`），不是用户的上游 key——
        所以它和中转是两套东西，账本也是两套。客户端**不持有任何模型 key**
        （大纲 §8、`data/remote/package-info.java`）。

        `missingData` 里如实列出这一版还不具备的能力。**这不是占位符**：
        现在没有接工具调用循环，所以回答不了「这周花了多少」这类问题——
        与其让模型凭印象编一个数字，不如把「没有数据」这件事讲出来。
        那正是 `AdviceAnswer.missingData` 存在的理由。
        """
        user_id = identify(authorization)
        with store.connect(write=True) as db:
            # 智能体花的是**我们的钱**，所以限流比转发更该严一点。
            rate_limit(db, user_id, "agent", settings.agent_requests_per_minute)
        # 工具循环需要的东西都在这里：store（论坛帖子）与「今天」。
        # `uid` 由服务端从账号 token 解出并传进去——模型的参数表里没有 uid。
        settings_for_tools = ToolContext(digest=user_id, uid=user_id, store=store, today=today)
        text, tokens, model, evidence, records, missing = agent.ask(
            user_id, payload.question, context=vars(settings_for_tools))
        return {"text": text, "model": model, "createdAtEpochMillis": now_ms(),
                "evidence": evidence, "toolCalls": records,
                # 工具自己报上来的缺失（缺哪些天、哪些模型没价、哪一项查不到），
                # 加上循环没收敛时那一句。**这个列表是机制保证**，不是提示词。
                "missingData": missing,
                "usage": tokens}

    @router.get("/status")
    def agent_status(authorization: str | None = Header(default=None)):
        """智能体配好了没有 + 它自己这个月用了多少 token。

        **不再报钱了**：价目表随记账那一整套删掉了（2026-09-30），没有费率就
        算不出金额——按 `CONTRACTS.md` §4 那条底线，算不出来就**不能说 0**，
        所以这里直接不报这个字段，而不是填一个 0 上去。
        """
        user_id = identify(authorization)
        month = today().strftime("%Y-%m")
        return {"uid": user_id, "configured": agent.configured, "model": settings.agent_model,
                "ownUsageThisMonth": agent.own_usage_ledger(
                    user_id, day_millis_range(month_bounds(month)[0])[0]),
                "separateLedger": True,
                "costReporting": "unavailable"}

    return router
