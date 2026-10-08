import pytest
from fastapi.testclient import TestClient
from conftest import verifier
from helpers import create_app_for_tests
from modelpilot_forum.app import Settings
from modelpilot_forum.news_job import import_articles
from modelpilot_forum.store import now_ms

A = {"Authorization": "Bearer account-a"}

@pytest.fixture
def api(tmp_path):
    app = create_app_for_tests(Settings(str(tmp_path), "https://forum.example"), verifier())
    with TestClient(app) as client:
        yield client

def test_search_is_case_insensitive_and_cursor_is_query_bound(api):
    for i in range(2):
        response = api.post("/api/forum/posts", headers={**A, "Idempotency-Key": f"search-{i}"},
                            json={"title": f"Claude {i}", "body": "Test", "imageIds": []})
        assert response.status_code == 201
    response = api.get("/api/forum/posts", headers=A, params={"q": " CLAUDE ", "limit": 1})
    assert response.status_code == 200
    first = response.json()
    assert len(first["items"]) == 1 and first["nextCursor"]
    second = api.get("/api/forum/posts", headers=A, params={"q": "claude", "cursor": first["nextCursor"]})
    assert second.status_code == 200 and len(second.json()["items"]) == 1
    assert api.get("/api/forum/posts", headers=A,
                   params={"q": "deepseek", "cursor": first["nextCursor"]}).status_code == 400

def test_trending_uses_recent_articles_and_authentication(api):
    timestamp = now_ms()
    import_articles(api.app.state.store, {"items": [
        {"id": str(i), "title": "Claude agents" if i < 2 else "Claude historical",
         "sourceName": f"Source {i}", "originalUrl": f"https://news.example/{i}",
         "category": "AI", "publishedAtEpochMillis": timestamp if i < 2 else timestamp - 8*86400000}
        for i in range(3)], "collectedAtEpochMillis": timestamp, "sourceErrors": {}})
    assert api.get("/api/forum/trending").status_code == 401
    response = api.get("/api/forum/trending", headers=A)
    assert response.status_code == 200
    result = response.json()
    assert result["windowDays"] == 7 and result["posts"] == []
    claude = next(topic for topic in result["topics"] if topic["name"] == "claude")
    assert claude["articleCount"] == 2 and claude["sourceCount"] == 2
