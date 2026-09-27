"""Verify two anonymous test identities without touching the team's account API.

Run as root on the deployment host; it restarts only the test forum and cleans its
own test fixtures. Tokens remain in memory and are never printed.
"""
import io
import json
from pathlib import Path
import sqlite3
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid
from PIL import Image

ORIGIN = "https://43.140.212.47"
ROOT = Path("/var/lib/tokentrail-forum-test")


def call(path, session=None, body=None, method=None, key=None, mime="application/json"):
    headers = {"Content-Type": mime}
    if session:
        headers["Authorization"] = "Bearer " + session["token"]
    if key:
        headers["Idempotency-Key"] = key
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
    with urlopen(Request(ORIGIN + path, body, headers, method=method), timeout=20) as response:
        value = response.read()
        return json.loads(value) if "application/json" in response.headers.get("Content-Type", "") else value


def main():
    sessions = []
    try:
        sessions.append(call("/test-api/forum/test-session", method="POST"))
        sessions.append(call("/test-api/forum/test-session", method="POST"))
        a, b = sessions
        assert a["accountId"] != b["accountId"]
        output = io.BytesIO()
        Image.new("RGB", (4, 4), "purple").save(output, "PNG")
        image = output.getvalue()
        boundary = uuid.uuid4().hex
        payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="test.png"\r\nContent-Type: image/png\r\n\r\n'.encode()
                   + image + f"\r\n--{boundary}--\r\n".encode())
        uploaded = call("/test-api/forum/images", a, payload, mime=f"multipart/form-data; boundary={boundary}")
        post = call("/test-api/forum/posts", a, {"body": "Temporary test area check", "imageIds": [uploaded["id"]]}, key="test-post")
        assert uploaded["url"].startswith(ORIGIN + "/test-api/")
        assert call(uploaded["url"][len(ORIGIN):]) == image
        assert any(item["id"] == post["id"] for item in call("/test-api/forum/posts", b)["items"])
        path = "/test-api/forum/posts/" + post["id"]
        assert call(path + "/like", b, method="PUT")["likeCount"] == 1
        assert call(path + "/replies", b, {"body": "Second temporary identity"}, key="reply")["authorUid"] == b["accountId"]
        assert call(path, a)["commentCount"] == 1
        assert call("/test-api/forum/news", a)["items"]
        try:
            call("/api/forum/posts", a)
            raise AssertionError("Production accepted a test identity")
        except HTTPError as error:
            assert error.code == 401
        subprocess.run(["systemctl", "restart", "tokentrail-forum-test.service"], check=True)
        for attempt in range(20):
            try:
                assert call(path, a)["commentCount"] == 1
                break
            except (HTTPError, URLError):
                if attempt == 19:
                    raise
                time.sleep(0.5)
        call("/test-api/forum/test-session", b, method="DELETE")
        try:
            call(path, b)
            raise AssertionError("Revoked test session accepted")
        except HTTPError as error:
            assert error.code == 401
        print(json.dumps({"noAccountRequired": True, "distinctDevices": True, "images": True,
                          "sharedPostsLikesComments": True, "news": True, "productionRejectsTestTokens": True,
                          "restartPersistence": True, "exitRevokesTestIdentity": True}))
    finally:
        with sqlite3.connect(ROOT / "forum.sqlite3") as db:
            for session in sessions:
                uid = session["accountId"]
                images = db.execute("SELECT filename FROM images WHERE owner_uid=?", (uid,)).fetchall()
                db.execute("DELETE FROM likes WHERE uid=? OR post_id IN (SELECT id FROM posts WHERE author_uid=?)", (uid, uid))
                db.execute("DELETE FROM replies WHERE author_uid=? OR post_id IN (SELECT id FROM posts WHERE author_uid=?)", (uid, uid))
                db.execute("DELETE FROM images WHERE owner_uid=?", (uid,))
                db.execute("DELETE FROM posts WHERE author_uid=?", (uid,))
                db.execute("DELETE FROM idempotency WHERE uid=?", (uid,))
                db.execute("DELETE FROM write_events WHERE uid=?", (uid,))
                db.execute("DELETE FROM test_sessions WHERE uid=?", (uid,))
                for (filename,) in images:
                    (ROOT / "media" / filename).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
