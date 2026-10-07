import unittest
from rss_collector import canonical_url, collect, parse_feed, timestamp, PreviewImage, fill_article_images, preview_allowed


class CollectorTests(unittest.TestCase):
    source = {"name": "AI publisher", "category": "AI", "url": "https://example.com/feed"}

    def test_rss_cleans_html_and_preserves_original_semantic_query(self):
        feed = b'''<rss><channel><item><title>New &amp; useful model</title>
        <link>https://example.com/story?id=42&amp;utm_source=rss#top</link>
        <pubDate>Sat, 26 Sep 2026 08:00:00 GMT</pubDate>
        <description><![CDATA[<p>A model.</p><script>bad()</script><p>Details.</p><img src="/cover.jpg"/>]]></description>
        </item></channel></rss>'''
        article = parse_feed(feed, self.source)[0]
        self.assertEqual(article["originalUrl"], "https://example.com/story?id=42")
        self.assertEqual(article["summary"], "A model. Details.")
        self.assertEqual(article["imageUrl"], "https://example.com/cover.jpg")
        self.assertEqual(article["title"], "New & useful model")

    def test_atom_and_invalid_entries(self):
        feed = b'''<feed xmlns="http://www.w3.org/2005/Atom">
        <entry><title>Good</title><link href="https://example.com/1"/><updated>2026-09-26T08:00:00Z</updated></entry>
        <entry><title>No date</title><link href="https://example.com/2"/></entry>
        <entry><title>Bad date</title><link href="https://example.com/3"/><updated>bad</updated></entry>
        </feed>'''
        self.assertEqual(len(parse_feed(feed, self.source)), 1)

    def test_partial_failure_retains_cached_source_and_deduplicates(self):
        now = timestamp("2026-09-26T09:00:00Z")
        cached = {"id": "cached", "sourceName": "Offline", "publishedAtEpochMillis": now - 10000}
        article = {"id": "shared", "sourceName": "AI publisher", "publishedAtEpochMillis": now}
        second = {"name": "Offline", "category": "AI", "url": "https://offline.example/feed"}
        def fetch(source):
            if source["name"] == "Offline":
                raise TimeoutError("Timed out")
            return [article, article]
        result = collect([self.source, second], [cached], fetch, now)
        self.assertEqual([a["id"] for a in result["items"]], ["shared", "cached"])
        self.assertIn("Offline", result["sourceErrors"])

    def test_retention_removed_sources_future_dates_and_stable_sort(self):
        now = timestamp("2026-09-26T09:00:00Z")
        def article(id, epoch, source="AI publisher"):
            return {"id": id, "sourceName": source, "publishedAtEpochMillis": epoch}
        previous = [article("b", now), article("a", now), article("old", now - 31 * 86400000), article("future", now + 86400000), article("removed", now, "Removed")]
        result = collect([self.source], previous, lambda s: [], now)
        self.assertEqual([a["id"] for a in result["items"]], ["b", "a"])

    def test_non_http_links_are_rejected(self):
        with self.assertRaises(ValueError):
            canonical_url("javascript:alert(1)")

    def test_full_rss_content_supplies_image_without_replacing_short_summary(self):
        feed = b'''<rss xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><item>
        <title>Story</title><link>https://example.com/story</link><pubDate>Sat, 26 Sep 2026 08:00:00 GMT</pubDate>
        <description>Short summary</description><content:encoded><![CDATA[<img src="/story.jpg"/>Full text]]></content:encoded>
        </item></channel></rss>'''
        item = parse_feed(feed, self.source)[0]
        self.assertEqual(item['imageUrl'], 'https://example.com/story.jpg')
        self.assertEqual(item['summary'], 'Short summary')

    def test_preview_image_uses_article_metadata_and_rejects_script_urls(self):
        parser = PreviewImage()
        parser.feed('<meta name="twitter:image" content="/twitter.jpg"><meta property="og:image" content="/article.jpg?a=1&amp;b=2">')
        self.assertEqual(parser.image('https://example.com/story'), 'https://example.com/article.jpg?a=1&b=2')
        parser = PreviewImage()
        parser.feed('<meta property="og:image" content="javascript:alert(1)">')
        self.assertIsNone(parser.image('https://example.com/story'))

    def test_preview_fetch_only_visits_configured_publishers(self):
        self.assertTrue(preview_allowed('https://techcrunch.com/story'))
        self.assertFalse(preview_allowed('https://techcrunch.com.attacker.test/story'))
        self.assertFalse(preview_allowed('https://127.0.0.1/story'))
        self.assertFalse(preview_allowed('http://techcrunch.com/story'))

    def test_cached_article_images_are_not_fetched_again(self):
        old = {'id': 'one', 'originalUrl': 'https://example.com/one', 'imageUrl': 'https://example.com/one.jpg'}
        items = [dict(old, imageUrl=None)]
        fill_article_images(items, [old], lambda url: self.fail('Cached image was refetched'), 1790500000000)
        self.assertEqual(items[0]['imageUrl'], old['imageUrl'])

    def test_failed_preview_keeps_story_and_retries_after_a_day(self):
        now = 1790500000000
        def offline(url): raise TimeoutError('offline')
        article = {'id': 'one', 'originalUrl': 'https://example.com/one', 'imageUrl': None}
        fill_article_images([article], [], offline, now)
        self.assertIsNone(article['imageUrl'])
        fresh = dict(article, imageCheckedAtEpochMillis=0)
        fill_article_images([fresh], [article], lambda url: self.fail('Negative cache ignored'), now + 3600000)
        fill_article_images([fresh], [article], lambda url: 'https://example.com/one.jpg', now + 86400000)
        self.assertEqual(fresh['imageUrl'], 'https://example.com/one.jpg')


if __name__ == "__main__":
    unittest.main()
