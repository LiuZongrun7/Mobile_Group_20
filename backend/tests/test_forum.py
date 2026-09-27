from concurrent.futures import ThreadPoolExecutor
import io
import json

from fastapi import HTTPException
from fastapi.testclient import TestClient
from PIL import Image
import pytest

from conftest import verifier
from tokentrail_forum.app import Settings, create_app
from tokentrail_forum.news_job import cleanup_unused_images, import_articles
from tokentrail_forum.store import now_ms


@pytest.fixture
def api(tmp_path):
    """注入假 verifier 的 app。**假实现和登录流程在 `conftest.py` 里**，
    这里不再自己拼一个——理由见那个文件的说明。"""
    app = create_app(Settings(str(tmp_path), "https://forum.example"), verifier())
    with TestClient(app) as client:
        yield client


A = {"Authorization": "Bearer account-a"}
B = {"Authorization": "Bearer account-b"}


def publish(api, key="post-key", body="Hello", images=None, headers=A):
    return api.post("/api/forum/posts", headers={**headers, "Idempotency-Key": key},
                    json={"title": "", "body": body, "imageIds": images or []})


def png():
    output = io.BytesIO()
    Image.new("RGB", (4, 4), "purple").save(output, "PNG")
    return output.getvalue()


def test_auth_is_required_and_expired_session_rejected(api):
    assert api.get("/api/forum/posts").status_code == 401
    assert api.get("/api/forum/news", headers={"Authorization": "Bearer expired"}).status_code == 401
    assert api.get("/api/forum/posts", headers=A).json() == {"items": [], "nextCursor": None}


def test_fail_closed_when_the_session_is_unknown(tmp_path):
    """**默认实现是我们自己的 `accounts` 表**（2026-09-27 起不再打外部服务）。

    没登录过、或者 token 是编的 → 401，而且论坛什么都不给。
    这条盯的是「fail closed」这个性质：不认识的会话绝不能放行。
    """
    with TestClient(create_app(Settings(str(tmp_path), "https://forum.example"))) as api:
        assert api.get("/api/forum/posts", headers=A).status_code == 401
        assert api.get("/api/forum/posts").status_code == 401


def test_an_unknown_token_is_refused(tmp_path):
    """认不出的会话绝不能放行。**fail closed。**

    这条原来测的是外部 verifier（`TeamAuth`）自身出错时返回 503 而不是放行。
    `TeamAuth` 已经删掉，但性质本身不能丢：现在唯一的鉴权路是查我们自己的
    `account_sessions` 表，认不出就是 401，**没有任何回退分支**。
    """
    with TestClient(create_app(Settings(str(tmp_path), "https://forum.example"), verifier())) as api:
        assert api.get("/api/forum/posts", headers={"Authorization": "Bearer nonsense"}).status_code == 401
        assert api.get("/api/forum/posts", headers={"Authorization": "Basic abc"}).status_code == 401
        assert api.get("/api/forum/posts").status_code == 401


def test_two_accounts_share_posts_likes_and_replies(api):
    post = publish(api).json()
    assert post["authorUid"] == "a"
    assert api.get("/api/forum/posts", headers=B).json()["items"][0]["id"] == post["id"]
    path = f"/api/forum/posts/{post['id']}"
    for _ in range(2):
        liked = api.put(path + "/like", headers=B).json()
        assert liked["likeCount"] == 1 and liked["likedByMe"] is True
    assert api.get(path, headers=A).json()["likedByMe"] is False
    comment = api.post(path + "/replies", headers={**B, "Idempotency-Key": "reply-key"}, json={"body": "Useful"})
    assert comment.status_code == 201
    repeated = api.post(path + "/replies", headers={**B, "Idempotency-Key": "reply-key"}, json={"body": "Useful"})
    assert repeated.json() == comment.json()
    assert api.get(path, headers=A).json()["commentCount"] == 1
    assert api.get(path + "/replies", headers=A).json()["items"][0]["authorUid"] == "b"
    for _ in range(2):
        unliked = api.delete(path + "/like", headers=B)
        assert unliked.status_code == 200 and unliked.json()["likeCount"] == 0


def test_idempotency_and_payload_conflict(api):
    first = publish(api)
    assert first.status_code == 201
    assert publish(api).json() == first.json()
    assert publish(api, body="Different").status_code == 409
    assert len(api.get("/api/forum/posts", headers=A).json()["items"]) == 1
    assert publish(api, headers=B).json()["id"] != first.json()["id"]


def test_concurrent_retry_is_atomic(api):
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: publish(api, key="concurrent"), range(4)))
    assert all(result.status_code == 201 for result in results)
    assert len({result.json()["id"] for result in results}) == 1


def test_upload_ownership_unpublished_privacy_and_durable_image(api):
    uploaded = api.post("/api/forum/images", headers=A, files={"image": ("image.png", png(), "image/png")})
    assert uploaded.status_code == 201
    image = uploaded.json()
    path = f"/api/forum/images/{image['id']}"
    assert api.get(path).status_code == 401
    assert api.get(path, headers=B).status_code == 403
    assert api.get(path, headers=A).content == png()
    assert publish(api, images=[image["id"]], headers=B).status_code == 403
    post = publish(api, images=[image["id"]], body="").json()
    assert post["images"] == [image]
    downloaded = api.get(path)
    assert downloaded.status_code == 200 and downloaded.content == png()
    assert downloaded.headers["content-type"] == "image/png"
    assert publish(api, key="reuse", images=[image["id"]]).status_code == 403


def test_input_validation_and_spoofed_author_rejected(api):
    assert publish(api, body="  ").status_code == 400
    assert publish(api, images=["x"] * 10).status_code == 400
    assert publish(api, images=["x", "x"]).status_code == 400
    assert publish(api, key="").status_code == 400
    response = api.post("/api/forum/posts", headers={**A, "Idempotency-Key": "spoof"},
                        json={"body": "Test", "authorUid": "b"})
    assert response.status_code == 400
    assert api.get("/api/forum/posts?limit=21", headers=A).status_code == 400
    assert api.get("/api/forum/posts?cursor=broken", headers=A).status_code == 400


def test_invalid_and_oversized_images_rejected(api):
    assert api.post("/api/forum/images", headers=A, files={"image": ("fake.png", b"not an image", "image/png")}).status_code == 400
    assert api.post("/api/forum/images", headers=A, files={"image": ("large.png", b"0" * (10 * 1024 * 1024 + 1), "image/png")}).status_code == 413
    assert api.post("/api/forum/images", headers=A, content=b"0" * (11 * 1024 * 1024 + 1)).status_code == 413


def test_post_pagination_with_equal_timestamps_no_duplicates(api):
    store = api.app.state.store
    with store.connect(write=True) as db:
        for index in range(45):
            db.execute("INSERT INTO posts VALUES(?,?,?,?,?,?)", (f"{index:03}", "", "Post", "a", "User A", 1000))
    items, cursor = [], None
    for _ in range(3):
        response = api.get("/api/forum/posts", headers=A, params={"cursor": cursor} if cursor else {})
        document = response.json()
        items += document["items"]
        cursor = document["nextCursor"]
    assert cursor is None and len(items) == 45 and len({item["id"] for item in items}) == 45
    assert [item["id"] for item in items] == sorted((item["id"] for item in items), reverse=True)


def test_reply_cursor_cannot_be_reused_on_another_post(api):
    first, second = publish(api).json()["id"], publish(api, key="second").json()["id"]
    with api.app.state.store.connect(write=True) as db:
        for index in range(3):
            db.execute("INSERT INTO replies VALUES(?,?,?,?,?,?)", (str(index), first, "Reply", "b", "User B", 1000))
    response = api.get(f"/api/forum/posts/{first}/replies?limit=2", headers=A).json()
    assert [item["id"] for item in response["items"]] == ["0", "1"]
    cursor = response["nextCursor"]
    assert api.get(f"/api/forum/posts/{second}/replies", headers=A, params={"cursor": cursor}).status_code == 400
    last = api.get(f"/api/forum/posts/{first}/replies", headers=A, params={"cursor": cursor}).json()
    assert [item["id"] for item in last["items"]] == ["2"] and last["nextCursor"] is None


def test_missing_posts_and_rate_limit(api):
    assert api.get("/api/forum/posts/missing", headers=A).status_code == 404
    assert api.put("/api/forum/posts/missing/like", headers=A).status_code == 404
    for index in range(5):
        assert publish(api, key=str(index)).status_code == 201
    assert publish(api, key="over-limit").status_code == 429
    assert publish(api, key="0").status_code == 201


def test_persistence_across_restart(api, tmp_path):
    post = publish(api).json()
    with TestClient(create_app(Settings(str(tmp_path), "https://forum.example"), verifier())) as restarted:
        assert restarted.get(f"/api/forum/posts/{post['id']}", headers=B).json() == post


def test_news_upsert_pagination_and_source_failure_metadata(api):
    document = {"collectedAtEpochMillis": now_ms(), "sourceErrors": {"Test": "Timed out"},
                "items": [{"id": f"n{i}", "title": "AI News", "summary": "Summary", "sourceName": "Test",
                           "originalUrl": f"https://news.example/{i}", "category": "AI",
                           "publishedAtEpochMillis": now_ms()} for i in range(3)]}
    import_articles(api.app.state.store, document)
    document["items"][0]["title"] = "Updated AI News"
    import_articles(api.app.state.store, document)
    first = api.get("/api/forum/news?limit=2", headers=A).json()
    second = api.get("/api/forum/news", headers=A, params={"cursor": first["nextCursor"]}).json()
    assert len(first["items"]) + len(second["items"]) == 3
    assert second["nextCursor"] is None
    health = api.get("/health").json()
    assert health["newsCount"] == 3 and health["newsSourceErrors"] == {"Test": "Timed out"}


def test_unused_image_cleanup_preserves_published_images(api):
    store = api.app.state.store
    old = now_ms() - 8 * 86_400_000
    with store.connect(write=True) as db:
        db.execute("INSERT INTO posts VALUES('p','','Text','a','A',?)", (old,))
        for image_id, post in [("unused", None), ("published", "p")]:
            (store.media / image_id).write_bytes(png())
            db.execute("INSERT INTO images VALUES(?,?,?,?,?,?,?)", (image_id, "a", image_id, "image/png", old, post, 0))
    cleanup_unused_images(store)
    assert not (store.media / "unused").exists() and (store.media / "published").exists()


def test_agent_compatibility_is_public_author_filter_not_news(api):
    post = publish(api).json()
    assert api.get("/api/forum/official", headers=A).json() == []
    assert api.get("/api/forum/me/posts", headers=B).json() == []
    assert api.get("/api/forum/hot", headers=B).json()[0]["id"] == post["id"]
    assert api.get("/api/forum/highlights", headers=B).json()["posts"][0]["source"] == "COMMUNITY"
    assert api.get("/api/forum/me/threads", headers=A).json()["threads"][0]["post"]["id"] == post["id"]
    assert api.get("/api/forum/hot?since=invalid", headers=A).status_code == 400
