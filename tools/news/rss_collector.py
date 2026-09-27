#!/usr/bin/env python3
"""English AI/technology RSS collector for the team's backend. Python 3.10+, stdlib only.

Run hourly on the backend, then ingest `items` into its news table. This is NOT an
Android direct-to-publisher client or a substitute for the team's authenticated API.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit, urljoin
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

ATOM = "{http://www.w3.org/2005/Atom}"
MEDIA = "{http://search.yahoo.com/mrss/}"


class PlainText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.hidden = 0
        self.image = None

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style"}:
            self.hidden += 1
        if tag in {"p", "div", "br", "li"}:
            self.parts.append(" ")
        if tag == "img" and self.image is None:
            self.image = dict(attrs).get("src")

    def handle_endtag(self, tag):
        if tag in {"script", "style"}:
            self.hidden = max(0, self.hidden - 1)
        if tag in {"p", "div", "li"}:
            self.parts.append(" ")

    def handle_data(self, data):
        if not self.hidden:
            self.parts.append(data)


def text_and_image(html):
    parser = PlainText()
    parser.feed(html or "")
    return re.sub(r"\s+", " ", "".join(parser.parts)).strip(), parser.image


def canonical_url(url):
    parts = urlsplit(url)
    if parts.scheme not in {"https", "http"} or not parts.netloc:
        raise ValueError("Invalid article URL")
    query = [(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True)
             if not k.lower().startswith("utm_") and k.lower() not in {"fbclid", "gclid"}]
    return urlunsplit((parts.scheme.lower(), parts.netloc.lower(), parts.path or "/", urlencode(sorted(query)), ""))


def timestamp(value):
    try:
        date = parsedate_to_datetime(value)
    except (ValueError, TypeError):
        date = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    if date.tzinfo is None:
        date = date.replace(tzinfo=timezone.utc)
    return int(date.timestamp() * 1000)


def parse_feed(xml, source):
    root = ET.fromstring(xml)
    entries = root.findall(".//item") + root.findall(f".//{ATOM}entry")
    articles = []
    for entry in entries:
        try:
            title, _ = text_and_image(entry.findtext("title") or entry.findtext(f"{ATOM}title"))
            url = entry.findtext("link")
            if not url:
                for link in entry.findall(f"{ATOM}link"):
                    if link.get("rel", "alternate") == "alternate":
                        url = link.get("href")
                        break
            if not url:
                continue
            url = canonical_url(urljoin(source["url"], url))
            date = entry.findtext("pubDate") or entry.findtext(f"{ATOM}published") or entry.findtext(f"{ATOM}updated") or entry.findtext("{http://purl.org/dc/elements/1.1/}date")
            # Missing dates cannot be presented as fresh news.
            published = timestamp(date)
            summary, image = text_and_image(entry.findtext("description") or entry.findtext(f"{ATOM}summary") or entry.findtext(f"{ATOM}content"))
            for enclosure in list(entry.findall("enclosure")) + list(entry.findall(f"{MEDIA}content")) + list(entry.findall(f"{MEDIA}thumbnail")):
                mime = enclosure.get("type", "")
                if enclosure.tag == f"{MEDIA}thumbnail" or mime.startswith("image/") or enclosure.get("medium") == "image":
                    image = enclosure.get("url") or image
                    break
            image = urljoin(url, image) if image else None
            if image and urlsplit(image).scheme not in {"https", "http"}:
                image = None
            if not title:
                continue
            articles.append({
                "id": hashlib.sha256(url.encode()).hexdigest(), "title": title,
                "summary": summary[:1200] or None, "sourceName": source["name"],
                "originalUrl": url, "imageUrl": image, "category": source["category"],
                "publishedAtEpochMillis": published,
            })
        except (ValueError, TypeError, AttributeError):
            # One malformed entry does not discard an otherwise healthy source.
            continue
    return articles


def fetch_source(source):
    request = Request(source["url"], headers={"User-Agent": "TokenTrail-News/1.0", "Accept": "application/rss+xml, application/atom+xml, application/xml"})
    with urlopen(request, timeout=20) as response:
        data = response.read(8 * 1024 * 1024 + 1)
        if len(data) > 8 * 1024 * 1024:
            raise ValueError("Feed exceeds 8 MB")
    return parse_feed(data, source)


def collect(sources, previous=None, fetch=fetch_source, now=None, retention_days=30):
    now = int(datetime.now(timezone.utc).timestamp() * 1000) if now is None else now
    names = {s["name"] for s in sources}
    articles = {a["id"]: a for a in (previous or []) if a.get("sourceName") in names}
    errors = {}
    # Map preserves configured source priority even though network calls run concurrently.
    def safely(source):
        try:
            return source, fetch(source), None
        except Exception as error:
            return source, [], type(error).__name__ + ": " + str(error)
    fresh_ids = set()
    with ThreadPoolExecutor(max_workers=5) as pool:
        for source, entries, error in pool.map(safely, sources):
            if error:
                errors[source["name"]] = error
            for article in entries:
                if article["id"] not in fresh_ids:
                    articles[article["id"]] = article
                    fresh_ids.add(article["id"])
    cutoff = now - retention_days * 86400000
    items = [a for a in articles.values() if cutoff <= a["publishedAtEpochMillis"] <= now + 300000]
    items.sort(key=lambda a: (a["publishedAtEpochMillis"], a["id"]), reverse=True)
    return {"items": items, "collectedAtEpochMillis": now, "sourceErrors": errors}


def atomic_write(path, document):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, encoding="utf-8", delete=False) as file:
            temporary = file.name
            json.dump(document, file, ensure_ascii=False, indent=2)
            file.write("\n")
        os.replace(temporary, path)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, default=Path(__file__).with_name("sources.json"))
    parser.add_argument("--output", type=Path, required=True, help="Private backend staging file; not the Android API")
    args = parser.parse_args()
    sources = json.loads(args.sources.read_text())["sources"]
    if not sources or any(s.get("category") not in {"AI", "Technology"} or urlsplit(s["url"]).scheme != "https" for s in sources):
        parser.error("Only configured HTTPS AI/Technology sources are allowed")
    previous = json.loads(args.output.read_text()).get("items", []) if args.output.exists() else []
    document = collect(sources, previous)
    atomic_write(args.output, document)
    print(json.dumps({"articles": len(document["items"]), "sourceErrors": document["sourceErrors"]}))
    return 1 if len(document["sourceErrors"]) == len(sources) else 0


if __name__ == "__main__":
    raise SystemExit(main())
