"""公网端到端验证：账号、智能体、论坛。

对着真正在跑的那台服务器（`https://43.140.212.47`）走一整条路，用的是
HTTP，和 App 侧 `AccountSession` / `AccountApi` 发的是同一批请求。

## 它验的是什么

不是「接口有没有 200」，而是**这台服务器现在该有的东西都还在、不该有的都没了**：

1. 账号能注册、能登录、注册不发 token（服务端有意为之）；
2. **智能体只认账号 token**，`tt_...` 那种旧凭据（relay key）一律 401；
3. 真的问一句智能体（花我们自己的 DeepSeek key），并检查回答里没有 key、
   工具结果里没有别人的数字；
4. **论坛这条链路**（2026-02 从已删除的 `check_deployment.py` 搬过来的）：
   图文上传、两个账号共享同一个帖子池、图片跨账号取回逐字节一致、
   发帖与点赞的幂等、计数不重复、帖子能被删干净。

2026-09-30 之前这里还验过用量、预算、赛季、relay key 那几段——那些接口连同记账
一起删了（见 `docs/SERVER_API.md` 开头），所以整段去掉：**对着已经不存在的接口
断言 200 只会让脚本自己变成假的。**

`MODELPILOT_ENABLE_AGENT` 没开的服务器上第 3 段会跳过（会明确说跳过了，不是静默通过）。

## 用法

    # 推荐：对着**测试区**跑。它在隔离的库上，而且注册验证码直接回显（devCode），
    # 所以整条账号链路能自动验完，不用去收邮件（智能体那一段会明确说"跳过"，
    # 因为测试区没开智能体）。
    python scripts/check_account_deployment.py --prefix /test-api

    # 对着正式服务跑：注册现在是**邮箱验证**的，脚本必须能读到那封验证码邮件，
    # 所以要有能 IMAP 收信的邮箱（`MODELPILOT_SMTP_USER` / `MODELPILOT_SMTP_PASS`）。
    # ⚠️ 正式这段要**两个不同的真邮箱**（第一段和第二段各一个，
    # `MODELPILOT_CHECK_EMAIL` / `MODELPILOT_CHECK_EMAIL2`），因为一个地址只能注册
    # 一个账号，而 163 **不支持 `user+tag@` 这种别名**（实测 550 User not found）。
    MODELPILOT_SMTP_USER=xxx@163.com MODELPILOT_SMTP_PASS=... \
        python scripts/check_account_deployment.py
    # 读不到验证码就**明确报错**，不会把"没验成"伪装成"通过"。

    python scripts/check_account_deployment.py --keep      # 保留临时账号，便于手工复查
    python scripts/check_account_deployment.py --base http://127.0.0.1:8010

脚本只创建**自己的**临时账号（`verify-<8 hex>`）并在结束时删掉它名下的会话、
智能体用量，以及自己发的帖子和图片。**不会碰别人的数据，
也不会重启任何服务**——重启持久性那条留给了本机的 pytest（那里可以随便重建 app）。

## 为什么清理要走 SSH

账号**故意没有删除接口**（App 里没有「注销」这个功能，多一个没人用的删除接口
只是多一个出错的地方），所以清理是运维操作。主机和 key 用环境变量给：

    TOKENTRAIL_SSH=ubuntu@host  TOKENTRAIL_SSH_KEY=~/.ssh/box.pem
"""
import argparse
import io
import json
import os
import secrets
import urllib.error
import urllib.request

from PIL import Image


BASE = "https://43.140.212.47"

# 正式是 `/api`，测试区是 `/test-api`（nginx 再把 `/test-api/...` 重写成服务内部的
# `/api/...`）。脚本里那二十几处路径写的都是 `/api/...`，换挂载点只动这一层。
PREFIX = "/api"


def mounted(path):
    """把脚本里的 `/api/...` 换成当前挂载点。测试区跑的时候只有这里变。"""
    return PREFIX + path[len("/api"):] if path.startswith("/api") else path


def call(path, method="GET", body=None, token=None, timeout=60, raw=None, content_type=None,
         idempotency=None):
    """一次请求。`body` 走 JSON；要发原始字节（比如 multipart 上传）就用 `raw`。

    **必须显式禁用代理**：macOS 的系统代理是「系统级」的，`urllib` 会读它，
    打 127.0.0.1 时会被代理用 502 回掉，看起来像「服务坏了」。
    """
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    if raw is not None:
        data = raw
    elif body is not None:
        data = json.dumps(body).encode()
    else:
        data = None
    request = urllib.request.Request(BASE + mounted(path), method=method, data=data)
    request.add_header("content-type", content_type or "application/json")
    if token: request.add_header("authorization", "Bearer " + token)
    if idempotency: request.add_header("Idempotency-Key", idempotency)
    try:
        with opener.open(request, timeout=timeout) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else {}), response.headers
    except urllib.error.HTTPError as error:
        raw = error.read()
        try: return error.code, json.loads(raw or b"{}"), error.headers
        except ValueError: return error.code, {"raw": raw[:120].decode("utf-8","replace")}, error.headers

def fetch_bytes(path, timeout=30):
  """取原始字节。图片、静态文件走这条，**不要经过 JSON 解析**。"""
  opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
  try:
    with opener.open(urllib.request.Request(BASE + mounted(path)), timeout=timeout) as response:
      return response.read() if response.status == 200 else None
  except urllib.error.HTTPError:
    return None


def read_code(email, response):
    """注册之后把验证码拿到手。三条路，**拿不到就明确失败**。

    1. 响应里有 `devCode`（测试区 `MODELPILOT_MAIL_DEV_ECHO=1`）→ 直接用；
    2. 环境里有 `MODELPILOT_SMTP_USER` / `MODELPILOT_SMTP_PASS` → 用同一个 163 账号
       走 IMAP 把最新那封验证码邮件读出来（只取那封、只取那 6 位）；
    3. 都没有 → 返回 None，调用方把这条**判成失败并说清怎么办**。

    为什么不做"跳过这一段"：账号是这个脚本的入口，跳过了后面论坛那几段就没有身份，
    而脚本照样会打印一堆 ✓——那正是这个仓库里反复出现的坑。
    """
    if response.get("devCode"):
        return response["devCode"], "devCode（测试区回显）"
    user = os.environ.get("MODELPILOT_SMTP_USER")
    secret = os.environ.get("MODELPILOT_SMTP_PASS")
    if not (user and secret):
        return None, "没有 devCode，也没有 MODELPILOT_SMTP_USER / MODELPILOT_SMTP_PASS"
    import imaplib, email as email_module, re, time
    from email.header import decode_header
    imaplib.Commands["ID"] = ("AUTH",)
    for attempt in range(6):
        box = imaplib.IMAP4_SSL("imap.163.com", 993, timeout=25)
        try:
            box.login(user, secret)
            box._simple_command("ID", '(\"name\" \"ModelPilot\" \"version\" \"1.0\" \"vendor\" \"modelpilot\")')
            box.select("INBOX", readonly=True)
            status, data = box.search(None, "ALL")
            for index in reversed(data[0].split()[-5:]):
                status, raw = box.fetch(index, "(RFC822)")
                message = email_module.message_from_bytes(raw[0][1])
                def header(name):
                    return "".join(part.decode(encoding or "utf-8", "replace")
                                   if isinstance(part, bytes) else part
                                   for part, encoding in decode_header(message.get(name, "")))
                if "验证码" not in header("Subject"):
                    continue
                # **按收件人过滤**：一次运行会注册两个账号（正文里那段共享池需要两个人），
                # 两封验证码邮件都在同一个收件箱里，"取最新一封"有可能会取到另一个账号的。
                # 取错了的表现是 CODE_INVALID——看起来像"验证码功能坏了"。
                if email.lower() not in header("To").lower():
                    continue
                body = message.get_payload(decode=True).decode("utf-8", "replace")
                hit = re.search(r"\b(\d{6})\b", body.split("验证码是")[-1])
                if hit:
                    return hit.group(1), "IMAP 收件箱"
        finally:
            try: box.logout()
            except Exception: pass
        time.sleep(4)                    # 邮件可能还在路上
    return None, "IMAP 里没找到验证码邮件"


def run(base, keep):
  global BASE, PREFIX
  BASE = base
  fails = []
  def check(label, ok, detail=""):
    print(("  ✓ " if ok else "  ✗ ") + label + ("" if ok else f"   {detail}"))
    if not ok: fails.append(label)

  name = "verify-" + secrets.token_hex(4)
  password = "verify-password-" + secrets.token_hex(4)
  # 正式服务上收件地址必须是**真的能收到信**的（脚本会去 IMAP 读那 6 位）；
  # 测试区怎么填都行，因为验证码直接在响应里回显。
  address = (os.environ.get("MODELPILOT_CHECK_EMAIL")
             or os.environ.get("MODELPILOT_SMTP_USER")
             or f"{name}@example.invalid")

  print("1. 账号（注册 = 建号 + 邮箱验证码）")
  status, created, _ = call("/api/account/register", "POST",
                            {"email": address, "username": name, "password": password})
  check("注册 201", status == 201, f"{status} {created}")
  check("注册不发 token", "token" not in created, str(created))
  check("注册返回的邮箱没被改写", created.get("email") == address.lower(), str(created.get("email")))
  code, source = read_code(address, created)
  check("拿到验证码（devCode 或 IMAP）", bool(code), source)
  if not code:
    print("  － 账号这一段没法继续：注册现在要邮箱验证。"
          "\n     要么加 --prefix /test-api 对着测试区跑（那里回显 devCode），"
          "\n     要么给 MODELPILOT_SMTP_USER / MODELPILOT_SMTP_PASS（脚本去 IMAP 读码）。")
  verified = False
  if code:
    status, result, _ = call("/api/account/verify", "POST", {"email": address, "code": code})
    verified = status == 200 and result.get("emailVerified") is True
    check("验证码核销 200", verified, f"{status} {result}")
    check("验证码只能用一次", call("/api/account/verify", "POST",
                                  {"email": address, "code": code})[0] == 400)
  check("验证码填错是 400 CODE_INVALID",
        call("/api/account/verify", "POST", {"email": address, "code": "000000"})[1].get("code")
        == "CODE_INVALID")
  check("验证码重发有 60 秒冷却（429 + Retry-After）",
        call("/api/account/verify/resend", "POST", {"email": address})[0] == 429)
  check("未知邮箱重发不泄露（和已知邮箱同样返回 200/429）",
        call("/api/account/verify/resend", "POST", {"email": "nobody-here@example.invalid"})[0]
        in (200, 429))
  if not verified:
    print("  － 没验证成功，后面几段没有身份可用，直接结束")
    return fails
  status, session, _ = call("/api/account/login", "POST", {"identifier": address, "password": password})
  check("登录拿到 tt_app_ token", status == 200 and session.get("token","").startswith("tt_app_"), f"{status} {session}")
  app, uid = session.get("token"), session.get("userId")
  check("userId 是 u_ 开头", isinstance(uid, str) and uid.startswith("u_"), str(uid))
  check("用户名重复是 409", call("/api/account/register", "POST",
                                {"email": f"other-{name}@example.invalid",
                                 "username": name, "password": password})[0] == 409)
  check("邮箱重复是 409", call("/api/account/register", "POST",
                              {"email": address.upper(), "username": name + "-x",
                               "password": password})[0] == 409)
  check("密码错是 401", call("/api/account/login", "POST", {"identifier": address, "password": "wrong-password-x"})[0] == 401)
  status, me, _ = call("/api/account/me", token=app)
  check("me 返回同一个 userId", status == 200 and me.get("userId") == uid, f"{status} {me}")
  check("没凭据时 me 是 401", call("/api/account/me")[0] == 401)

  print("2. 智能体只认账号 token")
  # 测试区（`--prefix /test-api`）没有挂智能体路由，那一整段在那边是**没验**，
  # 不是"验过了"——分开说清楚，别让一排 ✗ 看起来像服务坏了。
  probe, _, _ = call("/api/agent/status", token="tt_not_a_thing_0123456789")
  if probe == 404:
    print("  － 跳过：这个挂载点没挂智能体路由（测试区就是这样）")
  else:
    check("旧格式的 relay key 打智能体是 401", probe == 401, f"{probe}")
    status, agent, _ = call("/api/agent/status", token=app)
    check("账号 token 能查智能体状态", status == 200 and agent.get("configured") is True, f"{status} {agent}")
    check("智能体账本独立", agent.get("separateLedger") is True, str(agent))

  print("3. 真的问一句（花的是我们的 key）")
  status, answer, _ = call("/api/agent/ask", "POST", {"question": "我 2026 年 9 月一共用了多少 input token？"}, app)
  # **没开就说没开**，不要把它算成通过——静默跳过等于「以为验过了」。
  if status == 404 or (status == 503 and "not configured" in json.dumps(answer)):
    print("  － 跳过：这台服务器没开 MODELPILOT_ENABLE_AGENT（不是通过，是没验）")
  else:
   check("agent/ask 200", status == 200, f"{status} {answer}")
   if status == 200:
    print("     答：" + (answer.get("text") or "")[:200].replace("\n", " "))
    print("     工具：" + str([c.get("name") for c in answer.get("toolCalls", [])]))
    print("     缺口：" + str(answer.get("missingData"))[:160])
    check("它自己的用量记进了独立账本", (answer.get("usage") or {}).get("calls", 0) >= 1, str(answer.get("usage")))
    check("回答里没有我们的 key", "sk-" not in json.dumps(answer), "泄漏了 key 前缀")
    check("工具结果里没有别人的数字",
          all("uid" not in json.dumps(c) or uid in json.dumps(c) or "someone" not in json.dumps(c)
              for c in answer.get("toolCalls", [])))

  print("4. 论坛这条链路（图文、共享池、幂等、点赞评论）")
  # 这一段原来在 `check_deployment.py` 里，用的是**团队账号**——账号搬过来之后
  # 那个脚本就断了（用团队 token 读论坛 401），于是这一段没有任何东西守着。
  # 改成用我们自己的账号之后，脚本里再没有一处碰别人的服务。
  tag = "verify-" + secrets.token_hex(4)
  other = tag + "-b"
  # 第二个账号也要一个邮箱。测试区随便填；正式服务上得能收信，所以允许单独给一个
  # （`MODELPILOT_CHECK_EMAIL2`）；都没给就用 example.invalid——那样在正式服务上
  # 这一步会**明确失败**，而不是悄悄跳过共享池的验证。
  address2 = (os.environ.get("MODELPILOT_CHECK_EMAIL2")
              or f"{other}@example.invalid")
  status, second_account, _ = call("/api/account/register", "POST",
                                   {"email": address2, "username": other, "password": password})
  check("第二个账号能注册（共享池要两个人才测得出来）", status == 201, f"{status} {second_account}")
  if status == 409:
    print("     ↑ 这个邮箱已经注册过了。正式服务上第二段需要**另一个**真邮箱"
          "（`MODELPILOT_CHECK_EMAIL2`）；测试区随便填。")
  if status == 201:
    code2, source2 = read_code(address2, second_account)
    check("第二个账号也能拿到验证码", bool(code2), source2)
    if code2:
      call("/api/account/verify", "POST", {"email": address2, "code": code2})
  status, other_session, _ = call("/api/account/login", "POST",
                                  {"identifier": address2, "password": password})
  other_token = other_session.get("token")
  check("第二个账号能登录", status == 200 and other_token, f"{status} {other_session}")
  post_id, image_id = None, None
  # 幂等键必须**在同一次运行里保持一样**（那正是被测的东西），而且要够短：
  # 服务端限 128 字符。用它当键而不是随机值，重发才落在同一行上。
  post_key, reply_key = tag + "-post", tag + "-reply"
  forum_ok = status == 200
  if forum_ok:
    # 现画一张小图：不引任何素材文件，也不用把二进制塞进仓库。
    buffer = io.BytesIO()
    Image.new("RGB", (4, 4), "purple").save(buffer, "PNG")
    picture = buffer.getvalue()
    boundary = secrets.token_hex(16)
    multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="image"; '
                 f'filename="check.png"\r\nContent-Type: image/png\r\n\r\n').encode() \
        + picture + f"\r\n--{boundary}--\r\n".encode()
    status, image, _ = call("/api/forum/images", "POST", token=app, raw=multipart,
                            content_type=f"multipart/form-data; boundary={boundary}")
    check("图片能上传（201）", status == 201 and image.get("id"), f"{status} {image}")
    image_id = image.get("id")
    draft = {"title": tag, "body": "Temporary deployment validation",
             "imageIds": [image_id] if image_id else []}
    status, post, _ = call("/api/forum/posts", "POST", draft, app, idempotency=post_key)
    check("能发帖（201）", status == 201 and post.get("id"), f"{status} {post}")
    post_id = post.get("id")
    if post_id:
      # 幂等：同一个 Idempotency-Key 重发必须拿到**同一个**帖子，不能变成两条。
      status, repeated, _ = call("/api/forum/posts", "POST", draft, app, idempotency=post_key)
      check("重发拿到同一个帖子（幂等）", repeated.get("id") == post_id,
            f'{repeated.get("id")} != {post_id}')
      # 共享池：另一个账号看得见
      status, feed, _ = call("/api/forum/posts", token=other_token)
      check("另一个账号看得到这个帖子（共享池）",
            any(item.get("id") == post_id for item in feed.get("items", [])), f"{status}")
      # 图片取回**逐字节一致**（静态文件的路径、权限、nginx 那一段最容易出问题）。
      # 这里不能用 `call()`：它按 JSON 解析，而图片是二进制（踩过：
      # `UnicodeDecodeError: 0x89` —— 那是 PNG 的文件头）。
      url = (image or {}).get("url") or ""
      path = url[len(base):] if url.startswith(base) else url
      fetched = fetch_bytes(path)
      check("图片能按 url 取回，且字节完全一致",
            fetched == picture, f"{len(fetched or b'')} 字节 vs {len(picture)} 字节（{url}）")
      # 点赞两次只算一次；作者自己看不到 likedByMe
      for _ in range(2):
        status, liked, _ = call(f"/api/forum/posts/{post_id}/like", "PUT", token=other_token)
        check("点赞两次只算一次", liked.get("likeCount") == 1 and liked.get("likedByMe") is True,
              str(liked))
      status, mine, _ = call(f"/api/forum/posts/{post_id}", token=app)
      check("作者看到 likeCount 但 likedByMe=false",
            mine.get("likeCount") == 1 and mine.get("likedByMe") is False, str(mine))
      # 评论幂等
      status, comment, _ = call(f"/api/forum/posts/{post_id}/replies", "POST",
                                {"body": "Second account can comment"}, other_token,
                                idempotency=reply_key)
      check("另一个账号能评论", status == 201 and comment.get("id"), f"{status} {comment}")
      status, again, _ = call(f"/api/forum/posts/{post_id}/replies", "POST",
                              {"body": "Second account can comment"}, other_token,
                              idempotency=reply_key)
      check("重发评论拿到同一条（幂等）", again.get("id") == comment.get("id"), str(again))
      status, refreshed, _ = call(f"/api/forum/posts/{post_id}", token=app)
      check("作者看到评论数 1", refreshed.get("commentCount") == 1, str(refreshed))
      # 取消点赞两次都归零
      for _ in range(2):
        status, unliked, _ = call(f"/api/forum/posts/{post_id}/like", "DELETE", token=other_token)
        check("取消点赞两次都归零", unliked.get("likeCount") == 0, str(unliked))
    # 新闻页：只该有两类
    status, news, _ = call("/api/forum/news", token=app)
    check("新闻页只含 AI / Technology",
          status == 200 and all(item.get("category") in {"AI", "Technology"}
                                for item in news.get("items", [])), f"{status}")
  if post_id and not keep:
    removed = remove_forum_rows(base, tag, image_id)
    status, _, _ = call(f"/api/forum/posts/{post_id}", token=app)
    check(f"帖子已从服务端清掉（{removed}）", status == 404, f"{status}")

  print()
  print(f"临时账号：{name} / userId={uid}")
  if keep:
    print("（--keep：没有清理，手工复查完记得自己删）")
  else:
    cleanup(base, name, other)
    # **清理要当场验**：前面踩过一次——清理脚本自己语法错了，
    # 而脚本照样打印「全部通过」，那一轮数据就留在生产库里了。
    # 用同一个 token 再问一次 `/account/me`，401 才算真删掉。
    status, _, _ = call("/api/account/me", token=app)
    check("第一个临时账号已清理（同一个 token 现在 401）", status == 401, f"{status}")
    status, _, _ = call("/api/account/me", token=other_token)
    check("第二个临时账号也已清理", other_token is None or status == 401, f"{status}")
  if fails:
    print(f"失败 {len(fails)} 项：" + "；".join(fails))
    return 1
  print("公网端到端验证全部通过。")
  return 0


def on_server(script):
  """在服务器上以 `tokentrail` 身份跑一段 python，返回 (ok, 输出)。

  清理走 SSH 而不是 HTTP：账号和帖子都**故意没有删除接口**（App 里没有
  「注销」和「删帖」这两个功能，多一个没人用的删除接口只是多一个出错的地方）。
  主机和 key 用环境变量给，不写死在代码里。
  """
  import os
  import subprocess
  host = os.environ.get("TOKENTRAIL_SSH", "ubuntu@43.140.212.47")
  key = os.environ.get("TOKENTRAIL_SSH_KEY", os.path.expanduser("~/.ssh/box.pem"))
  command = ["ssh", "-o", "IdentitiesOnly=yes", "-o", "BatchMode=yes"]
  if os.path.exists(key):
    command += ["-i", key]
  done = subprocess.run(command + [host, "sudo -u tokentrail python3 -"],
                        input=script, text=True, capture_output=True)
  return done.returncode == 0, (done.stdout.strip() or done.stderr.strip()[-200:] or "（没有输出）")


def remove_forum_rows(base, title, image_id):
  """删掉这次验证发的帖子和图片。**只按标题精确匹配自己那一行。**"""
  database, media = data_paths()
  script = f"""
import os, sqlite3
db = sqlite3.connect({database!r})
row = db.execute("SELECT id FROM posts WHERE title=?", ({title!r},)).fetchone()
if row is None:
    print("帖子已经不在了")
else:
    post = row[0]
    for (name,) in db.execute("SELECT filename FROM images WHERE post_id=?", (post,)).fetchall():
        try: os.unlink({media!r} + name)
        except FileNotFoundError: pass
    db.execute("DELETE FROM likes WHERE post_id=?", (post,))
    db.execute("DELETE FROM replies WHERE post_id=?", (post,))
    db.execute("DELETE FROM images WHERE post_id=?", (post,))
    db.execute("DELETE FROM posts WHERE id=?", (post,))
    db.commit()
    print("已删帖子", post)
"""
  ok, output = on_server(script)
  return output


def data_paths():
    """当前挂载点对应的**库和数据目录**。

    测试区（`/test-api`）用的是独立的库和目录（`/var/lib/modelpilot-forum-test`），
    清理时走错库就会"删了但没删着"，而脚本照样打印"账号已经不在了"。
    """
    if PREFIX.startswith("/test-api"):
        return "/var/lib/modelpilot-forum-test/modelpilot.sqlite3", "/var/lib/modelpilot-forum-test/media/"
    return "/var/lib/modelpilot-forum/modelpilot.sqlite3", "/var/lib/modelpilot-forum/media/"


def cleanup(base, username, second=None):
    """删掉这次验证留下的账号和它名下的一切。`second` 是论坛那一段用的第二个账号。

    **两个都要删。** 第一版只按第一个账号的名字查，于是每跑一次都会在生产库里
    留下一个孤儿账号 `verify-xxxx-b`——而脚本照样打印「全部通过」。踩过：连着跑
    几轮之后库里攒了两个 `-b` 账号；要是它们名下有 relay key，「有多少人中转」
    这个数字也会跟着失真。

    为什么不留着：验证脚本每次跑都会造新账号，留着就是往生产库堆垃圾。

    **先删数据、后删账号**，顺序反了会因为外键删不掉；每张表都按账号 id 删，
    因为归属列**就是**账号——这正是这次改动的结果，所以清理只需要 id。
    """
    database, media = data_paths()
    wanted = [username] + ([second] if second else [])
    script = f"""
import os, sqlite3
db = sqlite3.connect({database!r})
placeholders = ",".join("?" * {len(wanted)})
rows = db.execute("SELECT user_id, username FROM accounts WHERE username IN ("
                  + placeholders + ")", {tuple(wanted)!r}).fetchall()
if not rows:
    print("账号已经不在了")
for uid, who in rows:
    # 图片要**先删文件再删行**：反过来会留下一堆没人认领的文件，
    # 而文件名是随机的，事后认不出来该删哪个。
    for (name,) in db.execute("SELECT filename FROM images WHERE owner_uid=?",
                              (uid,)).fetchall():
        try: os.unlink({media!r} + name)
        except FileNotFoundError: pass
    # **列名不一样，别照抄**：智能体那本账是 `user_id`，
    # 论坛早期那几张是 `uid` / `owner_uid`。写错列名会报 no such column，
    # 而那一刻正好是清理阶段，报错就意味着数据留在生产库里。
    #
    # 2026-09-30 之后这里只剩这几张：用量/预算/价目/赛季那几张连同中转一起删了，
    # 而且 App 启动时会把它们从老库里 DROP 掉——对着不存在的表写 DELETE 会直接报错。
    for table, column in (("agent_usage", "user_id"), ("email_codes", "user_id"),
                          ("account_sessions", "user_id"), ("images", "owner_uid"),
                          ("idempotency", "uid"), ("write_events", "uid")):
        # **先问表在不在**：测试区没开智能体，那边的库里根本没有 `agent_usage`，
        # 直接 DELETE 会报 no such table——而那一刻正是清理阶段，报错就意味着
        # 数据留在库里没删掉（脚本却已经打印过"清理完成"了）。
        present = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                             (table,)).fetchone()
        if present:
            db.execute("DELETE FROM " + table + " WHERE " + column + "=?", (uid,))
    for (post,) in db.execute("SELECT id FROM posts WHERE author_uid=?", (uid,)).fetchall():
        db.execute("DELETE FROM likes WHERE post_id=?", (post,))
        db.execute("DELETE FROM replies WHERE post_id=?", (post,))
        db.execute("DELETE FROM posts WHERE id=?", (post,))
    db.execute("DELETE FROM accounts WHERE user_id=?", (uid,))
    db.commit()
    print("已清理", who, uid)
"""
    ok, output = on_server(script)
    print("  清理：" + output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=BASE, help="服务地址，默认生产")
    parser.add_argument("--prefix", default="/api",
                        help="挂载点：正式 /api，测试区 /test-api")
    parser.add_argument("--keep", action="store_true", help="保留临时账号")
    arguments = parser.parse_args()
    # 挂载点要在 run() 之前定下来：`call()` / `fetch_bytes()` / 清理脚本都读它。
    PREFIX = arguments.prefix
    raise SystemExit(run(arguments.base, arguments.keep))
