from dataclasses import dataclass

from fastapi import HTTPException


@dataclass(frozen=True)
class Identity:
    uid: str
    name: str


class TableAuth:
    """用**我们自己的** `accounts` 表验会话。**这是唯一一条鉴权路。**

    §2026-02：这里原来有两个实现§，另一个叫 `TeamAuth`——拿 token 去问
    `http://127.0.0.1:8000/api/users/me`，也就是那台机器上**别人的**账号服务。
    它已经删了，理由正是它当年存在的理由的反面：

    * 这个 APP 里没有「团队」这回事，用户是 APP 的用户，账号该归我们。
      锁在别人的账号体系上，它的 schema、可用性、策略都牵着我们走，
      而且「用户是谁」这个答案一直在别人库里。
    * 它还有一条**读不出答案**的失败模式：对端换了字段名或返回形状，
      我们这边只会拿到一个 503——而 503 看起来像「我们的服务挂了」。

    `Identity.uid` 就是账号的 `user_id`——`posts.author_uid` 存的一直是这个值，
    所以**论坛的数据一个字都不用改**。
    """

    def __init__(self, store, accounts=None):
        from .accounts import Accounts
        self.accounts = accounts or Accounts(store)

    def verify(self, authorization):
        identity = self.accounts.resolve(authorization)
        if identity is None:
            # 给 401 和 `WWW-Authenticate`，App 侧据此判断该弹登录。
            raise HTTPException(401, "Sign in required", headers={"WWW-Authenticate": "Bearer"})
        user_id, username = identity
        return Identity(user_id, username)

    def close(self):
        """没有要关的资源（我们只是查表）。留着这个方法是因为 `app.py` 的
        lifespan 会调它——接口统一比省这一行值钱。"""
