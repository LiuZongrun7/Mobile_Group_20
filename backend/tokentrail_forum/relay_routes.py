"""中转的 HTTP 层：注册管理接口 + 原样转发路由。

分成两个前缀：

| 前缀 | 认证 | 谁在用 |
|---|---|---|
| `/relay/keys`（管理） | **relay key** 本身 | App 的设置页、脚本 |
| `/<公共前缀>/relay/*`（转发） | relay key | cc-switch |

转发是**兜底路由**，必须在管理路由之后注册，否则 `/relay/keys` 会被它吃掉。
`app.py` 里注册顺序就是这个原因，改动顺序会让管理接口返回 404。
"""
import httpx
from fastapi import APIRouter, Header, HTTPException, Query, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from .accounts import TOKEN_PREFIX, Accounts
from .agent import Agent, DEFAULT_ENDPOINT as AGENT_ENDPOINT, DEFAULT_MODEL as AGENT_MODEL
from .agent_tools import ToolContext
from .budgets import Budgets
from .summary import compare, summarize
from .seed_pricing import install as install_seed
from .pricing import (BUCKETS, CNY_PER_USD_MICROS, Pricing, cny_per_1m_to_usd_micros,
                      cost_micros)
from .relay import (HOP_BY_HOP, join, normalize_upstream_secret, provider_for, request_headers,
                    stream_usage, upstream_path, upstream_target, usage_from_body)
from .relay_store import (FINANCIAL_TIMEZONE, MIN_CUSTOM_LENGTH, RelayStore, generate_key,
                          key_hash, validate_custom_key)
from .budgets import month_bounds
from .seasons import (TOKENS_PER_UNIT, Seasons, day_millis_range,
                      today_in_financial_timezone)
from .store import now_ms, rate_limit


class EnrollRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    upstreamUrl: str = Field(min_length=1, max_length=2048)
    upstreamKey: str = Field(min_length=1, max_length=4096)
    # 不传就自动生成。传了就按自定义规则校验（长度下限见 relay_store）。
    relayKey: str | None = Field(default=None, max_length=200)
    displayName: str = Field(default="", max_length=128)


class UpdateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    upstreamUrl: str | None = Field(default=None, max_length=2048)
    upstreamKey: str | None = Field(default=None, max_length=4096)


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


def settings_fields(settings, store):
    """从 Settings 里取出中转需要的三项配置。集中一处，免得散落在各处。"""
    return {"allowed_hosts": settings.relay_allowed_hosts,
            "allow_private": settings.relay_allow_private,
            "self_hosts": settings.relay_self_hosts}


def build_router(settings, store, forwarding_client, day_provider=None):
    # **所有「今天」都走这一个函数**：赛季账本、预算覆盖度、智能体的提示词。
    # 早先只有赛季用了注入进来的 provider，预算那边直接读系统时钟——于是那几个
    # 测试把「今天是 2026-09-27」写死在断言里，过了那天就一起变红（踩过：
    # 9/30 跑出来 `coverage["to"] == "2026-09-30"`，而测试期望 `2026-09-27`）。
    # 生产两条路给出的是同一个日期，差别只在**可测**：注入之后测试不用碰系统时钟。
    today = day_provider or today_in_financial_timezone
    relay = RelayStore(store)
    pricing = Pricing(store)
    # 智能体：用**我们自己的** key，账本独立（见 agent.py 开头）。
    # key 从环境变量读，不进数据库、不进日志、不进响应。
    agent = Agent(store, settings.agent_key, settings.agent_model, settings.agent_endpoint,
                  day_provider=today)
    # 工具要用的那几个读写器。**这里自己建一份**，而不是从 `build_router` 传进来：
    # 两个 router 各自独立（可以只开其中一个），共用同一个库，代价只是几个对象。
    # 赛季/预算用到的「今天」也要能注入，理由见 `seasons.Seasons`。
    relay = RelayStore(store)
    seasons = Seasons(store, relay, day_provider)
    budgets = Budgets(store, relay, pricing)
    if settings.pricing_seed:
        # 幂等：upsert 的主键是 (provider, model, effective_from)。
        # 只在内置种子这一版**没有**对应记录时才写，不覆盖别人后来录的价。
        install_seed(store, pricing)
    site = settings_fields(settings, store)
    router = APIRouter()

    accounts = Accounts(store)

    def identify(authorization, touch=False, allow_account=True):
        """凭据 → 身份。

        两条都认（2026-02 起）：

        * **relay key**（`tt_...`）——返回它自己的 sha256 和身份。`/keys` 那几个
          接口要的就是「这条 key」，改上游、停用都是针对它的。
        * **账号 token**（`tt_app_...`）——解出账号，再用这个账号名下的 key 组成
          身份。用量、余额、预算、价格这些「个人的东西」因此不需要用户配好中转。

        `sk-` 之类一律 401，不做任何回退。
        """
        secret = bearer(authorization)
        if secret.startswith(TOKEN_PREFIX):
            if not allow_account:
                raise HTTPException(401, "Relay key required")
            identity = accounts.resolve(authorization)
            if identity is None:
                raise HTTPException(401, "Account token is invalid or expired",
                                    headers={"WWW-Authenticate": "Bearer"})
            user_id = identity[0]
            # 名下的 key：一个账号可以有多条，取最早注册的那条当「代表」。
            # 没有 key 也**不算错**——用户可以先看用量再决定配不配中转。
            # 读用量/余额时不需要具体哪条 key——账在账号上。这里挑一条只是为了让
            # `identity` 有个形状（响应里的 `displayName` 用它）。**管理类接口不要
            # 走这条路**：它们必须指名 uid，否则多条 key 时会改错人。
            keys = relay.keys_for(user_id)
            if keys:
                return keys[0]["uid"], relay.key(keys[0]["uid"])
            return user_id, {"uid": user_id, "displayName": identity[1],
                             "disabled": False, "requestCount": 0}
        if not secret.startswith("tt_"):
            raise HTTPException(401, "Relay key required")
        digest = key_hash(secret)
        identity = relay.key(digest)
        if identity is None:
            # 和密码错误一样，不区分「没这个 key」和「key 写错了」。
            raise HTTPException(401, "Relay key is not registered")
        if identity["disabled"]:
            raise HTTPException(403, "Relay key is disabled")
        return digest, identity

    def identify_owner(authorization, touch=False):
        """凭据 → **账号的 `user_id`**（用量、余额、预算、结算都挂它）。

        和中转管理接口（`/keys`）的区别值得写下来：那边要的是**这条 key**
        （改上游、停用），这边要的是**这个人**。同一个账号换了几条 key，
        余额和赛季都不该跟着断——所以记账一律走账号，key 只负责「把用量交上来」。

        认不出账号的老 key 退回它自己的 `key_hash` 当身份：那种行是
        「relay key 自带身份」那个阶段留下的，**宁可让它自成一户，也不并到别人账上**。
        """
        secret = bearer(authorization)
        # 账号 token → 直接就是账号本身。**不能走 `owner_of` 的 relay 分支**：
        # 那条分支返回的是「这条 key 属于谁」，而这里要的是「这个人是谁」。
        if secret.startswith(TOKEN_PREFIX):
            identity = accounts.resolve(authorization)
            if identity is None:
                raise HTTPException(401, "Account token is invalid or expired",
                                    headers={"WWW-Authenticate": "Bearer"})
            return identity[0], {"uid": identity[0], "displayName": identity[1],
                                 "disabled": False, "requestCount": 0}
        digest, identity = identify(authorization, touch)
        return relay.owner_of(secret) or digest, identity

    # ---- 管理接口：注册 / 查看 / 改 / 停用 ---------------------------------

    @router.post("/keys", status_code=201)
    def enroll(payload: EnrollRequest, request: Request,
               authorization: str | None = Header(default=None)):
        """给**当前登录的账号**注册一个 relay key。

        **必须带账号令牌**（`Authorization: Bearer tt_app_...`）。relay key 没有
        自己的身份——它只是一个「怎么把用量交上来」的凭据，挂在账号下面。

        早期版本不需要登录（「只用 key」），但那样 relay key 自带身份，
        于是用量、余额、结算全挂在一个凭据上，**换个 key 就换个人**。
        §方位错了：身份该是账号，relay key 只是它的一条通道§。
        """
        identity = accounts.resolve(authorization)
        if identity is None:
            raise HTTPException(401, "Sign in to your account first",
                                headers={"WWW-Authenticate": "Bearer"})
        user_id, username = identity
        address = request.client.host if request.client else "unknown"
        with store.connect(write=True) as db:
            rate_limit(db, "ip:" + address, "relay-enroll", 10)
        if payload.relayKey is None:
            secret = generate_key()
        else:
            problem = validate_custom_key(payload.relayKey)
            if problem:
                raise HTTPException(400, problem)
            secret = payload.relayKey
        upstream_secret = normalize_upstream_secret(payload.upstreamKey)
        if upstream_secret is None:
            raise HTTPException(400, "Upstream key is required")
        base, host = upstream_target(payload.upstreamUrl, **site)
        created = relay.register(secret, join(base, "")[:-1] or base, upstream_secret,
                                 payload.displayName or username, user_id)
        if created is None:
            raise HTTPException(409, "That relay key is already registered")
        created["relayKey"] = secret
        created["provider"] = provider_for(host)
        return created

    @router.get("/keys")
    def current(authorization: str | None = Header(default=None)):
        """**当前这一条 key**。cc-switch 用得上：它手上只有 key，想知道自己指向哪。"""
        digest, identity = identify(authorization, allow_account=False)
        return relay.summary(digest)

    def owned_by_account(user_id, uid):
        """这条 key 是不是这个账号的。不是就当**不存在**（404，不是 403）。

        403 会告诉对方「这条 key 存在，只是不属于你」——那是别人有没有注册过
        某个 key 的信息，不该给。
        """
        if not relay.belongs_to(uid, user_id):
            raise HTTPException(404, "No such relay key for this account")

    @router.get("/keys/all")
    def all_keys(authorization: str | None = Header(default=None)):
        """**我名下所有的 key。**（2026-02 加）

        为什么要这个接口：一个账号可以注册多条 key（换上游、轮换凭据），
        而 App 之前只能看见最先注册的那一条——用户没法回答「我到底录了什么」。
        这个接口就是回答那个问题的：每条给出备注名、创建时间、最后使用时间、
        转发次数、是否停用，以及**指向哪个上游主机**。

        §永远不返回 key 明文§：库里只有 sha256，取不回来。所以「哪条是哪条」
        只能靠注册时填的备注名认——这也是那个字段值得填的理由。
        认不出来就重新注册一条：旧的停用即可，历史用量挂在账号上，不会丢。
        """
        identity = accounts.resolve(authorization)
        if identity is None:
            raise HTTPException(401, "Sign in to your account first",
                                headers={"WWW-Authenticate": "Bearer"})
        user_id = identity[0]
        return {"uid": user_id, "items": relay.keys_for(user_id, with_upstream=True)}

    @router.patch("/keys/{uid}")
    def update_one(uid: str, payload: UpdateRequest,
                   authorization: str | None = Header(default=None)):
        """改**指定那一条** key 的上游地址或密钥。

        和下面 `PATCH /keys`（要带 key 本身）的区别：这个要**账号 token**，
        而且必须指名 `uid`——这样多条 key 之间就不会改错人。
        以前的写法是「拿账号 token 打 `/keys`，服务端挑一条」，那在只有一条时
        看着没问题，多了就会**悄悄地改错**。
        """
        identity = accounts.resolve(authorization)
        if identity is None:
            raise HTTPException(401, "Sign in to your account first",
                                headers={"WWW-Authenticate": "Bearer"})
        owned_by_account(identity[0], uid)
        base = None
        if payload.upstreamUrl is not None:
            base, _ = upstream_target(payload.upstreamUrl, **site)
        secret = None
        if payload.upstreamKey is not None:
            secret = normalize_upstream_secret(payload.upstreamKey)
            if secret is None:
                raise HTTPException(400, "Upstream key must not be empty")
        if base is None and secret is None:
            raise HTTPException(400, "Nothing to update")
        return relay.update_upstream(uid, base, secret)

    @router.delete("/keys/{uid}")
    def revoke_one(uid: str, authorization: str | None = Header(default=None)):
        """停用**指定那一条** key（不删行：它是审计线索）。"""
        identity = accounts.resolve(authorization)
        if identity is None:
            raise HTTPException(401, "Sign in to your account first",
                                headers={"WWW-Authenticate": "Bearer"})
        owned_by_account(identity[0], uid)
        relay.revoke(uid)
        return {"status": "ok"}

    @router.patch("/keys")
    def update(payload: UpdateRequest, authorization: str | None = Header(default=None)):
        digest, identity = identify(authorization, allow_account=False)
        base = None
        if payload.upstreamUrl is not None:
            # 换地址也要重新过一遍 SSRF 校验，不能只在注册时查一次。
            base, _ = upstream_target(payload.upstreamUrl, **site)
        secret = None
        if payload.upstreamKey is not None:
            secret = normalize_upstream_secret(payload.upstreamKey)
            if secret is None:
                raise HTTPException(400, "Upstream key must not be empty")
        return relay.update_upstream(digest, base, secret)

    @router.delete("/keys")
    def revoke(authorization: str | None = Header(default=None)):
        digest, identity = identify(authorization, allow_account=False)
        relay.revoke(digest)
        return {"status": "ok"}

    @router.get("/usage")
    def usage(since: int = 0, authorization: str | None = Header(default=None)):
        """按 (模型, 档位, 供应商) 汇总的用量。**账算在账号上**，不在这条 key 上。"""
        digest, identity = identify_owner(authorization)
        return {"uid": digest, "items": relay.usage_for(digest, since)}

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
        items = relay.daily_for(digest, since, until, bounded)
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
    # 这四个接口写在 relay 前缀下（`/api/relay/season`），但**它们不是中转专属的**：
    # 结算的输入是「这个 uid 的用量」，不管用量是中转来的还是导入来的。
    # 之所以挂在这里，是因为身份用的是 relay key；等客户端彻底改成
    # 「服务端为唯一数据源」之后，这里的 prefix 应该往上提一层，
    # 变成 `/api/season`（阶段之间要改的正是这个前缀，不是内部逻辑）。

    seasons = Seasons(store, relay, day_provider)

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
        items = relay.calls_for(digest, since, until, bounded)
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
        return summarize(relay, pricing, digest, from_, to, groupBy)

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
        return compare(relay, pricing, digest, from_, to, metric)

    # ---- 预算（只存上限，花销现算）----------------------------------------
    budgets = Budgets(store, relay, pricing)

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

        **需要 relay key**：价目本身不是秘密，但它挂在账号身份下面，
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

    # ---- 转发：兜底路由 ----------------------------------------------------
    #
    # **这一条必须由调用方在 `keys` 全部注册完之后再加**（见 `build_router` 的
    # 返回值和 `app.py` 里的注册顺序）。原因不是「习惯」：`APIRouter` 内部按路径
    # 长度排序，`/{path:path}` 的长度比 `/keys` 短，所以它**排在前面**——
    # 一旦一起注册，`POST /keys` 会先被兜底接管，然后用「必须有 relay key」
    # 把注册请求自己挡在门外（401），而注册接口恰恰是拿不到 key 的那一个。
    async def forward(request: Request):
        # 这里要**两个**身份，别混：`digest` 是这条 key（校验、计数、审计），
        # `owner` 是账号（用量记在它名下）。转发仍然只认 relay key——
        # 客户端拿 relay key 打 `/api/relay/v1/...`，那是中转自己那条路。
        digest, identity = identify(request.headers.get("authorization"))
        # 退回 `digest` 的那一支不是死代码：老 key（「自带身份」时代注册的）没有
        # `user_id`，用 None 去记账会撞 NOT NULL 约束——而那会在**转发的 finally 里**
        # 抛出来，表现成「请求成功了但用量没记上」。宁可让它自成一户。
        owner = relay.owner_of(bearer(request.headers.get("authorization"))) or digest
        credentials = relay.forwarding(digest)
        if credentials is None:
            raise HTTPException(409, "Relay key has no upstream configured")
        base, host = upstream_target(credentials["url"], **site)
        target = join(base, upstream_path(request.url.path, settings.relay_prefix))
        body = await request.body()
        with store.connect(write=True) as db:
            rate_limit(db, digest, "relay", settings.relay_requests_per_minute)
        upstream = forwarding_client.build_request(
            "POST" if body else "GET", target,
            headers=request_headers(request.headers, credentials["secret"]),
            content=body if body else None)
        try:
            response = await forwarding_client.send(upstream, stream=True)
        except httpx.HTTPError:
            # 上游连不上是我们的问题，不是客户端的问题；但对用户来说必须能看出来
            # 「这次没转成功」，所以不能吞掉返回 200。
            #
            # 只接 httpx 自己的异常：`except Exception` 会把代码 bug（比如
            # 事件循环用错）也伪装成「上游不可达」，那种错误根本查不出来。
            raise HTTPException(502, "Upstream is unreachable") from None
        content_type = response.headers.get("content-type", "")
        passthrough = {name: value for name, value in response.headers.items()
                       if name.lower() not in HOP_BY_HOP and name.lower() != "content-type"}
        # provider 在**两条路径上都要带上**（非流式和流式）。它进数据库之后会被日汇总
        # 当作分组键，缺了的话日汇总里会多出一档「不知道是哪家」的用量，
        # 而 DailyUsage 的主键是 (uid, 天, provider, 模型)——那一档根本进不了契约形状。
        provider = provider_for(host)
        if "text/event-stream" in content_type:
            return StreamingResponse(
                streaming(response, owner, provider),
                status_code=response.status_code, media_type=content_type, headers=passthrough)
        payload = await response.aread()
        await response.aclose()
        tokens = usage_from_body(payload)
        if tokens is not None:
            tokens["provider"] = provider
            with store.connect(write=True) as db:
                relay.record(db, owner, tokens)
                relay.touch(db, digest)
        return Response(payload, status_code=response.status_code, media_type=content_type,
                        headers=passthrough)

    async def streaming(response, owner, provider):
        """流式透传。

        **发给客户端的字节和上游发的完全一致**，`buffer` 只是旁边解析用的副本。
        用量在上游发完最后一块之后才落库——因为 usage 就在最后那几块里。
        """
        buffer = b""
        tokens = None
        try:
            async for chunk in response.aiter_bytes():
                found, buffer = stream_usage(chunk, buffer)
                if found is not None:
                    tokens = found
                yield chunk
        finally:
            await response.aclose()
            if tokens is not None:
                tokens["provider"] = provider
                with store.connect(write=True) as db:
                    relay.record(db, owner, tokens)
                    relay.touch(db, owner)

    return router, forward


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
    agent = Agent(store, settings.agent_key, settings.agent_model, settings.agent_endpoint,
                  day_provider=today)
    # 工具要用的那几个读写器。**这里自己建一份**，而不是从 `build_router` 传进来：
    # 两个 router 各自独立（可以只开其中一个），共用同一个库，代价只是几个对象。
    # 赛季/预算用到的「今天」也要能注入，理由见 `seasons.Seasons`。
    relay = RelayStore(store)
    seasons = Seasons(store, relay, day_provider)
    budgets = Budgets(store, relay, pricing)
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
        # 工具循环需要的东西都在这里：relay（用量）、pricing（价目）、
        # budgets（预算）、store（论坛帖子）。
        # `uid` 由服务端从账号 token 解出并传进去——模型的参数表里没有 uid。
        settings_for_tools = ToolContext(digest=user_id, uid=user_id, relay=relay,
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
