"""服务端对 App 的 HTTP 层：用量、预算、价目、赛季、智能体。

**2026-09-30 之前这个文件叫 `relay_routes.py`**，主体是"帮用户转发到他自己的上游"
那一套（`/keys` 注册管理 + `{path:path}` 兜底转发）。中转整块砍掉之后只剩下面这些：

| 前缀 | 认证 | 干什么 |
|---|---|---|
| `/relay/usage*` | 账号 token | 读用量（按次 / 按天 / 按模型 / 对比） |
| `/relay/budgets/{month}` | 账号 token | 读/写预算上限（花销现算，从不存） |
| `/relay/pricing*` | 读公开 / 写要账号 | 价目表，改价要留痕 |
| `/relay/season*` | 账号 token | 余额与结算（游戏移出后暂时没有客户端消费者） |
|（另一个 router）`/relay/agent/*` | 账号 token | 应用内智能体 |

**路径前缀里那个 `relay` 留着没改**：它在 App 的 `ServerApi`、部署脚本和文档里都写死了，
改它要同时改三处、还要重新部署。它现在只是个前缀字面量，和"中转"这件事没有关系了。
"""

from fastapi import APIRouter, Header, HTTPException, Query
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field

from .accounts import Accounts
from .agent import Agent, DEFAULT_ENDPOINT as AGENT_ENDPOINT, DEFAULT_MODEL as AGENT_MODEL
from .agent_tools import ToolContext
from .budgets import Budgets
from .summary import compare, summarize
from .seed_pricing import install as install_seed
from .pricing import (BUCKETS, CNY_PER_USD_MICROS, Pricing, cny_per_1m_to_usd_micros,
                      cost_micros)
from .usage_store import FINANCIAL_TIMEZONE, UsageStore
from .budgets import month_bounds
from .seasons import (TOKENS_PER_UNIT, Seasons, day_millis_range,
                      today_in_financial_timezone)
from .store import now_ms, rate_limit


class SpendRequest(BaseModel):
    """扣资源的入参。三个字段分别对应三种资源——**互不通兑**，所以不是一个总量。"""
    model_config = ConfigDict(extra="forbid")
    input: int = Field(default=0, ge=0, le=1_000_000_000)
    cache: int = Field(default=0, ge=0, le=1_000_000_000)
    output: int = Field(default=0, ge=0, le=1_000_000_000)


class AskRequest(BaseModel):
    """只收问题。**没有 uid、没有 key**——身份从凭据来，key 在服务端。"""
    model_config = ConfigDict(extra="forbid")
    question: str = Field(min_length=1, max_length=4000)


class PricingRequest(BaseModel):
    """一版费率。四个桶的值都是**微美元 / 1M token**。"""
    model_config = ConfigDict(extra="forbid")
    effectiveFrom: str = Field(min_length=10, max_length=10)
    rateVersion: str = Field(min_length=1, max_length=64)
    inputMicrosPer1M: int = Field(ge=0, le=10**12)
    cacheReadMicrosPer1M: int = Field(ge=0, le=10**12)
    cacheWriteMicrosPer1M: int = Field(ge=0, le=10**12)
    outputMicrosPer1M: int = Field(ge=0, le=10**12)
    sourceUrl: str | None = Field(default=None, max_length=2048)


class ConvertRequest(BaseModel):
    """人民币元 / 1M token（**不是微元**，就是元，比如 1、0.02、4）。"""
    model_config = ConfigDict(extra="forbid")
    cnyPer1M: float = Field(gt=0, le=10_000)


class BudgetRequest(BaseModel):
    """预算只存上限。`warnAtRatio` 是比例（0–1），不是金额。"""
    model_config = ConfigDict(extra="forbid")
    capMicros: int = Field(ge=0, le=10**15)
    warnAtRatio: float | None = Field(default=None, ge=0.0, le=1.0)


def validate_month(month):
    """`yyyy-MM` 校验。**手写而不是用 dateutil**：格式错的话
    `date.fromisoformat` 会抛一个内部异常，返回给客户端就成了 500 而不是 400。"""
    if len(month) != 7 or month[4] != "-" or not (month[:4].isdigit() and month[5:].isdigit()):
        raise HTTPException(400, "Month must be yyyy-MM")
    if not 1 <= int(month[5:]) <= 12:
        raise HTTPException(400, "Month must be yyyy-MM")


def bearer(authorization):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Relay key required", headers={"WWW-Authenticate": "Bearer"})
    return authorization[7:].strip()



def build_router(settings, store, day_provider=None):
    # **所有「今天」都走这一个函数**：赛季账本、预算覆盖度、智能体的提示词。
    # 早先只有赛季用了注入进来的 provider，预算那边直接读系统时钟——于是那几个
    # 测试把「今天是 2026-09-27」写死在断言里，过了那天就一起变红（踩过：
    # 9/30 跑出来 `coverage["to"] == "2026-09-30"`，而测试期望 `2026-09-27`）。
    # 生产两条路给出的是同一个日期，差别只在**可测**：注入之后测试不用碰系统时钟。
    today = day_provider or today_in_financial_timezone
    ledger = UsageStore(store)
    pricing = Pricing(store)
    # 智能体：用**我们自己的** key，账本独立（见 agent.py 开头）。
    # key 从环境变量读，不进数据库、不进日志、不进响应。
    # 注入的「今天」如果和真实的「现在」不是同一天（只有测试会这样），
    # 用量时间戳也得跟着走，否则记录落在真实那天、查询按注入的月份找，读出来是 0。
    now_provider = None
    if day_provider is not None:
        from .seasons import day_millis_range as _range
        now_provider = lambda: _range(today().isoformat())[0] + 12 * 3600 * 1000  # noqa: E731
    agent = Agent(store, settings.agent_key, settings.agent_model, settings.agent_endpoint,
                  day_provider=today, now_provider=now_provider)
    # 工具要用的那几个读写器。**这里自己建一份**，而不是从 `build_router` 传进来：
    # 两个 router 各自独立（可以只开其中一个），共用同一个库，代价只是几个对象。
    # 赛季/预算用到的「今天」也要能注入，理由见 `seasons.Seasons`。
    ledger = UsageStore(store)
    seasons = Seasons(store, ledger, day_provider)
    budgets = Budgets(store, ledger, pricing)
    if settings.pricing_seed:
        # 幂等：upsert 的主键是 (provider, model, effective_from)。
        # 只在内置种子这一版**没有**对应记录时才写，不覆盖别人后来录的价。
        install_seed(store, pricing)
    router = APIRouter()

    accounts = Accounts(store)

    def identify(authorization):
        """账号 token → `user_id`。**这是现在唯一的身份口径。**

        2026-09-30 之前这里还认 relay key（`tt_...`，中转那条路专用的凭据），
        因为用量是"用户把 cc-switch 指过来"时产生的。中转整块砍掉之后，产生用量的
        路只剩一条——用户在我们 App 里提问、后端替他去调模型——所以身份就是账号
        本身，原来那个"要这条 key"还是"要这个人"的区分也就不存在了。

        注意 `resolve` 的入参是**整个 Authorization 头**（它自己拆 `Bearer `），
        返回值是 `(user_id, username)` 二元组——两处都别想当然。
        """
        identity = accounts.resolve(authorization)
        if identity is None:
            raise HTTPException(401, "Sign in to your account first",
                                headers={"WWW-Authenticate": "Bearer"})
        user_id, username = identity
        return user_id, {"uid": user_id, "displayName": username,
                         "disabled": False, "requestCount": 0}

    def identify_owner(authorization):
        """`identify` 的别名。保留这个名字是因为调用点很多，而且它写着"账记在谁头上"。

        以前两者有别：`identify` 要的是"这条 key"（改上游、停用），
        `identify_owner` 要的是"这个人"（用量、余额、预算）。现在只剩账号一种身份，
        返回值本来就一样。
        """
        return identify(authorization)



    @router.get("/usage")
    def usage(since: int = 0, authorization: str | None = Header(default=None)):
        """按 (模型, 档位, 供应商) 汇总的用量。**账算在账号上**，不在这条 key 上。"""
        digest, identity = identify_owner(authorization)
        return {"uid": digest, "items": ledger.usage_for(digest, since)}

    @router.get("/usage/daily")
    def usage_daily(since: int = 0, until: int | None = None, limit: int = 1000,
                    authorization: str | None = Header(default=None)):
        """按 (`天, provider, 模型`) 滚出来的汇总——`DailyUsage` 的形状。

        **服务端现在是唯一数据源**（见 `docs/DATA_SOURCES.md` §5 之后的方向）：
        客户端不再自己拿 `UsageCall` 滚日汇总，它只读这里的结果。

        成本用**那一天生效的费率**算（`pricing.rate_for` 的 `effectiveFrom<=day`），
        不是用今天的价回头算——改了价目表不能让历史账单变。

        **查不到费率的那一行 `costMicros` 是 null，不是 0。**
        `CONTRACTS.md` §4 的底线：把「不知道花了多少」显示成「花了 0 元」是
        「数字看着没错、结论是错的」。响应里的 `pricingAvailable` 是对**整体**的
        概括（有一条算不出来就是 false），逐行的真相看每行的 `rateVersion`：
        它是 null 就表示这一行没价。
        """
        digest, identity = identify_owner(authorization)
        bounded = max(1, min(limit, 5000))
        items = ledger.daily_for(digest, since, until, bounded)
        # 批量查费率，键是 **(provider, 模型, 天)**——不是 (provider, 模型)。
        # 每行必须用**它自己那一天**生效的费率：改了价目表之后，同一个模型
        # 在不同的天可能就是两个价，用一次的价算整段会让历史账单跟着变。
        # 也避免按行查库（一天的汇总可能有十几个 (provider, 模型)）。
        rates = pricing.rates_for_days([(item["provider"], item["model"], item["day"])
                                        for item in items])
        priced = 0
        unpriced = []
        for item in items:
            item["uid"] = digest
            rate = rates.get((item["provider"], item["model"], item["day"]))
            item["costMicros"] = cost_micros(item, rate)
            item["rateVersion"] = None if rate is None else rate["rate_version"]
            item["settled"] = False
            if rate is None:
                unpriced.append(item["model"])
            else:
                priced += 1
        return {"uid": digest, "items": items,
                "pricingAvailable": bool(items) and priced == len(items),
                "unpricedModels": sorted(set(unpriced)),
                "timezone": FINANCIAL_TIMEZONE}

    # ---- 赛季与结算（服务端是权威，见 seasons.py 开头）--------------------
    #
    # 这四个接口写在 relay 前缀下（`/api/relay/season`），但它们和"中转"没关系：
    # 结算的输入是「这个 uid 的用量」，不管用量是中转来的还是导入来的。
    # 挂在这里只是历史原因（原来共用同一个 router）；等哪天重排路由前缀时
    # 「服务端为唯一数据源」之后，这里的 prefix 应该往上提一层，
    # 变成 `/api/season`（阶段之间要改的正是这个前缀，不是内部逻辑）。

    seasons = Seasons(store, ledger, day_provider)

    @router.get("/season")
    def season(authorization: str | None = Header(default=None)):
        """当前余额 + `lastSettledDay`。**只读，不触发结算。**"""
        digest, identity = identify_owner(authorization)
        state = seasons.state(digest)
        state["monthTokens"] = seasons.month_tokens(digest, today().strftime("%Y-%m"))
        state["tokensPerUnit"] = TOKENS_PER_UNIT
        return state

    @router.post("/season/settle")
    def settle(authorization: str | None = Header(default=None)):
        """结算所有「过完但还没结算」的天。

        **没有「结算哪一天」这个参数**，和客户端 `settleCompletedDays` 的签名一致：
        能算哪些天只由数据本身决定。今天永远不算。
        幂等——连调两次，第二次的 `settledDays` 是空的。
        """
        digest, identity = identify_owner(authorization)
        return seasons.settle(digest)

    @router.post("/season/spend")
    def spend(payload: SpendRequest, authorization: str | None = Header(default=None)):
        """扣资源。三种资源互不通兑，余额不能变负。"""
        digest, identity = identify_owner(authorization)
        balance, problem = seasons.spend(digest, payload.input, payload.cache, payload.output)
        if problem == "insufficient":
            raise HTTPException(409, "Not enough resources")
        if problem == "negative":
            raise HTTPException(400, "Amounts must not be negative")
        if problem == "empty":
            raise HTTPException(400, "Nothing to spend")
        return {"uid": digest, "balance": balance}

    @router.get("/season/days")
    def season_days(limit: int = 400, authorization: str | None = Header(default=None)):
        """已结算的天的流水。用来看「资源是哪儿来的」。"""
        digest, identity = identify_owner(authorization)
        bounded = max(1, min(limit, 1000))
        return {"uid": digest, "items": seasons.settled_days(digest, bounded)}

    # ---- 逐次记录（`UsageCall` 的形状）------------------------------------

    @router.get("/usage/calls")
    def usage_calls(since: int = 0, until: int | None = None, limit: int = 500,
                    authorization: str | None = Header(default=None)):
        """逐次调用记录——`UsageCall` 的形状，给 agent 的按会话分析和明细用。

        三个映射必须说清，它们都是契约里的硬口径：

        1. **`source` 恒为 `"IMPORTED"`。** `UsageCall.Source` 只有这个值和 `SAMPLE`，
           而 `CONTRACTS.md` §4 明确规定只有 `IMPORTED` 进日汇总、预算和游戏资源。
           中转看到的请求是真实流量，属于前者——语义上有点勉强（"imported" 也覆盖
           "拉取来的"），但 `DATA_SOURCES.md` §2 已经拍板不改枚举名，报告里说明即可。
        2. **`costMicros` / `nativeCostMicros` 恒为 `null`**，因为没有价目表就算不出来。
           契约里 `costMicros` 的类型就是 `Long`（可空），null 表示「价格未知」。
           **不许填 0**——`CONTRACTS.md` §4 那条底线。
        3. **`provider` 认不出来时是 `null`，不是 `"unknown"`。** `Provider` 是个枚举，
           值域只有 `OPENAI` / `MIMO` / `DEEPSEEK`，塞一个枚举外的字符串进去，
           客户端反序列化时会直接抛异常而不是拿到一个"未知"。
           `model` 也是同一个道理：抽不到时给 `null` 而不是编一个名字。
        """
        digest, identity = identify_owner(authorization)
        bounded = max(1, min(limit, 2000))
        items = ledger.calls_for(digest, since, until, bounded)
        for item in items:
            item["uid"] = digest
            item["source"] = "IMPORTED"
            item["costMicros"] = None
            item["costCurrency"] = None
            item["nativeCostMicros"] = None
        return {"uid": digest, "items": items, "pricingAvailable": False}

    # ---- 用量汇总（agent 的 getUsageSummary 用这个）-----------------------

    @router.get("/usage/summary")
    def usage_summary(from_: str = Query(alias="from"), to: str = Query(),
                      groupBy: str = Query(default="MODEL"),
                      authorization: str | None = Header(default=None)):
        """按维度滚一段区间的用量——`UsageSummary` 的形状。

        它是 agent 最常用的那个工具（`getUsageSummary`）。**`uid` 不是参数**：
        由服务端从请求的凭据解出来，和 `CONTRACTS.md` §6 那条
        「`uid` 不作为任何工具的参数」一致——客户端传什么都不影响算谁的账。

        **`coverage` 是必填项，不是装饰。** `Coverage` 的类注释讲了为什么：
        用户 9/10 之后没再导入日志，问「这周花了多少」，agent 拿到的区间是 9/1–9/21
        而只有 9/1–9/10 有数据；把缺失当 0 就会报告「本周比上周降了 50%」——
        数字算得没错，结论完全是反的。所以**一条记录都没有的天进 `daysMissing`**，
        「用量为 0」不进。
        """
        digest, identity = identify_owner(authorization)
        return summarize(ledger, pricing, digest, from_, to, groupBy)

    @router.get("/usage/compare")
    def usage_compare(from_: str = Query(alias="from"), to: str = Query(),
                      metric: str = Query(default="COST"),
                      authorization: str | None = Header(default=None)):
        """按 (provider, 模型) 比一段区间——`CompareResult` 的形状。

        agent 的 `compareAgentCosts` 用这个。**只比成本、token 和单价**：
        这个接口里根本没有质量数据，所以「不比哪个模型更聪明」这条边界
        是结构上成立的，不靠自觉（`CompareResult` 的类注释）。

        `uid` 同样不是参数。和汇总共用同一套聚合，不另写一遍。
        """
        digest, identity = identify_owner(authorization)
        return compare(ledger, pricing, digest, from_, to, metric)

    # ---- 预算（只存上限，花销现算）----------------------------------------
    budgets = Budgets(store, ledger, pricing)

    @router.get("/budgets/{month}")
    def budget_status(month: str, authorization: str | None = Header(default=None)):
        """某个月的预算 + 现算花销 + 覆盖度。

        `spentMicros` 是**按天用当天生效的费率**算出来的，每次请求现算、从不存。
        查不到价的天不进这个数——它们进 `unpricedDays`，而且此时
        `pricingAvailable` 是 false，客户端**不能**把 `spentMicros` 当成完整花销。
        缺哪些天没记录在 `coverage.daysMissing` 里，口径见 `Coverage` 的类注释。
        """
        digest, identity = identify_owner(authorization)
        validate_month(month)
        return budgets.status(digest, month, today())

    @router.put("/budgets/{month}")
    def budget_save(month: str, payload: BudgetRequest,
                    authorization: str | None = Header(default=None)):
        """设置或覆盖某个月的预算上限。**只存上限**——花销从来不存，
        因为它和用量是同一份数据的两个视图，存两份必然对不上。"""
        digest, identity = identify_owner(authorization)
        validate_month(month)
        return budgets.save(digest, month, payload.capMicros, payload.warnAtRatio)

    # ---- 价目表 -----------------------------------------------------------

    @router.get("/pricing")
    def pricing_listing(provider: str | None = None, model: str | None = None,
                        authorization: str | None = Header(default=None)):
        """列出已录入的费率。

        **需要账号 token**：价目本身不是秘密，但它挂在账号身份下面，
        匿名开放会变成一个免费的「我们支持哪些模型」探测口，而且没有别的接口
        是按账号过滤的例外。要公开的话应该另开一个不带身份的前缀。
        """
        digest, identity = identify(authorization)
        items = pricing.listing(provider, model)
        return {"items": items, "count": len(items),
                "cnyPerUsdMicros": CNY_PER_USD_MICROS,
                "buckets": list(BUCKETS)}

    @router.put("/pricing/{provider}/{model}")
    def pricing_upsert(provider: str, model: str, payload: PricingRequest,
                       authorization: str | None = Header(default=None)):
        """录一版费率。值是**微美元 / 1M token**（不是人民币）。

        要录人民币来源的价，用 `POST /pricing/convert` 先换算，别手算——
        手算出来的数和 `data/Money` 的口径对不上时，同一笔钱会在两个地方显示成两个值。
        """
        digest, identity = identify(authorization)
        if provider not in {"OPENAI", "MIMO", "DEEPSEEK"}:
            raise HTTPException(400, "Unknown provider")
        saved = pricing.upsert(provider, model, payload.effectiveFrom,
                               {"input": payload.inputMicrosPer1M,
                                "cache_read": payload.cacheReadMicrosPer1M,
                                "cache_write": payload.cacheWriteMicrosPer1M,
                                "output": payload.outputMicrosPer1M},
                               payload.rateVersion, payload.sourceUrl)
        return saved

    @router.post("/pricing/convert")
    def pricing_convert(payload: ConvertRequest, authorization: str | None = Header(default=None)):
        """人民币元 / 1M → 微美元 / 1M。

        把换算放在服务端而不是让录入的人手算：换算率只有一处
        （`CNY_PER_USD_MICROS`，和 App 侧 `data/Money` 同值），
        手算等于多一份实现，而且错了不会报错。
        """
        digest, identity = identify(authorization)
        return {"inputMicrosPer1M": cny_per_1m_to_usd_micros(payload.cnyPer1M),
                "cnyPer1M": payload.cnyPer1M,
                "cnyPerUsdMicros": CNY_PER_USD_MICROS}

    return router


def build_agent_router(settings, store, pricing, day_provider=None):
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
    # 用量时间戳也得跟着走，否则记录落在真实那天、查询按注入的月份找，读出来是 0。
    now_provider = None
    if day_provider is not None:
        from .seasons import day_millis_range as _range
        now_provider = lambda: _range(today().isoformat())[0] + 12 * 3600 * 1000  # noqa: E731
    agent = Agent(store, settings.agent_key, settings.agent_model, settings.agent_endpoint,
                  day_provider=today, now_provider=now_provider)
    # 工具要用的那几个读写器。**这里自己建一份**，而不是从 `build_router` 传进来：
    # 两个 router 各自独立（可以只开其中一个），共用同一个库，代价只是几个对象。
    # 赛季/预算用到的「今天」也要能注入，理由见 `seasons.Seasons`。
    ledger = UsageStore(store)
    seasons = Seasons(store, ledger, day_provider)
    budgets = Budgets(store, ledger, pricing)
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

    @router.post("/agent/ask")
    def agent_ask(payload: AskRequest, authorization: str | None = Header(default=None)):
        """问一句，拿一次回答。

        **用的是我们自己的模型 key**（`FORUM_AGENT_KEY`），不是用户的上游 key——
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
        # 工具循环需要的东西都在这里：usage（用量账本）、pricing（价目）、
        # budgets（预算）、store（论坛帖子）。
        # `uid` 由服务端从账号 token 解出并传进去——模型的参数表里没有 uid。
        settings_for_tools = ToolContext(digest=user_id, uid=user_id, usage=ledger,
                                        pricing=pricing, seasons=seasons,
                                        budgets=budgets, store=store, today=today)
        text, tokens, model, evidence, records, missing = agent.ask(
            user_id, payload.question, context=vars(settings_for_tools))
        month = today().strftime("%Y-%m")
        own = agent.month_cost_micros(user_id, pricing, month)
        return {"text": text, "model": model, "createdAtEpochMillis": now_ms(),
                "ownCostMicros": own or 0, "evidence": evidence, "toolCalls": records,
                # 工具自己报上来的缺失（缺哪些天、哪些模型没价、哪一项查不到），
                # 加上循环没收敛时那一句。**这个列表是机制保证**，不是提示词。
                "missingData": missing,
                "usage": tokens}

    @router.get("/agent/status")
    def agent_status(authorization: str | None = Header(default=None)):
        """智能体配好了没有 + 它自己这个月花了多少。"""
        user_id = identify(authorization)
        month = today().strftime("%Y-%m")
        own = agent.month_cost_micros(user_id, pricing, month)
        return {"uid": user_id, "configured": agent.configured, "model": settings.agent_model,
                "ownUsageThisMonth": agent.own_cost_ledger(
                    user_id, day_millis_range(month_bounds(month)[0])[0]),
                "ownCostMicros": own, "pricingAvailable": own is not None,
                "separateLedger": True}

    return router
