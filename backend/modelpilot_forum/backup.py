"""Daily consistent SQLite snapshot and its media, retaining seven local backups."""
from datetime import datetime, timezone
import os
from pathlib import Path
import sqlite3
import tarfile
import tempfile

from .store import Store


def main():
    store = Store(os.environ["MODELPILOT_DATA_DIR"])
    directory = store.directory / "backups"
    directory.mkdir(mode=0o700, exist_ok=True)
    name = datetime.now(timezone.utc).strftime("forum-%Y%m%d-%H%M%S.tar.gz")
    target = directory / name
    partial = target.with_suffix(".partial")
    with tempfile.TemporaryDirectory(dir=directory) as temporary:
        snapshot = Path(temporary) / "modelpilot.sqlite3"
        # Snapshot first; referenced images cannot be removed by unused-image cleanup.
        with store.connect() as db, sqlite3.connect(snapshot) as copied:
            db.backup(copied)
        with sqlite3.connect(snapshot) as db:
            images = [row[0] for row in db.execute("SELECT filename FROM images WHERE post_id IS NOT NULL")]
        with tarfile.open(partial, "w:gz") as archive:
            archive.add(snapshot, arcname="modelpilot.sqlite3")
            for filename in images:
                archive.add(store.media / filename, arcname=f"media/{filename}")
        os.replace(partial, target)
    for old in sorted(directory.glob("forum-*.tar.gz"), reverse=True)[7:]:
        old.unlink()
    print(f"Saved {target.name}")


if __name__ == "__main__":
    main()
