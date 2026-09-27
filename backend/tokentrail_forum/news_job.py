"""Hourly RSS -> SQLite import. Runs once under a systemd timer (no overlaps)."""
import importlib.util
import json
import os
from pathlib import Path

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
    cleanup_unused_images(store)
    print(json.dumps({"articles": len(document["items"]), "sourceErrors": document["sourceErrors"]}))
    return 1 if len(document["sourceErrors"]) == len(sources) else 0


if __name__ == "__main__":
    raise SystemExit(main())
