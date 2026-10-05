"""APP 账号的 HTTP 接口：注册、验证邮箱、重发验证码、登录、查我、退出。

**这几个接口以后端 `/api/account/*` 提供，不再打那台机器上的其它服务。**

## 为什么单独一个 router，不塞进别处

它们回答的是「你是谁」（邮箱/用户名 + 密码 → 会话 token），论坛和智能体回答
「你能干什么」。分开之后 `/api/account/login` 不会被别的前缀吃掉，路由表里也一眼
能看出身份这条线只有这几个入口。

## 错误码是接口的一部分（2026-10-05 起）

响应统一是 `{"code", "message"}`，而 `code` **不是**装饰：客户端要靠它决定下一步
（去验证邮箱 / 重发验证码 / 等 60 秒 / 换一个用户名）。用 400 一锅端的话，
"邮箱格式不对"和"验证码过期"在界面上就只能是同一句话。

## 邮箱验证这条路

`register`（建号 + 发码）→ `verify`（核销码）→ `login`（此时才放行）。
`resend` 对**存在和不存在的邮箱返回完全一样**：否则它就是一个
「这个邮箱注册过没有」的探测接口。
"""
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from .accounts import Accounts


class RegisterRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    # 长度上限故意放得比 254 宽：邮箱**太**长也要走我们自己的校验，
    # 好回一个 `EMAIL_INVALID`（能告诉用户"邮箱这一栏有问题"），
    # 而不是 pydantic 的 `INVALID_INPUT`（那在界面上只能显示成"参数不对"）。
    email: str = Field(default="", max_length=1000)
    username: str = Field(default="", max_length=200)
    password: str = Field(default="", max_length=1000)


class LoginRequest(BaseModel):
    """`identifier` 可以是邮箱也可以是用户名。

    `username` 这个字段名**留着**：装在用户手机上的旧版 App 还在发它，
    而"换个字段名"这种改动不该让一个已经能用的版本突然登不进去。
    两个都给的时候以 `identifier` 为准。
    """
    model_config = ConfigDict(extra="forbid")
    identifier: str | None = Field(default=None, max_length=1000)
    username: str | None = Field(default=None, max_length=200)
    password: str = Field(default="", max_length=1000)


class VerifyRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str = Field(default="", max_length=1000)
    code: str = Field(default="", max_length=32)


class EmailRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    email: str = Field(default="", max_length=1000)


class ResetRequest(BaseModel):
    """用重置码改密码。**不需要旧密码**——忘了密码的人拿不出它。"""
    model_config = ConfigDict(extra="forbid")
    email: str = Field(default="", max_length=1000)
    code: str = Field(default="", max_length=32)
    password: str = Field(default="", max_length=1000)


class ChangeRequest(BaseModel):
    """已登录用户改密码：要旧密码（确认是本人），当前会话保留。"""
    model_config = ConfigDict(extra="forbid")
    currentPassword: str = Field(default="", max_length=1000)
    newPassword: str = Field(default="", max_length=1000)


def build_account_router(settings, store, mailer=None):
    # 测试可以塞一个"把验证码记下来"的假发信口；生产永远是 `settings.mailer`
    # （配置来自 env，`dev_echo` 的闸门也在那一层）。
    accounts = Accounts(store, mailer or settings.mailer)
    router = APIRouter()

    @router.post("/account/register", status_code=201)
    def register(payload: RegisterRequest):
        """注册 + 发验证码。

        **不返回 token**：注册完让用户自己登一次。自动登录看着省事，但它让
        「注册」和「登录」两条路都要懂怎么签发会话，而且注册接口一旦被脚本刷，
        自动登录等于顺手帮他建了一堆可用会话。

        **发信失败时账号会被删掉**（见 `Accounts.register`）：宁可让用户重填一次，
        也不要留下一个占着邮箱、谁也验证不了的号。
        """
        return accounts.register(payload.username, payload.password, payload.email)

    @router.post("/account/login")
    def login(payload: LoginRequest):
        """登录，签发会话 token。

        邮箱/用户名错和密码错返回**同一个 401**——区分开等于送人一个
        「这个账号存在吗」的探测接口。**没验证邮箱是另一回事**：那是 403
        `EMAIL_UNVERIFIED`，客户端要跳去验证界面，所以必须分得开。
        """
        issued = accounts.login(payload.identifier or payload.username, payload.password)
        if issued is None:
            raise HTTPException(401, "Incorrect email/username or password")
        return issued

    @router.post("/account/verify")
    def verify(payload: VerifyRequest):
        """核销验证码。**这一步不给 token**——验证成功之后用刚填的密码登一次。

        邮箱不存在时按"码不对"回（和 `resend` 一样，不给探测接口）。
        """
        return accounts.verify_email(payload.email, payload.code)

    @router.post("/account/verify/resend")
    def resend(payload: EmailRequest):
        """重发验证码。

        **无论这个邮箱有没有注册过都返回成功**（不泄露账号是否存在），
        限流照着邮箱记，所以两种情况的表现完全一致。
        """
        return accounts.resend_verification(payload.email)

    @router.post("/account/password/forgot")
    def forgot(payload: EmailRequest):
        """忘记密码 → 发一条重置码。

        **注册过的邮箱和没注册过的邮箱返回完全一样**（不泄露账号是否存在），
        也没给陌生地址发信（见 `Accounts.request_password_reset`）。
        """
        return accounts.request_password_reset(payload.email)

    @router.post("/account/password/reset")
    def reset(payload: ResetRequest):
        """用重置码改密码。**成功之后所有会话失效**，客户端要用新密码重新登一次。

        这一步**不发 token**：和注册/验证一样，签发会话只发生在 `login`。
        """
        return accounts.reset_password(payload.email, payload.code, payload.password)

    @router.post("/account/password/change")
    def change(payload: ChangeRequest,
               authorization: str | None = Header(default=None)):
        """已登录用户改密码。**只踢掉其它设备**，当前会话留着。"""
        identity = accounts.resolve(authorization)
        if identity is None:
            raise HTTPException(401, "Sign in required", headers={"WWW-Authenticate": "Bearer"})
        return accounts.change_password(identity[0], payload.currentPassword,
                                        payload.newPassword, authorization)

    @router.get("/account/me")
    def me(authorization: str | None = Header(default=None)):
        """当前账号。App 启动时用它确认会话还有效。"""
        identity = accounts.resolve(authorization)
        if identity is None:
            raise HTTPException(401, "Sign in required", headers={"WWW-Authenticate": "Bearer"})
        # **和注册/login 用同一套字段名**（userId / username / email / emailVerified），
        # App 侧只认一套，不用为每个接口各写一个解析。
        return accounts.account(identity[0]) or {"userId": identity[0], "username": identity[1]}

    @router.post("/account/logout")
    def logout(authorization: str | None = Header(default=None)):
        """退出。**幂等**——已经失效的 token 也返回成功，
        不然 App 在「token 过期后点退出」会看到一个莫名其妙的错误。"""
        accounts.logout(authorization)
        return {"status": "ok"}

    return router
