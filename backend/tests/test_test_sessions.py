import json
import io
from fastapi.testclient import TestClient
from PIL import Image
import pytest

from tokentrail_forum.app import Settings, create_app
from tokentrail_forum.store import Store, now_ms
from tokentrail_forum.news_job import import_articles, export_news
from test_forum import verifier, png


def make_test_app(path, news_database=""):
    return create_app(Settings(str(path), "https://forum.example", test_sessions_enabled=True,
                               public_api_prefix="/test-api", news_database=news_database), verifier())


def enter(api):
    response = api.post("/api/forum/test-session")
    assert response.status_code == 201
    body = response.json()
    return {"Authorization": "Bearer " + body["token"]}, body


def test_temporary_identities_need_no_team_account_and_can_interact(tmp_path):
    with TestClient(make_test_app(tmp_path)) as api:
        a, first = enter(api)
        b, second = enter(api)
        assert first["accountId"] != second["accountId"] and first["displayName"].startswith("Tester ")
        assert now_ms() < first["expiresAtEpochMillis"] <= now_ms() + 86_400_000
        uploaded = api.post("/api/forum/images", headers=a, files={"image": ("test.png", png(), "image/png")}).json()
        assert uploaded["url"].startswith("https://forum.example/test-api/forum/images/")
        post = api.post("/api/forum/posts", headers={**a, "Idempotency-Key": "test-post"},
                        json={"body": "A shared test post", "imageIds": [uploaded["id"]]}).json()
        assert api.get("/api/forum/posts", headers=b).json()["items"][0]["id"] == post["id"]
        path = f"/api/forum/posts/{post['id']}"
        assert api.put(path + "/like", headers=b).json()["likeCount"] == 1
        assert api.post(path + "/replies", headers={**b, "Idempotency-Key": "reply"}, json={"body": "Test reply"}).status_code == 201
        assert api.get(path, headers=a).json()["commentCount"] == 1
        with api.app.state.store.connect() as db:
            stored = db.execute("SELECT token_hash FROM test_sessions").fetchall()
        assert all(row[0] != first["token"] and len(row[0]) == 64 for row in stored)


def test_production_service_rejects_test_tokens_and_cannot_issue_them(tmp_path):
    with TestClient(make_test_app(tmp_path / "test")) as api:
        authorization, session = enter(api)
    with TestClient(create_app(Settings(str(tmp_path / "main"), "https://forum.example"), verifier())) as main:
        assert main.post("/api/forum/test-session").status_code == 404
        assert main.get("/api/forum/posts", headers=authorization).status_code == 401
    with pytest.raises(ValueError, match="isolated"):
        create_app(Settings(str(tmp_path), "https://forum.example", test_sessions_enabled=True), verifier())


def test_expiry_revocation_and_restart(tmp_path):
    with TestClient(make_test_app(tmp_path)) as api:
        first, _ = enter(api)
        second, _ = enter(api)
        assert api.delete("/api/forum/test-session", headers=second).status_code == 200
        assert api.get("/api/forum/posts", headers=second).status_code == 401
    with TestClient(make_test_app(tmp_path)) as restarted:
        assert restarted.get("/api/forum/posts", headers=first).status_code == 200
        with restarted.app.state.store.connect(write=True) as db:
            db.execute("UPDATE test_sessions SET expires=?", (now_ms() - 1,))
        assert restarted.get("/api/forum/posts", headers=first).status_code == 401
        fresh, _ = enter(restarted)
        assert restarted.get("/api/forum/posts", headers=fresh).status_code == 200


def test_test_posts_are_isolated_and_news_reads_share_main_database(tmp_path):
    main_store = Store(tmp_path / "main")
    with main_store.connect(write=True) as db:
        db.execute("INSERT INTO posts VALUES('real-post','','Real post','a','Alice',?)", (now_ms(),))
    import_articles(main_store, {"items": [{"id": "news", "title": "AI news", "sourceName": "Source", "category": "AI",
                       "originalUrl": "https://news.example", "publishedAtEpochMillis": now_ms()}],
                       "collectedAtEpochMillis": now_ms(), "sourceErrors": {}})
    export_news(main_store)
    snapshot = main_store.directory / "news-readonly.sqlite3"
    with TestClient(make_test_app(tmp_path / "test", str(snapshot))) as api:
        user, _ = enter(api)
        assert api.get("/api/forum/posts", headers=user).json()["items"] == []
        assert api.get("/api/forum/news", headers=user).json()["items"][0]["id"] == "news"
        assert api.get("/health").json()["newsCount"] == 1
        assert api.get("/api/forum/posts/real-post", headers=user).status_code == 404
    with main_store.news_connect(str(snapshot)) as db:
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert tables == {"news", "metadata"}
        with pytest.raises(Exception):
            db.execute("DELETE FROM news")


def test_issue_rate_limit_and_no_identity_spoofing(tmp_path):
    with TestClient(make_test_app(tmp_path)) as api:
        for _ in range(10):
            enter(api)
        assert api.post("/api/forum/test-session").status_code == 429
        assert api.get("/api/forum/posts", headers={"Authorization": "Bearer tt_test_guessed"}).status_code == 401
        assert api.get("/api/forum/posts").status_code == 401
