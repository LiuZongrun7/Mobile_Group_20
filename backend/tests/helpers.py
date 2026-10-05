"""测试之间共用的东西：账号、认证头、假模型响应、时间字面量。

2026-09-30 之前这里还有"往 relay_usage 里塞一行用量"和"读按天用量"两个帮手，
记账那一整套删掉之后它们没有对象了。现在只剩这些：**测试要一个账号**（身份的唯一
口径）、要一个像模型返回的响应体（智能体的假上游）、要能按北京时间算毫秒。
"""

DEFAULT_PASSWORD = "owner password 123"

# ---- 发信：测试里不真发，验证码落在 OUTBOX ---------------------------------
#
# 真发信在单测里既慢、又需要凭据、还会让测试**依赖一个外部服务**——那正是这类
# 测试最不该有的失败方式。所以测试统一注入这个"记下来"的发信口，
# 用例从 OUTBOX 里取验证码，照样走完 register → verify → login 的真流程
# （被换掉的只有"这一封信怎么送出去"）。
OUTBOX = {}


class RecordingMailer:
    """和 `mailer.Mailer` 同一个接口，但只把码记进 `OUTBOX`。"""

    dev_echo = False
    configured = True

    def send_code(self, email, code, minutes):
        OUTBOX[email] = code


def create_app_for_tests(settings, verifier=None, mailer=None, **kwargs):
    """测试里建 app 的**唯一入口**：顺手把发信口换成记录型的。"""
    from modelpilot_forum.app import create_app
    return create_app(settings, verifier, mailer=mailer or RecordingMailer(), **kwargs)


def latest_code(email):
    assert email in OUTBOX, f"没有给 {email} 发过验证码"
    return OUTBOX[email]


def email_for(username):
    """用户名 → 测试用的邮箱。**和昵称一一对应**，排障时一眼看出是谁。"""
    return f"{username}@example.com"


def register_verified(api, username="owner", password=DEFAULT_PASSWORD):
    """建号 + 验证邮箱，返回邮箱。

    幂等：已经建过（409）就往下走——多半也验证过了，`account_login` 会兜住
    剩下那种"建了号但没验证完"的情况。
    """
    email = email_for(username)
    response = api.post("/api/account/register",
                        json={"email": email, "username": username, "password": password})
    assert response.status_code in (201, 409), response.text
    if response.status_code == 201:
        verification = api.post("/api/account/verify",
                                json={"email": email, "code": latest_code(email)})
        assert verification.status_code == 200, verification.text
    return email


def account_login(api, username="owner", password=DEFAULT_PASSWORD):
    """建账号、验证邮箱、登录，返回整个响应体。

    需要 `userId` 的用例用它——播种用量、断言归属都要知道账算在谁头上。
    **用量归属列就是账号的 `user_id`**。
    """
    email = register_verified(api, username, password)
    response = api.post("/api/account/login", json={"identifier": email, "password": password})
    if response.status_code == 403 and response.json().get("code") == "EMAIL_UNVERIFIED":
        # 上一轮建了号但没验证完（同一个 app 里重复调用会走到这）：
        # 用 OUTBOX 里那封**还没过期的**码补一次验证，不发新码
        # ——发新码会撞上 60 秒重发冷却，那是生产行为，测试不该绕过它。
        api.post("/api/account/verify", json={"email": email, "code": latest_code(email)})
        response = api.post("/api/account/login", json={"identifier": email, "password": password})
    assert response.status_code == 200, response.text
    return response.json()


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


