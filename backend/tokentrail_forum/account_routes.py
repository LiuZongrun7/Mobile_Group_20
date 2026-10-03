"""APP 账号的 HTTP 接口：注册、登录、查我、退出。

**这四个接口以后端 `/api/account/*` 提供，不再打那台机器上的其它服务。**

## 为什么单独一个 router，不塞进 relay 那套

它们解决的是完全不同的问题：

| | 账号（这里） | 记账与智能体（`api_routes.py`） |
|---|---|---|
| 回答什么 | 「你是谁」 | 「你怎么把用量交上来」 |
| 凭据 | 用户名 + 密码 → 会话 token | relay key |
| 用的人 | APP 自己 | cc-switch |

塞在一起的话，`/api/account/login` 会被中转那个兜底路由
`/{path:path}` 吃掉——那个路由必须最后注册，而「最后」这件事
一旦和别的 router 混在一个文件里就很难保证。分开就没有这个隐患。
"""
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from .accounts import Accounts


class RegisterRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=200)


class LoginRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=200)


def build_account_router(settings, store):
    accounts = Accounts(store)
    router = APIRouter()

    @router.post("/account/register", status_code=201)
    def register(payload: RegisterRequest):
        """注册。

        **不返回 token**：注册完让用户自己登一次。自动登录看着省事，但它让
        「注册」和「登录」两条路都要懂怎么签发会话，而且注册接口一旦被脚本刷，
        自动登录等于顺手帮他建了一堆可用会话。
        """
        created = accounts.register(payload.username, payload.password)
        if created is None:
            raise HTTPException(409, "That username is already taken")
        return created

    @router.post("/account/login")
    def login(payload: LoginRequest):
        """登录，签发会话 token。

        用户名错和密码错返回**同一个 401**——区分开等于送人一个
        「这个用户名存在吗」的探测接口。
        """
        issued = accounts.login(payload.username, payload.password)
        if issued is None:
            raise HTTPException(401, "Incorrect username or password")
        return issued

    @router.get("/account/me")
    def me(authorization: str | None = Header(default=None)):
        """当前账号。App 启动时用它确认会话还有效。"""
        identity = accounts.resolve(authorization)
        if identity is None:
            raise HTTPException(401, "Sign in required", headers={"WWW-Authenticate": "Bearer"})
        user_id, username = identity
        account = accounts.account(user_id) or {}
        # **和注册/login 用同一套字段名**（userId / username），
        # App 侧只认一套，不用为每个接口各写一个解析。
        return {"userId": user_id, "username": username,
                "createdAtEpochMillis": account.get("createdAtEpochMillis")}

    @router.post("/account/logout")
    def logout(authorization: str | None = Header(default=None)):
        """退出。**幂等**——已经失效的 token 也返回成功，
        不然 App 在「token 过期后点退出」会看到一个莫名其妙的错误。"""
        accounts.logout(authorization)
        return {"status": "ok"}

    return router
