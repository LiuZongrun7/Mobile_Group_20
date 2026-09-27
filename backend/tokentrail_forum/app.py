from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
from typing import Annotated
from urllib.parse import urlsplit
import uuid
import warnings
import ipaddress

from fastapi import Depends, FastAPI, File, Header, HTTPException, Query, UploadFile, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
import httpx
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field

from .account_routes import build_account_router
from .accounts import Accounts
from .auth import Identity, TableAuth
from .pricing import Pricing
from .relay_routes import build_agent_router
from .relay_routes import build_router as build_relay_router
from .seed_pricing import install as install_seed
from .test_sessions import ForumAuth
from .store import (Store, decode_cursor, news_json, now_ms, official_post, official_posts,
                    page, rate_limit, reply_json)


@dataclass(frozen=True)
class Settings:
    data_dir: str
    public_origin: str
    # `auth_url` / `auth_uid_field` / `auth_name_field` 三个字段删掉了（2026-02）：
    # 那是「拿 token 去问团队那台机器的账号服务」用的，`TeamAuth` 已经不存在，
    # 环境变量 `FORUM_AUTH_*` 从此**读了也不起作用**。留着字段的话，旧的 env 文件
    # 会让健康检查报一个 `authConfigured: true`，而那个 true 什么也不代表。
    test_sessions_enabled: bool = False
    public_api_prefix: str = "/api"
    news_database: str = ""
    # 中转（见 relay.py / relay_store.py）。默认关闭，打开后 /<前缀>/relay/* 才会注册，
    # 没打开时这些路径落到 404，和以前一样。
    relay_enabled: bool = False
    # 应用内 AI 智能体。**和 relay_enabled 分开**：一开始挂在同一个开关下面，
    # 结果「只想开智能体」必须连中转一起开——而中转一开用户的 API key
    # 就落在这台机器上了。两件事的风险完全不同，不该捆在一起。
    agent_enabled: bool = False
    # 允许的上游域名白名单（逗号分隔）。为空时退化成「拒绝私网地址」这一层校验；
    # 生产环境应该填成实际允许的几家，把「用户能让我们请求任意公网地址」也收掉。
    relay_allowed_hosts: tuple = ()
    # 只在本地/测试里开：允许上游指向回环地址（假上游）。生产必须保持 False。
    relay_allow_private: bool = False
    # 指回自己会形成转发环。部署时填自己的公网域名和 127.0.0.1。
    relay_self_hosts: tuple = ("127.0.0.1", "localhost", "::1")
    relay_requests_per_minute: int = 240
    # 启动时把内置的价目种子录进去（幂等）。默认关闭：价目是**数据**不是代码，
    # 自动写入生产库会让「这批价是哪来的」变成一个没人说得清的问题。
    pricing_seed: bool = False
    # 应用内 AI 智能体。用的是**我们自己的**模型 key（不是用户的上游 key），
    # 所以它和中转是两套东西、账本也分开（见 agent.py 开头）。
    # key 只从环境变量读：不进数据库、不进日志、不进任何响应。
    agent_key: str = ""
    agent_model: str = "deepseek-chat"
    agent_endpoint: str = "https://api.deepseek.com"
    # 智能体每次问答都要花我们的钱，所以限流比转发更严。
    agent_requests_per_minute: int = 10

    @classmethod
    def from_environment(cls):
        # 注意 self_hosts 这一项**不能写 `split_hosts(...) or 默认值`**：
        # 「显式设成空串」的意思是「我确认不用防转发环」（本地验收就是这种），
        # 而 `or` 会把空串当成没设置、悄悄退回默认值，于是那个开关关不掉。
        # 只有变量**完全没定义**时才用默认值。
        self_hosts = os.getenv("FORUM_RELAY_SELF_HOSTS")
        return cls(os.getenv("FORUM_DATA_DIR", "./runtime"),
                   os.getenv("FORUM_PUBLIC_ORIGIN", ""),
                   os.getenv("FORUM_ENABLE_TEST_SESSIONS", "0") == "1",
                   os.getenv("FORUM_PUBLIC_API_PREFIX", "/api"),
                   os.getenv("FORUM_NEWS_DATABASE", ""),
                   os.getenv("FORUM_ENABLE_RELAY", "0") == "1",
                   os.getenv("FORUM_ENABLE_AGENT", "0") == "1",
                   split_hosts(os.getenv("FORUM_RELAY_ALLOWED_HOSTS", "")),
                   os.getenv("FORUM_RELAY_ALLOW_PRIVATE", "0") == "1",
                   ("127.0.0.1", "localhost", "::1") if self_hosts is None else split_hosts(self_hosts),
                   int(os.getenv("FORUM_RELAY_REQUESTS_PER_MINUTE", "240")),
                   os.getenv("FORUM_PRICING_SEED", "0") == "1",
                   os.getenv("FORUM_AGENT_KEY", ""),
                   os.getenv("FORUM_AGENT_MODEL", "deepseek-chat"),
                   os.getenv("FORUM_AGENT_ENDPOINT", "https://api.deepseek.com"),
                   int(os.getenv("FORUM_AGENT_REQUESTS_PER_MINUTE", "10")))

    @property
    def relay_prefix(self):
        """中转转发路由挂在公共前缀下面：`/api/relay` 或 `/test-api/relay`。"""
        return self.public_api_prefix.rstrip("/") + "/relay"


def split_hosts(value):
    return tuple(item.strip() for item in (value or "").split(",") if item.strip())


class PostDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")
    title: str = Field(default="", max_length=512)
    body: str = Field(default="", max_length=20_000)
    imageIds: list[str] = Field(default_factory=list, max_length=9)


class ReplyDraft(BaseModel):
    model_config = ConfigDict(extra="forbid")
    body: str = Field(min_length=1, max_length=10_000)


class BoundedBody:
    """Reject oversized bodies before multipart parsing can spool them to disk."""
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] not in {"POST", "PUT", "PATCH"}:
            return await self.app(scope, receive, send)
        maximum = 11 * 1024 * 1024 if scope["path"] == "/api/forum/images" else 128 * 1024
        size = 0
        chunks = []
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            size += len(chunk)
            if size > maximum:
                return await JSONResponse({"code": "BODY_TOO_LARGE", "message": "Request too large"}, 413)(scope, receive, send)
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        delivered = False

        async def bounded_receive():
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": b"".join(chunks), "more_body": False}
            return await receive()

        await self.app(scope, bounded_receive, send)


def identifier():
    return uuid.uuid4().hex


def idempotency(db, uid, endpoint, key, payload):
    if not key or not key.strip() or len(key) > 128:
        raise HTTPException(400, "Idempotency-Key is required (maximum 128 characters)")
    fingerprint = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    row = db.execute("SELECT * FROM idempotency WHERE uid=? AND endpoint=? AND key=?", (uid, endpoint, key)).fetchone()
    if row is not None and row["fingerprint"] != fingerprint:
        raise HTTPException(409, "Idempotency key already used for different content")
    return fingerprint, json.loads(row["response"]) if row else None


def save_result(db, uid, endpoint, key, fingerprint, result):
    db.execute("INSERT INTO idempotency VALUES(?,?,?,?,?)", (uid, endpoint, key, fingerprint, json.dumps(result)))


def since_timestamp(value):
    if value is None:
        return 0
    try:
        return int(datetime.strptime(value, "%Y-%m-%d").replace(tzinfo=timezone.utc).timestamp() * 1000)
    except ValueError:
        raise HTTPException(400, "since must be yyyy-MM-dd") from None


def _relay_client(app):
    """转发用的 HTTP 客户端。

    传入的实例由调用方负责关闭（测试里是 `httpx.MockTransport` 包出来的）；
    没传就懒建一个真实的，并且注册进 `app.state` 让 lifespan 关掉它。
    """
    if app.state.relay_client is None:
        app.state.relay_client = httpx.AsyncClient(
            timeout=httpx.Timeout(connect=10.0, read=600.0, write=60.0, pool=10.0),
            follow_redirects=False, trust_env=False)
    return app.state.relay_client


def create_app(settings=None, verifier=None, relay_client=None, day_provider=None):
    settings = settings or Settings.from_environment()
    origin = settings.public_origin.rstrip("/")
    parsed = urlsplit(origin)
    if parsed.scheme != "https" or not parsed.netloc or parsed.path or parsed.query or parsed.fragment or parsed.username:
        raise ValueError("FORUM_PUBLIC_ORIGIN must be an HTTPS origin, e.g. https://43.140.212.47")
    if settings.public_api_prefix not in {"/api", "/test-api"}:
        raise ValueError("Unsupported public API prefix")
    if settings.test_sessions_enabled and settings.public_api_prefix != "/test-api":
        raise ValueError("Anonymous test sessions require the isolated /test-api service")
    store = Store(settings.data_dir, settings.public_api_prefix)
    # 账号归我们：用自己的 `accounts` 表。**没有第二条路**——
    # `verifier` 只是测试注入点（单测里不想真注册账号时用），生产永远是 `TableAuth`。
    auth = verifier or TableAuth(store)
    forum_auth = ForumAuth(store, auth, settings.test_sessions_enabled)

    @asynccontextmanager
    async def lifespan(app):
        yield
        auth.close()
        if app.state.relay_client is not None:
            await app.state.relay_client.aclose()

    app = FastAPI(title="TokenTrail Forum", version="1.0.0", lifespan=lifespan, docs_url=None, redoc_url=None)
    app.add_middleware(BoundedBody)
    app.state.store = store
    app.state.relay_client = relay_client

    @app.exception_handler(HTTPException)
    async def http_error(request, exc):
        return JSONResponse({"code": f"HTTP_{exc.status_code}", "message": str(exc.detail)},
                            exc.status_code, headers=exc.headers)

    @app.exception_handler(RequestValidationError)
    async def validation_error(request, exc):
        return JSONResponse({"code": "INVALID_INPUT", "message": "Invalid request fields"}, 400)

    def current_user(authorization: Annotated[str | None, Header()] = None):
        return forum_auth.verify(authorization)

    User = Annotated[Identity, Depends(current_user)]
    Limit = Annotated[int, Query(ge=1, le=20)]

    # 智能体和转发是两个开关、两个 router（见 `build_agent_router` 的注释）。
    # **顺序仍然重要**：兜底转发路由 `/{path:path}` 必须最后注册——
    # `APIRouter` 按路径长度排序，它比 `/keys`、`/agent/ask` 都短，
    # 先注册的话会把那些具体路径全吃掉。
    # **账号路由永远注册**，而且排在中转那套之前——中转的兜底路由
    # `/{path:path}` 会吃掉具体路径，顺序错了登录接口就 404。
    app.include_router(build_account_router(settings, store), prefix=settings.public_api_prefix)

    if settings.agent_enabled:
        # ⚠️ **已知耦合**：身份自助注册（`/keys`）目前和中转那套路由在一起，
        # 所以只开 `agent_enabled`、不开 `relay_enabled` 的服务器**注册不出身份**。
        # 要解耦得把「身份管理/用量/赛季/预算/价目」那批路由和转发兜底拆开，
        # 但那个改动会动到 `APIRouter` 的注册顺序（兜底路由最短路优先，
        # 拆错了具体路径会被它吃掉），**在没人用这个组合的时候不值得冒险**。
        # 现在的部署两个开关都开着，所以碰不到。
        app.include_router(build_agent_router(settings, store, Pricing(store),
                                             day_provider=day_provider),
                           prefix=settings.relay_prefix)
    if settings.relay_enabled:
        if settings.pricing_seed:
            install_seed(store, Pricing(store))
        relay_router, relay_forward = build_relay_router(settings, store, _relay_client(app),
                                                         day_provider=day_provider)
        app.include_router(relay_router, prefix=settings.relay_prefix)
        app.add_api_route(settings.relay_prefix + "/{path:path}", relay_forward,
                          methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"])

    @app.get("/health")
    def health():
        with store.news_connect(settings.news_database) as db:
            db.execute("SELECT 1")
            metadata = dict(db.execute("SELECT key,value FROM metadata"))
            count = db.execute("SELECT COUNT(*) FROM news").fetchone()[0]
        return {"status": "ok", "service": "tokentrail-forum", "newsCount": count,
                "newsLastCollectedAt": metadata.get("newsLastCollectedAt"),
                "newsSourceErrors": json.loads(metadata.get("newsSourceErrors", "{}")),
                "testSessionsEnabled": settings.test_sessions_enabled,
                "relayEnabled": settings.relay_enabled,
                "relayAllowedHosts": list(settings.relay_allowed_hosts),
                # 只报「配没配」，**永远不报 key 本身**。
                "agentConfigured": bool(settings.agent_key),
                "agentModel": settings.agent_model}

    @app.post("/api/forum/test-session", status_code=201)
    def test_session(request: Request):
        address = request.client.host if request.client else "unknown"
        if address in {"127.0.0.1", "::1"} and request.headers.get("x-real-ip"):
            try:
                address = str(ipaddress.ip_address(request.headers["x-real-ip"]))
            except ValueError:
                raise HTTPException(400, "Invalid client IP") from None
        return forum_auth.issue(address)

    @app.delete("/api/forum/test-session")
    def leave_test_session(authorization: Annotated[str | None, Header()] = None):
        forum_auth.revoke(authorization)
        return {"status": "ok"}

    @app.get("/api/forum/posts")
    def posts(user: User, cursor: str | None = None, limit: Limit = 20):
        position = decode_cursor(cursor, "posts")
        where, parameters = ("", []) if position is None else ("WHERE (created,id)<(?,?)", list(position))
        with store.connect() as db:
            rows = db.execute(f"SELECT * FROM posts {where} ORDER BY created DESC,id DESC LIMIT ?", parameters + [limit + 1]).fetchall()
            return page(rows, limit, "posts", lambda row: store.post(db, row["id"], user.uid, origin))

    @app.get("/api/forum/news")
    def news(user: User, cursor: str | None = None, limit: Limit = 20):
        position = decode_cursor(cursor, "news")
        where, parameters = ("", []) if position is None else ("WHERE (published,id)<(?,?)", list(position))
        with store.news_connect(settings.news_database) as db:
            rows = db.execute(f"SELECT * FROM news {where} ORDER BY published DESC,id DESC LIMIT ?", parameters + [limit + 1]).fetchall()
            return page(rows, limit, "news", news_json, "published")

    @app.post("/api/forum/images", status_code=201)
    def upload(user: User, image: Annotated[UploadFile, File()]):
        content = image.file.read(10 * 1024 * 1024 + 1)
        if len(content) > 10 * 1024 * 1024:
            raise HTTPException(413, "Image exceeds 10 MB")
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", Image.DecompressionBombWarning)
                with Image.open(io.BytesIO(content)) as decoded:
                    fmt = decoded.format
                    if fmt not in {"JPEG", "PNG", "WEBP", "GIF"}:
                        raise ValueError()
                    decoded.verify()
                with Image.open(io.BytesIO(content)) as decoded:
                    decoded.load()
        except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning):
            raise HTTPException(400, "Upload a valid JPEG, PNG, WebP or GIF image") from None
        mime, suffix = {"JPEG": ("image/jpeg", ".jpg"), "PNG": ("image/png", ".png"),
                        "WEBP": ("image/webp", ".webp"), "GIF": ("image/gif", ".gif")}[fmt]
        image_id = identifier()
        filename = image_id + suffix
        path = store.media / filename
        try:
            with store.connect(write=True) as db:
                rate_limit(db, user.uid, "upload", 30)
                path.write_bytes(content)
                db.execute("INSERT INTO images(id,owner_uid,filename,mime,created) VALUES(?,?,?,?,?)",
                           (image_id, user.uid, filename, mime, now_ms()))
        except BaseException:
            path.unlink(missing_ok=True)
            raise
        return store.image({"id": image_id}, origin)

    @app.get("/api/forum/images/{image_id}")
    def media(image_id: str, authorization: Annotated[str | None, Header()] = None):
        with store.connect() as db:
            row = db.execute("SELECT * FROM images WHERE id=?", (image_id,)).fetchone()
        if row is None:
            raise HTTPException(404, "Image not found")
        if row["post_id"] is None:
            user = forum_auth.verify(authorization)
            if user.uid != row["owner_uid"]:
                raise HTTPException(403, "Image not available")
        path = store.media / row["filename"]
        if not path.is_file():
            raise HTTPException(404, "Image not found")
        return FileResponse(path, media_type=row["mime"], headers={"X-Content-Type-Options": "nosniff",
                            "Cache-Control": "public, max-age=3600" if row["post_id"] else "private, no-store"})

    @app.post("/api/forum/posts", status_code=201)
    def publish(draft: PostDraft, user: User, key: Annotated[str | None, Header(alias="Idempotency-Key")] = None):
        payload = draft.model_dump()
        title, body = draft.title.strip(), draft.body.strip()
        if not body and not draft.imageIds:
            raise HTTPException(400, "Add text or an image")
        if len(set(draft.imageIds)) != len(draft.imageIds):
            raise HTTPException(400, "Duplicate image IDs")
        endpoint = "posts"
        with store.connect(write=True) as db:
            fingerprint, replay = idempotency(db, user.uid, endpoint, key, payload)
            if replay is not None:
                return replay
            rate_limit(db, user.uid, "post", 5)
            for image_id in draft.imageIds:
                row = db.execute("SELECT * FROM images WHERE id=?", (image_id,)).fetchone()
                if row is None or row["owner_uid"] != user.uid or row["post_id"] is not None:
                    raise HTTPException(403, "Image does not belong to this draft")
                if not (store.media / row["filename"]).is_file():
                    raise HTTPException(404, "Uploaded image missing")
            post_id = identifier()
            db.execute("INSERT INTO posts VALUES(?,?,?,?,?,?)", (post_id, title, body, user.uid, user.name, now_ms()))
            for position, image_id in enumerate(draft.imageIds):
                db.execute("UPDATE images SET post_id=?,position=? WHERE id=?", (post_id, position, image_id))
            result = store.post(db, post_id, user.uid, origin)
            save_result(db, user.uid, endpoint, key, fingerprint, result)
            return result

    # Static compatibility paths must be registered before /posts/{post_id}.
    def ranked(user, limit, since, mine=False):
        with store.connect() as db:
            rows = db.execute("""SELECT p.id, COUNT(DISTINCT l.uid) + COUNT(DISTINCT r.id)*2 AS rank
                FROM posts p LEFT JOIN likes l ON l.post_id=p.id LEFT JOIN replies r ON r.post_id=p.id
                WHERE p.created>=? AND (? IS NULL OR p.author_uid=?)
                GROUP BY p.id ORDER BY rank DESC,p.id DESC LIMIT ?""",
                (since_timestamp(since), user.uid if mine else None, user.uid, limit)).fetchall()
            return [store.post(db, row["id"], user.uid, origin) for row in rows]

    @app.get("/api/forum/official")
    def official(user: User, limit: Limit = 20, category: str | None = None):
        """官方帖 = 我们自己发布的资讯（`news` 表），以 `ForumPost` 的形状返回。

        来源是**我们自己的采集任务**，所以这不涉及「拿别人的文章冒充官方帖」——
        那些文章本来就是为这个项目采的，`source_name` / `original_url` 也一起返回，
        界面上能看出原始出处。

        **直接被映射，不另存一份进 `posts`。** 存两份的话同一个标题在两处，
        改一次采集逻辑要改两个地方，而且迟早漂移。代价是官方帖不能被评论
        （它们在 `news` 表里，没有 `post_id` 可挂评论）——这是有意的：
        官方帖是公告，讨论去对应的社区帖。

        `source` 填 `OFFICIAL` 不是装饰：`ForumPost.Source` 的注释写明
        「官方帖可以当作价格/版本的事实来源，社区帖只能当经验分享」，
        客户端据此渲染官方徽章，agent 据此决定措辞。
        """
        bounded = max(1, min(limit, 50))
        with store.news_connect(settings.news_database) as db:
            return official_posts(db, bounded, category)

    @app.get("/api/forum/hot")
    def hot(user: User, since: str | None = None, limit: Limit = 20):
        return ranked(user, limit, since)

    @app.get("/api/forum/me/posts")
    def my_posts(user: User):
        with store.connect() as db:
            rows = db.execute("SELECT id FROM posts WHERE author_uid=? ORDER BY created DESC,id DESC LIMIT 100", (user.uid,)).fetchall()
            return [store.post(db, row["id"], user.uid, origin) for row in rows]

    @app.get("/api/forum/highlights")
    def highlights(user: User, since: str | None = None, modelFilter: str | None = None, limit: Limit = 20):
        """官方帖 + 社区热帖。给 agent 的 `getForumHighlights` 用。

        **两类都在里面，而且各自带着 `source`**——`ForumHighlights` 的注释写的是
        「官方帖 + 热帖」，而两者的可信度不同（官方能当事实来源，社区只能当经验分享），
        所以调用方必须能分清哪些是哪些。

        `modelFilter` 仍然让社区那半边返回空（原来就是这样，理由是模型标签还没做）；
        但**官方帖不受它影响**——资讯本来就带 `category`，按模型筛没有意义，
        而「筛了模型就什么官方内容都看不到」会让 agent 以为没有官方公告。
        """
        since_timestamp(since)
        with store.news_connect(settings.news_database) as db:
            official_items = official_posts(db, limit)
        community = [] if modelFilter else ranked(user, limit, since)
        return {"posts": official_items + community, "since": since,
                "modelFilter": modelFilter, "asOfEpochMillis": now_ms()}

    @app.get("/api/forum/me/threads")
    def threads(user: User, since: str | None = None, limit: Limit = 20):
        with store.connect() as db:
            rows = db.execute("SELECT id FROM posts WHERE author_uid=? AND created>=? ORDER BY created DESC,id DESC LIMIT ?",
                              (user.uid, since_timestamp(since), limit)).fetchall()
            items = []
            for row in rows:
                replies = [reply_json(reply) for reply in db.execute(
                    "SELECT * FROM replies WHERE post_id=? ORDER BY created,id", (row["id"],))]
                items.append({"post": store.post(db, row["id"], user.uid, origin),
                              "replies": replies, "hasOfficialReply": False})
            return {"threads": items, "since": since, "asOfEpochMillis": now_ms()}

    @app.get("/api/forum/posts/{post_id}")
    def post(post_id: str, user: User):
        with store.connect() as db:
            try:
                return store.post(db, post_id, user.uid, origin)
            except HTTPException as missing:
                if missing.status_code != 404:
                    raise
        # 不是社区帖 → 试官方帖。**官方帖的详情也要能打开**，
        # 否则列表里点进去是 404，用户会以为链接坏了。
        with store.news_connect(settings.news_database) as db:
            article = official_post(db, post_id)
        if article is None:
            raise HTTPException(404, "Post not found")
        return article

    def change_like(post_id, user, liked):
        with store.connect(write=True) as db:
            store.post(db, post_id, user.uid, origin)
            rate_limit(db, user.uid, "like", 120)
            if liked:
                db.execute("INSERT OR IGNORE INTO likes VALUES(?,?)", (post_id, user.uid))
            else:
                db.execute("DELETE FROM likes WHERE post_id=? AND uid=?", (post_id, user.uid))
            return store.post(db, post_id, user.uid, origin)

    @app.put("/api/forum/posts/{post_id}/like")
    def like(post_id: str, user: User):
        return change_like(post_id, user, True)

    @app.delete("/api/forum/posts/{post_id}/like")
    def unlike(post_id: str, user: User):
        return change_like(post_id, user, False)

    @app.get("/api/forum/posts/{post_id}/replies")
    def replies(post_id: str, user: User, cursor: str | None = None, limit: Limit = 20):
        position = decode_cursor(cursor, f"replies:{post_id}")
        where, parameters = ("", []) if position is None else ("AND (created,id)>(?,?)", list(position))
        with store.connect() as db:
            store.post(db, post_id, user.uid, origin)
            rows = db.execute(f"SELECT * FROM replies WHERE post_id=? {where} ORDER BY created,id LIMIT ?",
                              [post_id] + parameters + [limit + 1]).fetchall()
            return page(rows, limit, f"replies:{post_id}", reply_json)

    @app.post("/api/forum/posts/{post_id}/replies", status_code=201)
    def comment(post_id: str, draft: ReplyDraft, user: User, key: Annotated[str | None, Header(alias="Idempotency-Key")] = None):
        body = draft.body.strip()
        if not body:
            raise HTTPException(400, "Comment is empty")
        endpoint = f"replies:{post_id}"
        with store.connect(write=True) as db:
            fingerprint, replay = idempotency(db, user.uid, endpoint, key, draft.model_dump())
            if replay is not None:
                return replay
            store.post(db, post_id, user.uid, origin)
            rate_limit(db, user.uid, "comment", 30)
            reply_id = identifier()
            db.execute("INSERT INTO replies VALUES(?,?,?,?,?,?)", (reply_id, post_id, body, user.uid, user.name, now_ms()))
            result = reply_json(db.execute("SELECT * FROM replies WHERE id=?", (reply_id,)).fetchone())
            save_result(db, user.uid, endpoint, key, fingerprint, result)
            return result

    return app


def application():
    """Uvicorn --factory entry point; settings are read only at startup."""
    return create_app()
