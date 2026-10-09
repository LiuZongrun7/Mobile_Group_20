"""Reproducible source/license integrity check; offline, stdlib only, no execution of vendored code."""
import hashlib
import json
from pathlib import Path


def main():
    root = Path(__file__).resolve().parents[1]
    source = root / "third_party/pydantic-ai-chat"
    manifest = json.loads((source / "SOURCE.json").read_text(encoding="utf-8"))
    for name, expected in manifest["files"].items():
        data = (source / name).read_bytes()
        sha256 = hashlib.sha256(data).hexdigest()
        blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if sha256 != expected["sha256"] or blob != expected["git_blob_sha1"]:
            raise SystemExit(f"Source checksum mismatch: {name}")
        print(f"Verified upstream source: {name} ({blob})")
    if (root / "app/src/main/assets/licenses/pydantic-ai-chat.txt").read_bytes() != (source / "LICENSE").read_bytes():
        raise SystemExit("APK license does not match upstream LICENSE")
    print(f"MIT license preserved in source and APK assets. Commit: {manifest['commit']}")
    print("This checks file identity, not the percentage of code reuse or runtime correctness.")


if __name__ == "__main__":
    main()
