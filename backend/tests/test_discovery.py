import pytest
from fastapi import HTTPException
from modelpilot_forum.discovery import news_topics, search_kind, search_query

def article(title, source="A", at=1):
    return {"title": title, "source_name": source, "published": at}

def test_search_normalizes_case_spaces_and_binds_cursor():
    assert search_query("  Claude   Agents ") == "claude agents"
    assert search_kind("news", "") == "news"
    assert search_kind("news", "claude") != search_kind("news", "deepseek")
    assert search_kind("news", "claude") != search_kind("posts", "claude")

def test_search_length_is_bounded():
    with pytest.raises(HTTPException):
        search_query("a" * 101)

def test_topic_counts_articles_not_repetitions_and_merges_plural():
    result = news_topics([article("Agent agent agents"), article("Agents announced", "B", 2)])
    assert result == [{"name": "agent", "query": "agent", "rank": 1, "articleCount": 2,
                       "sourceCount": 2, "latestPublishedAtEpochMillis": 2}]

def test_single_mentions_and_stop_words_are_not_trending():
    assert news_topics([article("The new model"), article("The latest news")]) == []

def test_topics_are_limited_and_ranks_are_contiguous():
    title = " ".join(f"topic{i}" for i in range(20))
    result = news_topics([article(title), article(title, "B")])
    assert len(result) == 10
    assert [x["rank"] for x in result] == list(range(1, 11))
