"""本地端到端验收：真实 uvicorn + 真实 HTTP，不是 TestClient。

跑法（见 `backend/README.md` 的本地验证一节）：

    /tmp/tokentrail-forum-venv/bin/python backend/scripts/check_relay_local.py

它自己起两个进程：一个假上游（认证、按请求体回 usage），一个中转服务，
然后走完 `注册 → 转发 → 记账` 全程，并顺带验三个应当失败的路径
（内网上游、重复 key、拿团队 token 打中转）。
"""
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from contextlib import closing
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

RELAY_KEY = "tt_e2e_relay_key_0123456789abcdef"
UPSTREAM_KEY = "sk-upstream-e2e-secret"
# 端到端脚本自己注册的账号。它的 `userId` 是**所有账的归属**——
# 用量、赛季、预算、智能体都记在它下面，不是记在 relay key 上。
ACCOUNT = "e2e-runner"
PASSWORD = "e2e-password-12345"
ROOT = Path(__file__).resolve().parents[1]


def free_port():
    with closing(socket.socket()) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class FakeUpstream(BaseHTTPRequestHandler):
    """假上游：要求 Bearer 等于用户的上游 key，然后回一个带 usage 的响应。"""

    seen = []

    def do_POST(self):
        length = int(self.headers.get("content-length", 0))
        body = self.rfile.read(length)
        FakeUpstream.seen.append({"path": self.path, "auth": self.headers.get("authorization"),
                                  "body": body})
        if self.headers.get("authorization") != "Bearer " + UPSTREAM_KEY:
            self.send_response(401)
            self.send_header("content-type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"error":{"message":"bad upstream key"}}')
            return
        payload = json.dumps({
            "model": "deepseek-chat", "choices": [{"message": {"content": "pong"}}],
            "usage": {"prompt_tokens": 1200, "completion_tokens": 340,
                      "prompt_tokens_details": {"cached_tokens": 900}}}).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("x-upstream", "fake")
        self.send_header("content-length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def call(url, method="GET", body=None, token=None):
    # **必须显式禁用代理。** macOS 的系统代理是「系统级」设置，`urllib` 会读它，
    # 而 `NO_PROXY` 环境变量对它无效。本机验收打的是 127.0.0.1，走代理会被代理
    # 用 502 回掉，看起来像「我们的服务坏了」，其实是请求根本没到服务。
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(url, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
    request.add_header("content-type", "application/json")
    if token:
        request.add_header("authorization", "Bearer " + token)
    try:
        with opener.open(request, timeout=20) as response:
            return response.status, json.loads(response.read() or b"{}"), dict(response.headers)
    except urllib.error.HTTPError as error:
        # 错误响应不一定是 JSON：404 是空体，500 可能是 HTML。
        # 直接 `json.loads` 会在**验收脚本自己**这里炸掉，还看不出是哪个接口。
        raw = error.read()
        try:
            payload = json.loads(raw or b"{}")
        except ValueError:
            payload = {"raw": raw.decode("utf-8", "replace")[:200]}
        return error.code, payload, dict(error.headers)


def describe(item):
    """把假上游收到的一条记录变成可比较的文本（body 是 bytes，不能直接 json.dumps）。"""
    return json.dumps({"path": item["path"], "auth": item["auth"],
                       "body": item["body"].decode("utf-8", "replace")})


def cst_millis(day, hour, minute=0):
    """北京时间的某个时刻 → UTC 毫秒。和 `relay_store.DAY_OFFSET_SECONDS` 是同一个口径，
    这里独立算一遍（不调被测代码），免得把「服务端怎么算的」当成预期答案。"""
    import datetime
    moment = datetime.datetime.strptime(f"{day} {hour:02d}:{minute:02d}", "%Y-%m-%d %H:%M")
    return int(moment.replace(tzinfo=datetime.timezone(datetime.timedelta(hours=8))).timestamp() * 1000)


def digest_of(relay_key):
    """relay key → sha256，和 `relay_store.key_hash` 同口径（独立实现，理由同上）。

    注意它**不再是身份**：用量、赛季、预算的归属是账号的 `userId`
    （登录响应里的那个字段），不是这个 hash。
    """
    import hashlib
    return hashlib.sha256(relay_key.encode()).hexdigest()


def datetime_now_day():
    """北京时间今天，`yyyy-MM-dd`。独立算，不调被测代码。"""
    import datetime
    return (datetime.datetime.now(datetime.timezone.utc)
            + datetime.timedelta(hours=8)).strftime("%Y-%m-%d")


def wait_for(port, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        with closing(socket.socket()) as probe:
            probe.settimeout(0.4)
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.2)
    return False
    deadline = time.time() + timeout
    while time.time() < deadline:
        with closing(socket.socket()) as probe:
            probe.settimeout(0.4)
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.2)
    return False


def main():
    failures = []

    def check(label, condition, detail=""):
        print(("  ✓ " if condition else "  ✗ ") + label + (f"  {detail}" if detail and not condition else ""))
        if not condition:
            failures.append(label)

    upstream_port, relay_port = free_port(), free_port()
    upstream = HTTPServer(("127.0.0.1", upstream_port), FakeUpstream)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()

    data_dir = tempfile.mkdtemp(prefix="relay-e2e-")
    environment = {**os.environ,
                   "FORUM_DATA_DIR": data_dir,
                   "FORUM_PUBLIC_ORIGIN": "https://forum.example",
                   "FORUM_ENABLE_RELAY": "1",
                   # 智能体开着（但 key 是假的）：要验的是**鉴权边界**——
                   # 账号 token 能进、relay key 不能。真调模型不在这里做。
                   "FORUM_ENABLE_AGENT": "1",
                   # 假上游在回环地址上，所以本地验收必须放开这两道（生产默认都关着）。
                   "FORUM_RELAY_ALLOW_PRIVATE": "1",
                   "FORUM_RELAY_ALLOWED_HOSTS": "",
                   "FORUM_RELAY_SELF_HOSTS": "",
                   # 把内置价目种子录上，好验证「有价目表」这条路。
                   # 种子只含 DeepSeek——另外两家没实测过，不编。
                   "FORUM_PRICING_SEED": "1",
                   # 本机代理软件会让 uvicorn 的出站/入站都出问题，验收必须直连。
                   "NO_PROXY": "127.0.0.1,localhost",
                   "no_proxy": "127.0.0.1,localhost"}
    server = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "tokentrail_forum.app:application", "--factory",
         "--host", "127.0.0.1", "--port", str(relay_port), "--no-access-log"],
        cwd=ROOT, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    base = f"http://127.0.0.1:{relay_port}"
    # 抓一份启动日志，服务起不来时能直接看到原因，而不是只看到一堆 502。
    log = []
    threading.Thread(target=lambda: [log.append(line) for line in server.stdout], daemon=True).start()

    try:
        if not wait_for(relay_port):
            print("中转服务没起来，输出如下：")
            print("".join(log))
            return 1
        time.sleep(0.5)
        print(f"假上游 :{upstream_port}   中转 :{relay_port}\n")

        print("1. 健康检查")
        status, health, _ = call(base + "/health")
        check("health 200 且 relayEnabled=true", status == 200 and health.get("relayEnabled") is True,
              f"{status} {health}")

        print("2. 账号（中转 key 挂在账号下面，所以先要有账号）")
        # 账号是这个 App 自己的：注册完**不发 token**（服务端有意为之），所以这里
        # 显式走一次登录拿到会话 token。**所有管理接口都用它**——
        # relay key 只用于转发那一条路（下面第 4 步）。
        status, account, _ = call(base + "/api/account/register", "POST",
                                  {"username": ACCOUNT, "password": PASSWORD})
        check("注册账号 201", status in (201, 409), f"{status} {account}")
        status, session, _ = call(base + "/api/account/login", "POST",
                                  {"username": ACCOUNT, "password": PASSWORD})
        check("登录 200 且拿到 tt_app_ token",
              status == 200 and str(session.get("token", "")).startswith("tt_app_"),
              f"{status} {session}")
        app_token = session.get("token")
        user_id = session.get("userId")
        check("userId 是 u_ 开头（这是所有账的归属）",
              isinstance(user_id, str) and user_id.startswith("u_"), str(user_id))
        # 密码错要和用户名错给同一个 401，不能区分（区分等于送一个探测接口）
        bad = call(base + "/api/account/login", "POST",
                   {"username": ACCOUNT, "password": PASSWORD + "x"})[0]
        check("密码错是 401", bad == 401, str(bad))
        # 没登录不能注册 relay key
        anon = call(base + "/api/relay/keys", "POST",
                    {"upstreamUrl": f"http://127.0.0.1:{upstream_port}",
                     "upstreamKey": UPSTREAM_KEY})[0]
        check("没登录时注册 relay key 是 401", anon == 401, str(anon))

        print("3. 注册 relay key（自动生成）")
        status, created, _ = call(base + "/api/relay/keys", "POST",
                                  {"upstreamUrl": f"http://127.0.0.1:{upstream_port}",
                                   "upstreamKey": UPSTREAM_KEY, "displayName": "e2e"},
                                  app_token)
        check("注册 201", status == 201, f"{status} {created}")
        generated = created.get("relayKey", "")
        check("返回了自动生成的 relay key", generated.startswith("tt_") and len(generated) > 24)
        check("响应里没有上游密钥", UPSTREAM_KEY not in json.dumps(created))
        check("provider 认不出来时留空（本地假上游）", created.get("provider") is None)

        print("4. 自定义 key 注册 + 冲突")
        status, custom, _ = call(base + "/api/relay/keys", "POST",
                                 {"upstreamUrl": f"http://127.0.0.1:{upstream_port}",
                                  "upstreamKey": UPSTREAM_KEY, "relayKey": RELAY_KEY},
                                 app_token)
        check("自定义 key 注册 201", status == 201, f"{status} {custom}")
        status, _, _ = call(base + "/api/relay/keys", "POST",
                            {"upstreamUrl": f"http://127.0.0.1:{upstream_port}",
                             "upstreamKey": UPSTREAM_KEY, "relayKey": RELAY_KEY},
                            app_token)
        check("同一个 key 再注册被拒（409）", status == 409, str(status))
        status, _, _ = call(base + "/api/relay/keys", "POST",
                            {"upstreamUrl": f"http://127.0.0.1:{upstream_port}",
                             "upstreamKey": UPSTREAM_KEY, "relayKey": "short"},
                            app_token)
        check("太短的自定义 key 被拒（400）", status == 400, str(status))

        print("5. 转发（这是 cc-switch 会走的那条路）")
        sent = {"model": "deepseek-chat", "messages": [{"role": "user", "content": "ping"}]}
        status, answer, headers = call(base + "/api/relay/v1/chat/completions", "POST", sent, RELAY_KEY)
        check("转发返回 200", status == 200, f"{status} {answer}")
        check("响应体原样回来", answer.get("choices", [{}])[0].get("message", {}).get("content") == "pong")
        check("上游响应头保留", headers.get("x-upstream") == "fake")
        check("上游收到的是用户的上游 key",
              bool(FakeUpstream.seen) and FakeUpstream.seen[-1]["auth"] == "Bearer " + UPSTREAM_KEY,
              describe(FakeUpstream.seen[-1]) if FakeUpstream.seen else "(上游没收到请求)")
        check("上游收到的路径没有重复 /v1",
              bool(FakeUpstream.seen) and FakeUpstream.seen[-1]["path"] == "/v1/chat/completions",
              FakeUpstream.seen[-1]["path"] if FakeUpstream.seen else "")
        check("请求体一个字节没动",
              bool(FakeUpstream.seen) and json.loads(FakeUpstream.seen[-1]["body"]) == sent)
        check("上游没看到我们的 relay key",
              all(RELAY_KEY not in describe(item) for item in FakeUpstream.seen))

        print("5. 记账")
        status, usage, _ = call(base + "/api/relay/usage", token=RELAY_KEY)
        item = (usage.get("items") or [{}])[0]
        check("usage 200", status == 200, str(status))
        # **账记在账号上。** 同一个账号换一条 relay key，这里读到的还是同一份用量。
        check("uid 是账号的 userId（不是 relay key 的 sha256）",
              usage.get("uid") == user_id, f'{usage.get("uid")} != {user_id}')
        check("四桶正确（input 已扣掉缓存）",
              (item.get("input"), item.get("cacheRead"), item.get("cacheWrite"), item.get("output")) == (300, 900, 0, 340),
              str(item))
        check("calls=1", item.get("calls") == 1)
        check("模型名落在记录上（不是落在 key 上）", item.get("model") == "deepseek-chat")

        print("5b. 日汇总（服务端成为唯一数据源的那一层）")
        status, rollup, _ = call(base + "/api/relay/usage/daily", token=RELAY_KEY)
        check("日汇总 200", status == 200, str(status))
        check("时区口径是 Asia/Shanghai", rollup.get("timezone") == "Asia/Shanghai")
        rows = rollup.get("items") or []
        check("有一条日汇总", len(rows) == 1, str(rows))
        if rows:
            row = rows[0]
            # 用本机时钟独立算一遍「今天」（北京时区），不拿服务端的答案当预期。
            import datetime
            today = (datetime.datetime.now(datetime.timezone.utc)
                     + datetime.timedelta(hours=8)).strftime("%Y-%m-%d")
            check("日期是北京时间的今天", row.get("day") == today, f"{row.get('day')} vs {today}")
            check("四桶在日汇总里保持正确",
                  (row.get("input"), row.get("cacheRead"), row.get("cacheWrite"), row.get("output")) == (300, 900, 0, 340),
                  str(row))
            # 服务端没有价目表，所以必须报「算不出价」而不是 0。
            check("算不出价时留 null 而不是 0", row.get("costMicros") is None and row.get("rateVersion") is None)
            check("显式声明尚未计价", rollup.get("pricingAvailable") is False)
            check("uid 由服务端填（且是账号的 userId）", row.get("uid") == user_id,
                  f'{row.get("uid")} != {user_id}')
        check("日汇总也要 relay key",
              call(base + "/api/relay/usage/daily", token="team-token")[0] == 401)
        # §这一条是整个改动的验收点§：账号 token 和 relay key 读到**同一份账**。
        # 用户没配中转（或者换了 key）时，用量和余额不能跟着丢。
        same = call(base + "/api/relay/usage", token=app_token)[1]
        check("账号 token 读到同一份用量（身份是账号，不是 key）",
              same.get("uid") == usage.get("uid") and
              sum(i.get("input", 0) for i in same.get("items", [])) ==
              sum(i.get("input", 0) for i in usage.get("items", [])),
              f"{same} vs {usage}")
        # 而智能体**只认账号 token**：relay key 打过去必须 401。
        # 智能体只认账号 token。
        check("relay key 不能用来问智能体（401）",
              call(base + "/api/relay/agent/status", token=RELAY_KEY)[0] == 401)
        status, agent_status, _ = call(base + "/api/relay/agent/status", token=app_token)
        check("账号 token 能查智能体状态", status == 200, f"{status} {agent_status}")
        check("智能体自己的账本独立（这个账号还没问过）",
              agent_status.get("separateLedger") is True
              and agent_status.get("ownCostMicros") == 0, str(agent_status))

        print("5b2. 逐次记录（UsageCall 的形状）")
        status, calls_body, _ = call(base + "/api/relay/usage/calls", token=RELAY_KEY)
        check("逐次记录 200", status == 200, str(status))
        rows = calls_body.get("items") or []
        check("转发产生了至少一条记录", len(rows) >= 1, str(len(rows)))
        if rows:
            row = rows[0]
            # 前缀 `call:` 是必需的——它决定这行能不能做按会话分析。
            check("id 以 call: 开头", str(row.get("id", "")).startswith("call:"), str(row.get("id")))
            check("source 是 IMPORTED（只有它进汇总/预算/资源）",
                  row.get("source") == "IMPORTED", str(row.get("source")))
            check("逐次记录里也算不出价（null 而非 0）",
                  row.get("costMicros") is None and row.get("costCurrency") is None, str(row))
            check("四桶在逐次记录里保持正确",
                  (row.get("input"), row.get("cacheRead"), row.get("output")) == (300, 900, 340), str(row))
        check("每条记录的 id 都不同（去重键不能撞）",
              len({r["id"] for r in rows}) == len(rows), str([r.get("id") for r in rows]))
        check("逐次记录也要 relay key",
              call(base + "/api/relay/usage/calls", token="team-token")[0] == 401)

        print("5b3. 价目表")
        status, listing, _ = call(base + "/api/relay/pricing", token=RELAY_KEY)
        check("价目表 200", status == 200, str(status))
        check("种子里只有实测过的 DeepSeek",
              {item["provider"] for item in listing.get("items", [])} == {"DEEPSEEK"},
              str({item["provider"] for item in listing.get("items", [])}))
        check("每条都有出处和版本",
              all(item.get("source_url") and item.get("rate_version") for item in listing.get("items", [])))
        status, converted, _ = call(base + "/api/relay/pricing/convert", "POST", {"cnyPer1M": 1}, RELAY_KEY)
        check("人民币换算在服务端做（1 元/1M ≈ 140845 微美元/1M）",
              status == 200 and converted.get("inputMicrosPer1M") == 140845, str(converted))
        # 假上游是回环地址 → provider 认不出来 → 即使有价目表也算不出价。
        # 这正是要证明的：**「有价目表」和「这一笔算得出来」是两件事**。
        check("认不出 provider 时仍算不出价（null 而非 0）",
              row.get("costMicros") is None, str(row.get("costMicros")))
        # 讲清是哪几行没价：模型名是拿得到的（`deepseek-chat`），
        # 认不出来的是 **provider**——所以 `unpricedModels` 里是模型名而不是 "unknown"。
        check("并且列出是哪个模型没价", "deepseek-chat" in (rollup.get("unpricedModels") or []),
              str(rollup.get("unpricedModels")))

        print("5c. 结算与资源余额（服务端是权威）")
        # 刚才那次转发写的是**今天**，而规则 1 是「今天永远不结算」。
        # 所以把它往前挪两天——只有过完的天才该发资源。
        # **挪两天而不是一天**：下面要再造一笔「足够换出资源」的用量，
        # 而结算的顺序是单向的（`lastSettledDay` 一旦推到某天，
        # 那天之前的用量就永远不再结算——这是规则 4 要求的）。
        # 两笔都落在同一个还没结算过的天，才能一次性验证换算率。
        import sqlite3
        from datetime import datetime as _dt, timezone as _tz, timedelta as _td
        target_day = (_dt.now(_tz.utc) + _td(hours=8) - _td(days=2)).strftime("%Y-%m-%d")
        future_day = (_dt.now(_tz.utc) + _td(hours=8) - _td(days=1)).strftime("%Y-%m-%d")
        with sqlite3.connect(os.path.join(data_dir, "forum.sqlite3")) as db:
            moved = db.execute("UPDATE relay_usage SET created=created-172800000").rowcount
        check("把用量挪到两天前（为测结算）", moved >= 1, f"改了 {moved} 行")

        status, before, _ = call(base + "/api/relay/season", token=RELAY_KEY)
        check("赛季状态 200", status == 200, str(status))
        check("结算前余额是 0", before.get("balance") == {"input": 0, "cache": 0, "output": 0}, str(before))
        check("服务端报出换算率", before.get("tokensPerUnit") == 10000, str(before.get("tokensPerUnit")))
        check("本月 token 统计在（转发那次的四桶）",
              before.get("monthTokens", {}).get("input") == 300, str(before.get("monthTokens")))

        # 再补一笔「够大」的用量，落在**同一个**还没结算过的天。
        # 一次转发只产生几千 token，够不到一个单位（10000），所以换算率必须造数才测得到。
        with sqlite3.connect(os.path.join(data_dir, "forum.sqlite3")) as db:
            # 归属列是**账号的 user_id**（2026-02 起），不是 relay key 的 sha256。
            # 写错了不会报错，只会静静地记到另一个「人」名下，然后查出来是 0。
            db.execute("""INSERT INTO relay_usage(user_id,model,service_tier,provider,created,
                input,cache_read,cache_write,output,calls) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                (user_id, "deepseek-chat", None, "DEEPSEEK",
                 cst_millis(target_day, 12), 1_000_000, 600_000, 400_000, 2_000_000, 1))

        status, settled, _ = call(base + "/api/relay/season/settle", "POST", token=RELAY_KEY)
        check("结算 200", status == 200, str(status))
        check("结算了那一天", settled.get("settledDays") == [target_day], str(settled.get("settledDays")))
        # 100 万 input → 100；缓存两桶合并 (60万+40万)/1万 = 100；200 万 output → 200。
        # 转发那次的小额（300/900/340）不到一个单位，换不出东西——**向下取整**。
        check("100 万 input → 100 资源", settled.get("balanceAfter", {}).get("input") == 100,
              str(settled.get("balanceAfter")))
        check("缓存两桶先加总再取整 → 100", settled.get("balanceAfter", {}).get("cache") == 100,
              str(settled.get("balanceAfter")))
        check("200 万 output → 200 资源", settled.get("balanceAfter", {}).get("output") == 200,
              str(settled.get("balanceAfter")))

        status, again, _ = call(base + "/api/relay/season/settle", "POST", token=RELAY_KEY)
        check("再结算一次是幂等的（没有新的一天）", again.get("settledDays") == [], str(again.get("settledDays")))
        check("幂等那次的余额不变", again.get("balanceAfter") == settled.get("balanceAfter"))

        status, spent, _ = call(base + "/api/relay/season/spend", "POST",
                                {"input": 40, "cache": 25, "output": 10}, RELAY_KEY)
        check("扣款成功", status == 200, f"{status} {spent}")
        check("余额按扣减后的值返回",
              spent.get("balance") == {"input": 60, "cache": 75, "output": 190}, str(spent.get("balance")))
        status, over, _ = call(base + "/api/relay/season/spend", "POST", {"input": 61}, RELAY_KEY)
        check("超支被拒（409）", status == 409, f"{status} {over}")
        status, _, _ = call(base + "/api/relay/season/spend", "POST", {"input": -5}, RELAY_KEY)
        check("负数扣款被拒（400）", status == 400, str(status))
        status, after, _ = call(base + "/api/relay/season", token=RELAY_KEY)
        check("超支请求没有改动余额（三种资源不通兑）",
              after.get("balance") == {"input": 60, "cache": 75, "output": 190}, str(after.get("balance")))
        status, days, _ = call(base + "/api/relay/season/days", token=RELAY_KEY)
        check("已结算天数流水只有一条", len(days.get("items") or []) == 1, str(days.get("items")))
        check("结算日期不可能是今天或未来",
              all(item["day"] < future_day for item in (days.get("items") or [])), str(days.get("items")))

        print("5d. 预算（只存上限，花销现算）")
        month = target_day[:7]
        status, fresh, _ = call(base + f"/api/relay/budgets/{month}", token=RELAY_KEY)
        check("读预算 200", status == 200, str(status))
        # 「没设预算」必须是 configured=false + cap 为 null，**不是** cap=0。
        check("没设预算时 configured=false 且 cap 是 null",
              fresh.get("configured") is False and fresh.get("capMicros") is None, str(fresh))
        # 没有价目表 → 花销报 0 但必须显式说算不出来。
        # 有价的天算进 spentMicros，**没价的天进 unpricedDays 而不是当 0 加进来**。
        # 只要有一天没价，`pricingAvailable` 就是 false——客户端据此显示
        # 「价格未知」而不是一个偏低的总额。
        check("算不出价的天单列出来，不当 0 加进花销",
              isinstance(fresh.get("unpricedDays"), list)
                      and fresh.get("spentMicros", 0) >= 0, str(fresh))
        check("有没价的天时 pricingAvailable 为 false",
              fresh.get("pricingAvailable") is False, str(fresh.get("pricingAvailable")))
        check("覆盖度算到本月今天", fresh.get("coverage", {}).get("to") == datetime_now_day(),
              str(fresh.get("coverage")))

        status, saved, _ = call(base + f"/api/relay/budgets/{month}", "PUT",
                                {"capMicros": 20000000, "warnAtRatio": 0.5}, RELAY_KEY)
        check("存预算 200", status == 200, f"{status} {saved}")
        check("回读拿到刚才存的上限", saved.get("capMicros") == 20000000, str(saved))
        status, again_budget, _ = call(base + f"/api/relay/budgets/{month}", token=RELAY_KEY)
        check("设过之后 configured=true", again_budget.get("configured") is True, str(again_budget))
        check("设过的上限回读一致", again_budget.get("capMicros") == 20000000, str(again_budget))
        status, _, _ = call(base + "/api/relay/budgets/2026-13", token=RELAY_KEY)
        check("非法月份是 400 而不是 500", status == 400, str(status))
        status, _, _ = call(base + f"/api/relay/budgets/{month}", token="team-token")
        check("预算也要 relay key", status == 401, str(status))

        print("6. 认证边界")
        status, _, _ = call(base + "/api/relay/v1/chat/completions", "POST", sent, "team-session-token")
        check("团队 token 打中转被拒（401）", status == 401, str(status))
        # 本地没有配置团队账号服务（FORUM_AUTH_URL 为空），所以任何非 relay 的 token
        # 打到论坛都会得到 503「账号服务未配置」而不是 401。**这不是 relay 的问题**，
        # 但真正要证明的性质是：relay key **永远拿不到 200**，身份隔离才成立。
        status, rejected, _ = call(base + "/api/forum/posts", token=RELAY_KEY)
        check("relay key 打论坛拿不到 200（身份隔离）", status in (401, 403, 503), f"{status}")
        check("拒绝理由来自账号服务而不是放行", status != 200 and "relay" not in json.dumps(rejected).lower(),
              json.dumps(rejected))
        status, _, _ = call(base + "/api/relay/v1/chat/completions", "POST", sent, "sk-some-upstream-key")
        check("上游 key 当 relay key 用被拒（401）", status == 401, str(status))

        print("7. 上游报错如实透传，不伪装成成功")
        FakeUpstream.seen.clear()
        status, _, _ = call(base + "/api/relay/v1/chat/completions", "POST", sent, RELAY_KEY)
        check("上游 200 时正常转发", status == 200, str(status))
        # 换一个上游不认的 key，上游会回 401，我们必须如实返回而不是自己造一个错误。
        status, _, _ = call(base + "/api/relay/keys", "PATCH", {"upstreamKey": "sk-wrong"},
                            RELAY_KEY)
        check("改上游 key 成功", status == 200, str(status))
        status, body, _ = call(base + "/api/relay/v1/chat/completions", "POST", sent, RELAY_KEY)
        check("上游 401 如实透传", status == 401 and "bad upstream key" in json.dumps(body), f"{status} {body}")

        print("8. relay key 不在库里留明文")
        blobs = b"".join(path.read_bytes() for path in Path(data_dir).glob("forum.sqlite3*"))
        check("relay key 明文不在数据库里", RELAY_KEY.encode() not in blobs)
        check("上游密钥在（转发必须注入）", UPSTREAM_KEY.encode() in blobs)
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
        upstream.shutdown()
        if failures:
            print("\n--- 中转服务日志 ---")
            print("".join(log[-40:]) or "(空)")

    print()
    if failures:
        print(f"失败 {len(failures)} 项：" + "；".join(failures))
        return 1
    print("本地端到端验收全部通过。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
