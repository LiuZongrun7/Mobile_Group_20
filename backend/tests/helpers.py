"""测试之间共用的东西：账号、认证头、假模型响应、时间字面量。

2026-09-30 之前这里还有"往 relay_usage 里塞一行用量"和"读按天用量"两个帮手，
记账那一整套删掉之后它们没有对象了。现在只剩这些：**测试要一个账号**（身份的唯一
口径）、要一个像模型返回的响应体（智能体的假上游）、要能按北京时间算毫秒。
"""

DEFAULT_PASSWORD = "owner password 123"


def account_login(api, username="owner", password=DEFAULT_PASSWORD):
    """建账号并登录，返回整个响应体。

    幂等：注册撞了就只登录。需要 `userId` 的用例用它——播种用量、断言归属都要知道
    账算在谁头上。**用量归属列就是账号的 `user_id`**（不是 relay key 的 sha256，
    中转删掉之后 relay key 已经不存在了）。
    """
    api.post("/api/account/register", json={"username": username, "password": password})
    return api.post("/api/account/login",
                    json={"username": username, "password": password}).json()


def account_token(api, username="owner", password=DEFAULT_PASSWORD):
    """同上，只要 token。"""
    return account_login(api, username, password)["token"]


def bearer(token):
    """`Authorization` 头。写成一个函数是为了让调用点读起来都是同一句话。"""
    return {"Authorization": "Bearer " + token}


def completion(model="deepseek-chat", prompt=100, completion_tokens=20, cached=0):
    """一个像模型接口返回的响应体。智能体测试用它当假上游。"""
    return {"id": "chatcmpl-1", "model": model, "object": "chat.completion",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": prompt, "completion_tokens": completion_tokens,
                      "total_tokens": prompt + completion_tokens,
                      "prompt_tokens_details": {"cached_tokens": cached}}}



def cst_millis(day, hour, minute=0):
    """把北京时间的一个时刻换成 UTC 毫秒。测试里用**字面量**算，不经过被测代码。"""
    from datetime import datetime, timezone as tz, timedelta
    moment = datetime.strptime(f"{day} {hour:02d}:{minute:02d}", "%Y-%m-%d %H:%M")
    return int(moment.replace(tzinfo=tz(timedelta(hours=8))).timestamp() * 1000)


