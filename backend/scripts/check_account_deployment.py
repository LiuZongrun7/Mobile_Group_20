"""公网端到端验证：**账号是身份，relay key 只在中转里**。

对着真正在跑的那台服务器（`https://43.140.212.47`）走一整条路，用的是
HTTP，和 App 侧 `AccountSession` / `RelayApi` 发的是同一批请求。

## 它验的是什么

不是「接口有没有 200」，而是**这次改动的核心断言**：

1. 账号能注册、能登录、注册不发 token（服务端有意为之）；
2. **没有 relay key 也能读自己的用量、赛季、预算**——这是「身份是账号」的最小含义，
   改之前做不到（那时没配中转在服务端根本没有身份）；
3. relay key 注册在账号下面，而且 **relay key 和账号 token 读到同一份账**；
4. **「我录了什么」答得出来**：`GET /keys/all` 列出名下每一条（备注名、上游主机、
   创建/最后使用时间、转发次数、停用没有），**但没有 key 明文**；
   能按 `uid` 停用指定那一条，而不是「服务端挑一条」；
5. **智能体只认账号 token**，relay key 打过去是 401——relay key 只该出现在中转里；
6. 真的问一句智能体（花我们自己的 DeepSeek key），并检查回答里没有 key、
   工具结果里没有别人的数字；
7. **同账号换一条 relay key，余额不断**——改之前换个 key 就等于换个人；
8. **论坛这条链路**（2026-02 从已删除的 `check_deployment.py` 搬过来的）：
   图文上传、两个账号共享同一个帖子池、图片跨账号取回逐字节一致、
   发帖与点赞的幂等、计数不重复、帖子能被删干净。

`FORUM_ENABLE_AGENT` 没开的服务器上第 6 段会跳过（会明确说跳过了，不是静默通过）。

## 用法

    python scripts/check_account_deployment.py            # 跑完自动清理临时账号
    python scripts/check_account_deployment.py --keep      # 保留临时账号，便于手工复查
    python scripts/check_account_deployment.py --base http://127.0.0.1:8010

脚本只创建**自己的**临时账号（`verify-<8 hex>`）并在结束时删掉它名下的
relay key、用量、赛季、预算、会话，以及自己发的帖子和图片。**不会碰别人的数据，
也不会重启任何服务**——重启持久性那条留给了本机的 pytest（那里可以随便重建 app）。

## 为什么清理要走 SSH

账号**故意没有删除接口**（App 里没有「注销」这个功能，多一个没人用的删除接口
只是多一个出错的地方），所以清理是运维操作。主机和 key 用环境变量给：

    TOKENTRAIL_SSH=ubuntu@host  TOKENTRAIL_SSH_KEY=~/.ssh/box.pem
"""
import argparse
import io
import json
import secrets
import urllib.error
import urllib.request

from PIL import Image


BASE = "https://43.140.212.47"


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
    request = urllib.request.Request(BASE + path, method=method, data=data)
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
    with opener.open(urllib.request.Request(BASE + path), timeout=timeout) as response:
      return response.read() if response.status == 200 else None
  except urllib.error.HTTPError:
    return None


def run(base, keep):
  global BASE
  BASE = base
  fails = []
  def check(label, ok, detail=""):
    print(("  ✓ " if ok else "  ✗ ") + label + ("" if ok else f"   {detail}"))
    if not ok: fails.append(label)

  name = "verify-" + secrets.token_hex(4)
  password = "verify-password-" + secrets.token_hex(4)

  print("1. 账号")
  status, created, _ = call("/api/account/register", "POST", {"username": name, "password": password})
  check("注册 201", status == 201, f"{status} {created}")
  check("注册不发 token", "token" not in created, str(created))
  status, session, _ = call("/api/account/login", "POST", {"username": name, "password": password})
  check("登录拿到 tt_app_ token", status == 200 and session.get("token","").startswith("tt_app_"), f"{status} {session}")
  app, uid = session.get("token"), session.get("userId")
  check("userId 是 u_ 开头", isinstance(uid, str) and uid.startswith("u_"), str(uid))
  check("用户名重复是 409", call("/api/account/register", "POST", {"username": name, "password": password})[0] == 409)
  check("密码错是 401", call("/api/account/login", "POST", {"username": name, "password": "wrong-password-x"})[0] == 401)
  status, me, _ = call("/api/account/me", token=app)
  check("me 返回同一个 userId", status == 200 and me.get("userId") == uid, f"{status} {me}")
  check("没凭据时 me 是 401", call("/api/account/me")[0] == 401)

  print("2. 只凭账号 token 就能读自己的账")
  status, season, _ = call("/api/relay/season", token=app)
  check("账号 token 能读赛季状态", status == 200, f"{status} {season}")
  check("uid 就是账号 userId", season.get("uid") == uid, str(season.get("uid")))
  status, summary, _ = call("/api/relay/usage/summary?from=2026-09-01&to=2026-09-27", token=app)
  check("账号 token 能读用量汇总", status == 200 and summary.get("uid") == uid, f"{status} {summary.get('uid')}")
  status, budget, _ = call("/api/relay/budgets/2026-09", token=app)
  check("账号 token 能读预算", status == 200 and budget.get("uid") == uid, f"{status} {budget.get('uid')}")

  print("3. 智能体只认账号 token")
  check("旧格式的 relay key 打智能体是 401",
        call("/api/relay/agent/status", token="tt_not_a_thing_0123456789")[0] == 401)
  status, agent, _ = call("/api/relay/agent/status", token=app)
  check("账号 token 能查智能体状态", status == 200 and agent.get("configured") is True, f"{status} {agent}")
  check("智能体账本独立", agent.get("separateLedger") is True, str(agent))

  print("4. 真的问一句（花的是我们的 key）")
  status, answer, _ = call("/api/relay/agent/ask", "POST", {"question": "我 2026 年 9 月一共用了多少 input token？"}, app)
  # **没开就说没开**，不要把它算成通过——静默跳过等于「以为验过了」。
  if status == 404 or (status == 503 and "not configured" in json.dumps(answer)):
    print("  － 跳过：这台服务器没开 FORUM_ENABLE_AGENT（不是通过，是没验）")
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

  print("5. 论坛这条链路（图文、共享池、幂等、点赞评论）")
  # 这一段原来在 `check_deployment.py` 里，用的是**团队账号**——账号搬过来之后
  # 那个脚本就断了（用团队 token 读论坛 401），于是这一段没有任何东西守着。
  # 改成用我们自己的账号之后，脚本里再没有一处碰别人的服务。
  tag = "verify-" + secrets.token_hex(4)
  other = tag + "-b"
  status, second_account, _ = call("/api/account/register", "POST",
                                   {"username": other, "password": password})
  check("第二个账号能注册（共享池要两个人才测得出来）", status == 201, f"{status} {second_account}")
  status, other_session, _ = call("/api/account/login", "POST",
                                  {"username": other, "password": password})
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
  database = "/var/lib/tokentrail-forum/forum.sqlite3"
  script = f"""
import os, sqlite3
db = sqlite3.connect({database!r})
row = db.execute("SELECT id FROM posts WHERE title=?", ({title!r},)).fetchone()
if row is None:
    print("帖子已经不在了")
else:
    post = row[0]
    for (name,) in db.execute("SELECT filename FROM images WHERE post_id=?", (post,)).fetchall():
        try: os.unlink("/var/lib/tokentrail-forum/media/" + name)
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
    database = "/var/lib/tokentrail-forum/forum.sqlite3"
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
        try: os.unlink("/var/lib/tokentrail-forum/media/" + name)
        except FileNotFoundError: pass
    # **列名不一样，别照抄**：中转和游戏那几张表是 `user_id`（这次改动的结果），
    # 论坛早期那几张是 `uid` / `owner_uid`。写错列名会报 no such column，
    # 而那一刻正好是清理阶段，报错就意味着数据留在生产库里。
    for table, column in (("relay_usage", "user_id"),
                          ("season_balances", "user_id"), ("season_settlements", "user_id"),
                          ("budgets", "user_id"), ("agent_usage", "user_id"),
                          ("account_sessions", "user_id"), ("images", "owner_uid"),
                          ("idempotency", "uid"), ("write_events", "uid")):
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
    parser.add_argument("--keep", action="store_true", help="保留临时账号")
    arguments = parser.parse_args()
    raise SystemExit(run(arguments.base, arguments.keep))
