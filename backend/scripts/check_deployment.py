"""Explicit deployment check using two temporary accounts, with fixture cleanup.

Run as root on this server after deploying HTTPS. Does not print passwords/tokens.
Uses the team's public registration/login API and deletes only its own test users.
"""
import io
import json
import os
from pathlib import Path
import secrets
import sqlite3
import subprocess
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import uuid
from PIL import Image

PUBLIC = "https://43.140.212.47"
AUTH = "http://127.0.0.1:8000"
DATA = Path("/var/lib/tokentrail-forum")
TEAM = Path("/home/ubuntu/aibox_backend")


def request(origin, path, token=None, body=None, key=None, method=None, content_type="application/json"):
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
    with urlopen(Request(origin + path, body, headers, method=method), timeout=20) as response:
        raw = response.read()
        return json.loads(raw) if "application/json" in response.headers.get("Content-Type", "") else raw


def cleanup(users):
    if not users:
        return
    uids = [str(user["id"]) for user in users if user.get("id") is not None]
    with sqlite3.connect(DATA / "forum.sqlite3") as db:
        for uid in uids:
            rows = db.execute("SELECT filename FROM images WHERE owner_uid=?", (uid,)).fetchall()
            db.execute("DELETE FROM likes WHERE uid=? OR post_id IN (SELECT id FROM posts WHERE author_uid=?)", (uid, uid))
            db.execute("DELETE FROM replies WHERE author_uid=? OR post_id IN (SELECT id FROM posts WHERE author_uid=?)", (uid, uid))
            db.execute("DELETE FROM images WHERE owner_uid=?", (uid,))
            db.execute("DELETE FROM posts WHERE author_uid=?", (uid,))
            db.execute("DELETE FROM idempotency WHERE uid=?", (uid,))
            db.execute("DELETE FROM write_events WHERE uid=?", (uid,))
            for (filename,) in rows:
                (DATA / "media" / filename).unlink(missing_ok=True)
    # Use the existing backend's own environment, never copy its database credentials.
    helper = '''
import json,sys
from db import SessionLocal
from models import User
with SessionLocal() as db:
    for user in json.load(sys.stdin):
        assert user["username"].startswith("tt_deploy_")
        query = db.query(User).filter(User.username == user["username"])
        if user.get("id") is not None:
            query = query.filter(User.id == user["id"])
        query.delete(synchronize_session=False)
    db.commit()
'''
    pid = subprocess.check_output(["pgrep", "-f", f"^{TEAM}/venv/bin/python3 {TEAM}/venv/bin/uvicorn "]).decode().split()[0]
    environment = os.environ.copy()
    for value in Path(f"/proc/{pid}/environ").read_bytes().split(b"\0"):
        if value and b"=" in value:
            key, data = value.split(b"=", 1)
            environment[os.fsdecode(key)] = os.fsdecode(data)
    subprocess.run([str(TEAM / "venv/bin/python"), "-c", helper], cwd=TEAM, env=environment,
                   input=json.dumps(users).encode(), check=True, stdout=subprocess.DEVNULL)


def save_manifest(users):
    path = DATA / "deployment-fixtures.json"
    descriptor = os.open(path, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(users, output)


def main():
    users, tokens = [], []
    try:
        manifest = DATA / "deployment-fixtures.json"
        if manifest.exists():
            cleanup(json.loads(manifest.read_text()))
            manifest.unlink()
        for label in ("a", "b"):
            username = f"tt_deploy_{uuid.uuid4().hex[:16]}_{label}"
            password = secrets.token_urlsafe(24)
            registered = request(AUTH, "/api/register", body={"username": username, "password": password})
            assert registered.get("status") == "success", "Registration failed"
            users.append({"username": username, "id": None})
            save_manifest(users)
            token = request(PUBLIC, "/api/login", body=urlencode({"username": username, "password": password}).encode(),
                            content_type="application/x-www-form-urlencoded")["access_token"]
            me = request(PUBLIC, "/api/users/me", token)
            users[-1]["id"] = me["user_id"]
            save_manifest(users)
            tokens.append(token)
        output = io.BytesIO()
        Image.new("RGB", (4, 4), "purple").save(output, "PNG")
        image_bytes = output.getvalue()
        boundary = uuid.uuid4().hex
        multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="check.png"\r\nContent-Type: image/png\r\n\r\n'.encode()
                     + image_bytes + f"\r\n--{boundary}--\r\n".encode())
        image = request(PUBLIC, "/api/forum/images", tokens[0], multipart, content_type=f"multipart/form-data; boundary={boundary}")
        draft = {"title": "Deployment check", "body": "Temporary two-account validation", "imageIds": [image["id"]]}
        post = request(PUBLIC, "/api/forum/posts", tokens[0], draft, "deployment-post")
        repeated = request(PUBLIC, "/api/forum/posts", tokens[0], draft, "deployment-post")
        assert repeated["id"] == post["id"], "Idempotency failed"
        feed = request(PUBLIC, "/api/forum/posts", tokens[1])
        assert any(item["id"] == post["id"] for item in feed["items"]), "Shared pool failed"
        assert request("", image["url"]) == image_bytes, "Cross-device image failed"
        path = f"/api/forum/posts/{post['id']}"
        for _ in range(2):
            liked = request(PUBLIC, path + "/like", tokens[1], method="PUT")
            assert liked["likeCount"] == 1 and liked["likedByMe"]
        comment = request(PUBLIC, path + "/replies", tokens[1], {"body": "Account B can comment"}, "deployment-reply")
        assert request(PUBLIC, path + "/replies", tokens[1], {"body": "Account B can comment"}, "deployment-reply")["id"] == comment["id"]
        refreshed = request(PUBLIC, path, tokens[0])
        assert refreshed["likeCount"] == 1 and refreshed["commentCount"] == 1 and not refreshed["likedByMe"]
        news = request(PUBLIC, "/api/forum/news", tokens[0])
        assert news["items"] and all(item["category"] in {"AI", "Technology"} for item in news["items"])
        subprocess.run(["systemctl", "restart", "tokentrail-forum.service"], check=True)
        import time
        for attempt in range(20):
            try:
                refreshed = request(PUBLIC, path, tokens[0])
                break
            except (HTTPError, OSError):
                if attempt == 19:
                    raise
                time.sleep(0.5)
        assert refreshed["commentCount"] == 1 and refreshed["images"][0]["id"] == image["id"]
        for _ in range(2):
            assert request(PUBLIC, path + "/like", tokens[1], method="DELETE")["likeCount"] == 0
        request(PUBLIC, "/api/logout", tokens[1], method="POST")
        try:
            request(PUBLIC, "/api/forum/posts", tokens[1])
            raise AssertionError("Logged-out token was accepted")
        except HTTPError as error:
            assert error.code == 401
        print(json.dumps({"https": True, "sharedPosts": True, "images": True, "likesComments": True,
                          "idempotency": True, "restartPersistence": True, "logoutRevocation": True,
                          "newsPageItems": len(news["items"])}))
    finally:
        for token in tokens:
            try:
                request(AUTH, "/api/logout", token, method="POST")
            except HTTPError:
                pass
        cleanup(users)
        (DATA / "deployment-fixtures.json").unlink(missing_ok=True)


if __name__ == "__main__":
    main()
