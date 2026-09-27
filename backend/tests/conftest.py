"""测试共用的注入点。

## `verifier()` 是什么，为什么在这里

`create_app(settings, verifier)` 的那个 `verifier` 只该是**测试**用的：
生产形态永远是 `TableAuth`（查我们自己的 `account_sessions` 表）。

绝大多数用例要验的不是「登录流程」，而是**登录之后的事**——发帖、点赞、
用量归属、结算、智能体。让每个用例都去 `/api/account/register|login` 走一遍，
只会让那些测试多两行样板，并且把「账号」这个主题混进每个文件里。
所以这里给一个**认两个固定身份**的假实现：

    Bearer account-a  →  uid "a",  名字 "User A"
    Bearer account-b  →  uid "b",  名字 "User B"
    别的              →  401

它**不发任何 HTTP 请求**。2026-02 之前这个假实现是拿 `TeamAuth` +
`httpx.MockTransport` 拼出来的——那时候「真的鉴权」是去问团队那台机器的账号服务，
假实现自然要模仿一次 HTTP 往返。现在真的鉴权是查表，假实现就没必要再假装发请求了。

**账号本身的测试在 `test_accounts.py`**：那里是**不注入 verifier** 的，
走的就是生产那条 `TableAuth` 路。
"""
from fastapi import HTTPException


class FakeAuth:
    """认 `Bearer account-a|b` 的假 verifier。接口和 `TableAuth.verify` 一样。"""

    ACCOUNTS = {"account-a": ("a", "User A"), "account-b": ("b", "User B")}

    def verify(self, authorization):
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "Sign in required", headers={"WWW-Authenticate": "Bearer"})
        known = self.ACCOUNTS.get(authorization[7:].strip())
        if known is None:
            raise HTTPException(401, "Sign in required", headers={"WWW-Authenticate": "Bearer"})
        from tokentrail_forum.auth import Identity
        return Identity(*known)

    def close(self):
        """和 `TableAuth` 一样什么都不用关——`app.py` 的 lifespan 会调它。"""


def verifier():
    return FakeAuth()
