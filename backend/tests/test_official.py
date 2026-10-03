"""官方帖（把资讯映射成 `ForumPost`）的测试。

**这不是「拿别人的文章冒充官方帖」。** 来源是我们自己配的采集任务
（`tools/news/sources.json` 那五个源），它本来就是为这个项目采的。
测试要盯住的是三件事：

1. `source` 必须是 `OFFICIAL`——`ForumPost.Source` 的注释写明两类帖子的
   **可信度不同**（官方能当事实来源，社区只能当经验分享），
   客户端据此渲染徽章、agent 据此决定措辞。填错的后果是模型把社区经验
   当官方口径讲出来。
2. **互动计数是 0，而不是「不知道」**——官方帖不在 `posts` 表里，
   确实没有评论和点赞可数。
3. 官方帖和社区帖**不能互相污染**：`/posts` 只回社区，`/official` 只回官方。
"""
import httpx
import pytest
from fastapi.testclient import TestClient

from tokentrail_forum.app import Settings, create_app
from tokentrail_forum.news_job import import_articles
from tokentrail_forum.store import OFFICIAL_AUTHOR_NAME, OFFICIAL_AUTHOR_UID, Store
from tokentrail_forum.usage_store import UsageStore
from conftest import verifier
from test_forum import png
from helpers import account_login, account_token, bearer, completion, insert_usage
from test_seasons import make_season_app

TODAY_MS = 1_790_500_000_000


def article(article_id="news-1", title="A model update", category="AI",
            source="OpenAI", published=TODAY_MS, image=None, summary="短摘要"):
    return {"id": article_id, "title": title, "summary": summary, "sourceName": source,
            "originalUrl": f"https://example.com/{article_id}", "imageUrl": image,
            "category": category, "publishedAtEpochMillis": published}


# 论坛接口要**团队账号会话**，不是 relay key——两套身份是分开的
# （`CONTRACTS.md` §5）。这里用 `conftest.py` 那个假 verifier。
SESSION = {"Authorization": "Bearer account-a"}


def make_app_with_news(path, items):
    """一个论坛 app，里面先灌几条新闻。"""
    settings = Settings(str(path), "https://forum.example")
    app = create_app(settings, verifier())
    store = app.state.store
    if items:
        import_articles(store, {"items": items, "collectedAtEpochMillis": TODAY_MS,
                                "sourceErrors": {}})
    return app


def official(api, headers, **query):
    from urllib.parse import urlencode
    suffix = ("?" + urlencode(query)) if query else ""
    return api.get("/api/forum/official" + suffix, headers=headers)


TWO_ARTICLES = [article("n1", "Newer model update", published=TODAY_MS),
                article("n2", "Older pricing note", published=TODAY_MS - 86_400_000)]


@pytest.fixture
def forum(tmp_path):
    """一个灌了两条资讯的 app。"""
    return make_app_with_news(tmp_path, TWO_ARTICLES)


def test_official_returns_news_as_official_posts(forum):
    app = forum
    with TestClient(app) as api:
        body = official(api, SESSION).json()
    assert len(body) == 2
    first = body[0]
    # **这一条是重点**：source 填错的后果是两类帖子的可信度被混为一谈
    assert first["source"] == "OFFICIAL"
    assert first["title"] == "Newer model update"
    assert first["authorUid"] == OFFICIAL_AUTHOR_UID
    assert first["authorName"] == OFFICIAL_AUTHOR_NAME
    # 倒序：新的在前
    assert [item["title"] for item in body] == ["Newer model update", "Older pricing note"]


def test_official_posts_have_zero_interaction_counters(forum):
    app = forum
    """**0 而不是「不知道」。** 官方帖不在 `posts` 表里，确实没有互动可数。"""
    with TestClient(forum) as api:
        item = official(api, SESSION).json()[0]
    assert item["likeCount"] == 0
    assert item["commentCount"] == 0
    assert item["helpfulCount"] == 0
    assert item["likedByMe"] is False


def test_official_posts_keep_their_provenance(forum):
    app = forum
    """来源和原文链接要一起返回——界面上得能看出这条是哪来的。

    这是「不冒充」的技术落点：原始出处跟着帖子走，不是靠口头保证。
    """
    with TestClient(forum) as api:
        item = official(api, SESSION).json()[0]
    assert item["originalUrl"] == "https://example.com/n1"
    assert item["sourceName"] == "OpenAI"
    assert item["category"] == "AI"


def test_official_posts_carry_their_image(tmp_path):
    """带图的资讯，`ForumPost.images` 里要有那张图。

    §这条以前是假过的，记下来§：它拿 `forum` 这个 **app fixture** 当路径传给
    `make_app_with_news(path, ...)`——`str(app)` 变成
    `<fastapi.applications.FastAPI object at 0x...>`，于是 `os.makedirs` 在**仓库
    工作目录**里建了一个叫这个名字的目录，新库是空的；而断言读的是那个空库里
    `/forum/official` 返回的东西，正好是 `news` 表里的一条**真新闻**，它的
    `imageUrl` 就是 `https://img.example/x.png`——**碰巧对上了，所以测试是绿的**。
    现在老老实实按 `tmp_path` 建库、只灌这一条，断言才真的在测这件事。
    """
    app = make_app_with_news(tmp_path, [article("n9", image="https://img.example/x.png")])
    with TestClient(app) as api:
        body = official(api, SESSION).json()
    assert len(body) == 1 and body[0]["id"].endswith("n9"), body
    assert body[0]["images"] and body[0]["images"][0]["url"] == "https://img.example/x.png"


def test_official_detail_opens(forum):
    app = forum
    """列表里点进去要能打开。**不试官方帖的话，详情是 404——用户会以为链接坏了。**"""
    with TestClient(forum) as api:
        response = api.get("/api/forum/posts/n1", headers=SESSION)
        assert response.status_code == 200, response.text
        assert response.json()["source"] == "OFFICIAL"


def test_a_missing_id_is_still_404(forum):
    app = forum
    with TestClient(app) as api:
        response = api.get("/api/forum/posts/nope", headers=SESSION)
        assert response.status_code == 404


def test_community_and_official_pools_do_not_mix(forum):
    app = forum
    """`/posts` 只回社区帖，`/official` 只回官方帖。**两边的 id 也不能撞。**"""
    with TestClient(app) as api:
        community = api.get("/api/forum/posts", headers=SESSION).json()["items"]
        official_items = official(api, SESSION).json()
    # 新闻不冒充社区帖
    assert all(item["source"] == "COMMUNITY" for item in community)
    assert all(item["source"] == "OFFICIAL" for item in official_items)
    # 新灌的新闻不该出现在社区池里
    assert "n1" not in {item["id"] for item in community}


def test_highlights_include_both_kinds_sorted_apart(forum):
    app = forum
    """大纲要求的是「官方帖 + 热帖」，而且调用方要能分清两半。"""
    with TestClient(forum) as api:
        api.post("/api/forum/posts", headers={**SESSION, "Idempotency-Key": "p1"},
                 json={"body": "a community post"})
        body = api.get("/api/forum/highlights", headers=SESSION).json()
    sources = [item["source"] for item in body["posts"]]
    assert "OFFICIAL" in sources, sources
    assert "COMMUNITY" in sources, sources


def test_highlights_keep_official_items_even_with_a_model_filter(forum):
    app = forum
    """按模型筛的时候官方帖**不该消失**——否则 agent 会以为没有官方公告。"""
    with TestClient(forum) as api:
        body = api.get("/api/forum/highlights?modelFilter=gpt-5",
                       headers=SESSION).json()
    assert [item["source"] for item in body["posts"]] == ["OFFICIAL", "OFFICIAL"]


def test_official_requires_a_session(forum):
    app = forum
    with TestClient(app) as api:
        assert api.get("/api/forum/official").status_code == 401


def test_an_empty_news_table_gives_an_empty_official_list(tmp_path):
    """没有资讯时回空列表——**不是**编一条出来。"""
    app = make_app_with_news(tmp_path, [])
    with TestClient(app) as api:
        assert official(api, SESSION).json() == []


def test_the_agent_tool_reports_both_counts(tmp_path):
    """agent 的 `getForumHighlights` 要能拿官方帖，而且**说明两者可信度不同**。"""
    from tokentrail_forum.agent_tools import ToolContext, execute
    from tokentrail_forum.budgets import Budgets
    from tokentrail_forum.pricing import Pricing
    from tokentrail_forum.seasons import Seasons

    # 自己建一份：这个测试要同时拿到 app 和**它用的那个路径**
    # （读写器必须和 app 指向同一个库，塞进子目录就会读空）。
    make_app_with_news(tmp_path, TWO_ARTICLES)
    store = Store(str(tmp_path), "/api")
    usage_store = UsageStore(store)
    pricing = Pricing(store)
    context = ToolContext(digest="u_test_account", uid=None, usage=usage_store, pricing=pricing,
                          seasons=Seasons(store, usage_store), budgets=Budgets(store, usage_store, pricing),
                          store=store)
    payload, evidence = execute("getForumHighlights", {"limit": 5}, **vars(context))
    # 官方帖在（新闻是公共内容，不需要论坛身份）
    assert payload["officialCount"] == 2, payload
    # 证据里必须点明「官方可作事实来源、社区只能当经验分享」
    assert "事实来源" in evidence["basis"], evidence
