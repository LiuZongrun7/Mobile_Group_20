"""Forum search and headline-frequency topics. Uses no model calls or external requests."""
import hashlib
import re
import unicodedata
from collections import defaultdict
from fastapi import HTTPException

STOP_WORDS = frozenset("a an and are as at be been but by can could for from has have how in into is it its of on or our that the their this to was were what when which who will with you your new latest says say said after about more not than over all now ai technology".split())

def search_query(value: str | None) -> str:
    query = " ".join(unicodedata.normalize("NFKC", value or "").lower().split())
    if len(query) > 100:
        raise HTTPException(400, "Search query exceeds 100 characters")
    return query

def search_kind(kind: str, query: str) -> str:
    # Bind cursors to the exact normalized query; do not expose it in the cursor kind.
    return kind if not query else kind + ":" + hashlib.sha256(query.encode("utf-8")).hexdigest()

def news_topics(articles):
    counts = defaultdict(lambda: {"articles": 0, "sources": set(), "latest": 0})
    for article in articles:
        words = re.findall(r"[a-z][a-z0-9-]{2,}|[\u4e00-\u9fff]{2,}",
                           unicodedata.normalize("NFKC", article["title"] or "").lower())
        unique = set()
        for word in words:
            word = {"agents": "agent", "models": "model", "tokens": "token"}.get(word, word)
            if word not in STOP_WORDS:
                unique.add(word)
        for word in unique:
            data = counts[word]
            data["articles"] += 1
            source = article["source_name"]
            if source:
                data["sources"].add(source)
            data["latest"] = max(data["latest"], article["published"] or 0)
    ranked = sorted(((word, data) for word, data in counts.items() if data["articles"] >= 2),
                    key=lambda item: (-item[1]["articles"], -len(item[1]["sources"]),
                                      -item[1]["latest"], item[0]))[:10]
    return [{"name": word, "query": word, "rank": i + 1, "articleCount": data["articles"],
             "sourceCount": len(data["sources"]), "latestPublishedAtEpochMillis": data["latest"]}
            for i, (word, data) in enumerate(ranked)]
