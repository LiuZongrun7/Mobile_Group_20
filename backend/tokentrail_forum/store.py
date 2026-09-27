import base64
from contextlib import contextmanager
import json
from pathlib import Path
import sqlite3
import time

from fastapi import HTTPException


def now_ms():
    return time.time_ns() // 1_000_000


SCHEMA = """
CREATE TABLE IF NOT EXISTS posts (
 id TEXT PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL,
 author_uid TEXT NOT NULL, author_name TEXT NOT NULL, created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS posts_order ON posts(created DESC, id DESC);
CREATE INDEX IF NOT EXISTS posts_author ON posts(author_uid, created DESC);
CREATE TABLE IF NOT EXISTS images (
 id TEXT PRIMARY KEY, owner_uid TEXT NOT NULL, filename TEXT NOT NULL,
 mime TEXT NOT NULL, created INTEGER NOT NULL,
 post_id TEXT REFERENCES posts(id), position INTEGER
);
CREATE TABLE IF NOT EXISTS likes (
 post_id TEXT NOT NULL REFERENCES posts(id), uid TEXT NOT NULL,
 PRIMARY KEY(post_id, uid)
);
CREATE INDEX IF NOT EXISTS images_post ON images(post_id, position);
CREATE TABLE IF NOT EXISTS replies (
 id TEXT PRIMARY KEY, post_id TEXT NOT NULL REFERENCES posts(id),
 body TEXT NOT NULL, author_uid TEXT NOT NULL, author_name TEXT NOT NULL,
 created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS replies_order ON replies(post_id, created, id);
CREATE TABLE IF NOT EXISTS idempotency (
 uid TEXT NOT NULL, endpoint TEXT NOT NULL, key TEXT NOT NULL,
 fingerprint TEXT NOT NULL, response TEXT NOT NULL,
 PRIMARY KEY(uid, endpoint, key)
);
CREATE TABLE IF NOT EXISTS news (
 id TEXT PRIMARY KEY, title TEXT NOT NULL, summary TEXT,
 source_name TEXT NOT NULL, original_url TEXT NOT NULL, image_url TEXT,
 category TEXT NOT NULL, published INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS news_order ON news(published DESC, id DESC);
CREATE TABLE IF NOT EXISTS write_events (
 uid TEXT NOT NULL, action TEXT NOT NULL, created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS write_events_lookup ON write_events(uid, action, created);
CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


class Store:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.media = self.directory / "media"
        self.media.mkdir(parents=True, exist_ok=True)
        self.path = self.directory / "forum.sqlite3"
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)

    @contextmanager
    def connect(self, write=False):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        try:
            if write:
                db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def image(self, row, media_base):
        return {"id": row["id"], "url": f"{media_base}/api/forum/images/{row['id']}"}

    def post(self, db, post_id, uid, media_base):
        row = db.execute("SELECT * FROM posts WHERE id=?", (post_id,)).fetchone()
        if row is None:
            raise HTTPException(404, "Post not found")
        likes = db.execute("SELECT COUNT(*) FROM likes WHERE post_id=?", (post_id,)).fetchone()[0]
        comments = db.execute("SELECT COUNT(*) FROM replies WHERE post_id=?", (post_id,)).fetchone()[0]
        liked = db.execute("SELECT 1 FROM likes WHERE post_id=? AND uid=?", (post_id, uid)).fetchone() is not None
        return {
            "id": row["id"], "title": row["title"], "body": row["body"],
            "source": "COMMUNITY", "authorUid": row["author_uid"], "authorName": row["author_name"],
            "createdAtEpochMillis": row["created"],
            "images": [self.image(image, media_base) for image in db.execute(
                "SELECT * FROM images WHERE post_id=? ORDER BY position", (post_id,))],
            "likeCount": likes, "likedByMe": liked, "commentCount": comments,
            "helpfulCount": 0, "viewCount": 0, "rankScore": likes + comments * 2,
            "modelTag": None,
        }


def reply_json(row):
    return {"id": row["id"], "postId": row["post_id"], "body": row["body"],
            "authorUid": row["author_uid"], "authorName": row["author_name"],
            "official": False, "createdAtEpochMillis": row["created"], "helpfulCount": 0}


def news_json(row):
    return {"id": row["id"], "title": row["title"], "summary": row["summary"],
            "sourceName": row["source_name"], "originalUrl": row["original_url"],
            "imageUrl": row["image_url"], "category": row["category"],
            "publishedAtEpochMillis": row["published"]}


def encode_cursor(kind, row, timestamp_field="created"):
    return base64.urlsafe_b64encode(json.dumps(
        [kind, row[timestamp_field], row["id"]], separators=(",", ":")).encode()).decode().rstrip("=")


def decode_cursor(value, kind):
    if value is None:
        return None
    try:
        if len(value) > 1024:
            raise ValueError()
        parsed = json.loads(base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True))
        cursor_kind, timestamp, identifier = parsed
        if cursor_kind != kind or type(timestamp) is not int or timestamp < 0 or not isinstance(identifier, str) or not 1 <= len(identifier) <= 128:
            raise ValueError()
        return timestamp, identifier
    except (ValueError, TypeError, UnicodeError):
        raise HTTPException(400, "Invalid cursor") from None


def page(rows, limit, kind, transform, timestamp_field="created"):
    visible = rows[:limit]
    return {"items": [transform(row) for row in visible],
            "nextCursor": encode_cursor(kind, visible[-1], timestamp_field) if len(rows) > limit else None}


def rate_limit(db, uid, action, maximum):
    current = now_ms()
    count = db.execute("SELECT COUNT(*) FROM write_events WHERE uid=? AND action=? AND created>?",
                       (uid, action, current - 60_000)).fetchone()[0]
    if count >= maximum:
        raise HTTPException(429, "Please wait before trying again", headers={"Retry-After": "60"})
    db.execute("DELETE FROM write_events WHERE created<?", (current - 86_400_000,))
    db.execute("INSERT INTO write_events VALUES(?,?,?)", (uid, action, current))
