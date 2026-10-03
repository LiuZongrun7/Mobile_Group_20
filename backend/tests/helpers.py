"""测试之间共用的东西。

2026-09-30 之前这些住在 `test_relay.py` 里——那时候还有"中转"这个功能要测，
账号、用量播种这些帮手顺手都放在那儿。中转整块删掉之后，剩下的用例只需要两样：
**一个账号**（身份的唯一口径）和**往库里塞一行用量**（造跨日边界的数据），
于是搬到这个文件，名字也不再跟 relay 绑在一起。
"""
from tokentrail_forum.usage_store import INDEXES, TABLES

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


def insert_usage(tmp_path, digest, created, provider=None, model="deepseek-chat",
                 input_tokens=10, cache_read=0, cache_write=0, output=5, calls=1,
                 call_id=None):
    """直接往 `relay_usage` 插一行，带指定的 `created`。

    日汇总必须能测**跨日边界**，而边界靠真实调用是造不出来的（真实调用写的是
    `now_ms()`），直接插是唯一能精确控制时刻的办法。

    表不存在时先建出来：有些用例要在第一次启动 app **之前**就把数据造好。

    `digest` 传账号的 `userId`（`account_login(api)["userId"]`）。传错了不报错，
    只会静静地记到另一个人名下、然后查出来是 0。

    `call_id` 默认 null：那代表"迁移之前写下的老行"（`/usage/calls` 会跳过它们，
    没有 id 就没法给客户端一个稳定的去重键）。要测逐次记录就显式传。
    """
    import sqlite3
    with sqlite3.connect(tmp_path / "forum.sqlite3") as db:
        db.executescript(TABLES)
        db.executescript(INDEXES)
        db.execute("""INSERT INTO relay_usage(user_id,model,service_tier,provider,created,
            input,cache_read,cache_write,output,calls,call_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            (digest, model, None, provider, created, input_tokens, cache_read,
             cache_write, output, calls, call_id))


def cst_millis(day, hour, minute=0):
    """把北京时间的一个时刻换成 UTC 毫秒。测试里用**字面量**算，不经过被测代码。"""
    from datetime import datetime, timezone as tz, timedelta
    moment = datetime.strptime(f"{day} {hour:02d}:{minute:02d}", "%Y-%m-%d %H:%M")
    return int(moment.replace(tzinfo=tz(timedelta(hours=8))).timestamp() * 1000)


def daily(api, token, **query):
    """读按天用量。**身份是账号 token**——中转删掉之后没有第二种凭据。"""
    from urllib.parse import urlencode
    suffix = ("?" + urlencode(query)) if query else ""
    return api.get("/api/relay/usage/daily" + suffix, headers=bearer(token))
