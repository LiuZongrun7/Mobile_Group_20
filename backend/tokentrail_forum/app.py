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
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, ConfigDict, Field

from .auth import Identity, TeamAuth
from .test_sessions import ForumAuth
from .store import Store, decode_cursor, now_ms, page, rate_limit, reply_json, news_json


@dataclass(frozen=True)
class Settings:
    data_dir: str
    public_origin: str
    auth_url: str = ""
    auth_uid_field: str = "id"
    auth_name_field: str = "username"
    test_sessions_enabled: bool = False
    public_api_prefix: str = "/api"
    news_database: str = ""

    @classmethod
    def from_environment(cls):
        return cls(os.getenv("FORUM_DATA_DIR", "./runtime"),
                   os.getenv("FORUM_PUBLIC_ORIGIN", ""),
                   os.getenv("FORUM_AUTH_URL", ""),
                   os.getenv("FORUM_AUTH_UID_FIELD", "id"),
                   os.getenv("FORUM_AUTH_NAME_FIELD", "username"),
                   os.getenv("FORUM_ENABLE_TEST_SESSIONS", "0") == "1",
                   os.getenv("FORUM_PUBLIC_API_PREFIX", "/api"),
                   os.getenv("FORUM_NEWS_DATABASE", ""))


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


def create_app(settings=None, verifier=None):
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
    auth = verifier or TeamAuth(settings.auth_url, settings.auth_uid_field, settings.auth_name_field)
    forum_auth = ForumAuth(store, auth, settings.test_sessions_enabled)

    @asynccontextmanager
    async def lifespan(app):
        yield
        auth.close()

    app = FastAPI(title="TokenTrail Forum", version="1.0.0", lifespan=lifespan, docs_url=None, redoc_url=None)
    app.add_middleware(BoundedBody)
    app.state.store = store

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

    @app.get("/health")
    def health():
        with store.news_connect(settings.news_database) as db:
            db.execute("SELECT 1")
            metadata = dict(db.execute("SELECT key,value FROM metadata"))
            count = db.execute("SELECT COUNT(*) FROM news").fetchone()[0]
        return {"status": "ok", "service": "tokentrail-forum", "newsCount": count,
                "newsLastCollectedAt": metadata.get("newsLastCollectedAt"),
                "newsSourceErrors": json.loads(metadata.get("newsSourceErrors", "{}")),
                "authConfigured": bool(settings.auth_url), "testSessionsEnabled": settings.test_sessions_enabled}

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
    def official(user: User, limit: Limit = 20):
        return []  # No fabricated official accounts or RSS articles as official posts.

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
        since_timestamp(since)
        return {"posts": [] if modelFilter else ranked(user, limit, since), "since": since,
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
            return store.post(db, post_id, user.uid, origin)

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
