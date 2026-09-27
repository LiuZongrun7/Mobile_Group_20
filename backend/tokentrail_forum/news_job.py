"""Hourly RSS -> SQLite import. Runs once under a systemd timer (no overlaps)."""
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import tempfile
from contextlib import closing

from .store import Store, now_ms


def import_articles(store, document):
    cutoff = now_ms() - 30 * 86_400_000
    with store.connect(write=True) as db:
        for article in document["items"]:
            db.execute("""INSERT INTO news VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET title=excluded.title, summary=excluded.summary,
                source_name=excluded.source_name,original_url=excluded.original_url,
                image_url=excluded.image_url,category=excluded.category,published=excluded.published""",
                (article["id"], article["title"], article.get("summary"), article["sourceName"],
                 article["originalUrl"], article.get("imageUrl"), article["category"], article["publishedAtEpochMillis"]))
        db.execute("DELETE FROM news WHERE published<?", (cutoff,))
        for key, value in {"newsLastCollectedAt": str(document["collectedAtEpochMillis"]),
                           "newsSourceErrors": json.dumps(document["sourceErrors"])}.items():
            db.execute("INSERT INTO metadata VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))


def cleanup_unused_images(store):
    with store.connect(write=True) as db:
        rows = db.execute("SELECT id,filename FROM images WHERE post_id IS NULL AND created<?",
                          (now_ms() - 7 * 86_400_000,)).fetchall()
        for row in rows:
            (store.media / row["filename"]).unlink(missing_ok=True)
            db.execute("DELETE FROM images WHERE id=?", (row["id"],))
        registered = {row[0] for row in db.execute("SELECT filename FROM images")}
        for path in store.media.iterdir():
            if path.is_file() and path.name not in registered and path.stat().st_mtime * 1000 < now_ms() - 86_400_000:
                path.unlink()


def export_news(store):
    """Atomically publish a read-only news snapshot with no forum/account tables.

    A separate test service can read this without writing the production WAL's
    shared-memory files or gaining access to production post/session records.
    """
    descriptor, temporary = tempfile.mkstemp(prefix="news-readonly-", suffix=".sqlite3", dir=store.directory)
    os.close(descriptor)
    try:
        with store.connect() as source, closing(sqlite3.connect(temporary)) as target:
            target.executescript("""
                CREATE TABLE news (id TEXT PRIMARY KEY,title TEXT,summary TEXT,source_name TEXT,
                    original_url TEXT,image_url TEXT,category TEXT,published INTEGER);
                CREATE INDEX news_order ON news(published DESC,id DESC);
                CREATE TABLE metadata (key TEXT PRIMARY KEY,value TEXT);
            """)
            target.executemany("INSERT INTO news VALUES(?,?,?,?,?,?,?,?)", [tuple(row) for row in source.execute("SELECT * FROM news")])
            target.executemany("INSERT INTO metadata VALUES(?,?)", [tuple(row) for row in source.execute("SELECT * FROM metadata WHERE key LIKE 'news%'")])
            target.commit()
        os.replace(temporary, store.directory / "news-readonly.sqlite3")
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    source_dir = Path(__file__).resolve().parents[2] / "tools" / "news"
    spec = importlib.util.spec_from_file_location("rss_collector", source_dir / "rss_collector.py")
    collector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(collector)
    store = Store(os.environ["FORUM_DATA_DIR"])
    staging = store.directory / "news-staging.json"
    sources = json.loads((source_dir / "sources.json").read_text())["sources"]
    previous = json.loads(staging.read_text()).get("items", []) if staging.exists() else []
    document = collector.collect(sources, previous)
    collector.atomic_write(staging, document)
    import_articles(store, document)
    export_news(store)
    cleanup_unused_images(store)
    print(json.dumps({"articles": len(document["items"]), "sourceErrors": document["sourceErrors"]}))
    return 1 if len(document["sourceErrors"]) == len(sources) else 0


if __name__ == "__main__":
    raise SystemExit(main())
