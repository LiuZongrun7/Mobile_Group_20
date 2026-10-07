package com.mobilegroup20.modelpilot.ui.forum;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.*;
import androidx.lifecycle.Observer;
import com.mobilegroup20.modelpilot.contract.model.*;
import com.mobilegroup20.modelpilot.data.repository.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class ForumViewModelTest {
    @Rule public InstantTaskExecutorRule executor = new InstantTaskExecutorRule();
    private FakeRepository repository;
    private ForumViewModel model;
    private final Observer<Object> activate = ignored -> { };
    @Before public void setUp() {
        repository = new FakeRepository(); model = new ForumViewModel(() -> repository);
        model.requests().observeForever(activate);
    }
    @After public void tearDown() { model.requests().removeObserver(activate); }
    private static ForumPost post(String id) { ForumPost p = new ForumPost(); p.id = id; return p; }
    private static ForumPage<ForumPost> page(String cursor, ForumPost... posts) {
        ForumPage<ForumPost> page = new ForumPage<>(); page.items.addAll(Arrays.asList(posts)); page.nextCursor = cursor; return page;
    }
    @Test public void independentTabsAndPagingAvoidDuplicatePosts() {
        model.selectedTab = 1; model.ensureLoaded();
        repository.postRequests.get(0).setValue(ForumResult.success(page("next", post("a"))));
        model.load(false); assertEquals("next", repository.cursors.get(1));
        repository.postRequests.get(1).setValue(ForumResult.success(page(null, post("a"), post("b"))));
        assertEquals(2, model.posts.items.size());
        model.selectedTab = 0; model.ensureLoaded();
        assertEquals(1, repository.newsRequests.size()); assertEquals(2, model.posts.items.size());
        model.selectedTab = 1; model.load(false); assertEquals(2, repository.postRequests.size());
    }
    @Test public void failedRefreshKeepsVisibleContentAndSupportsRetry() {
        model.selectedTab = 1; model.load(true);
        repository.postRequests.get(0).setValue(ForumResult.success(page(null, post("a"))));
        model.load(true); repository.postRequests.get(1).setValue(ForumResult.error("NETWORK"));
        assertEquals("a", model.posts.items.get(0).id); assertEquals("NETWORK", model.posts.error); assertFalse(model.posts.loading);
        model.load(true); repository.postRequests.get(2).setValue(ForumResult.success(page(null, post("new"))));
        assertEquals("new", model.posts.items.get(0).id); assertNull(model.posts.error);
    }
    @Test public void accountChangeClearsBothFeedsAndRejectsPendingOldResults() {
        model.selectedTab = 1; model.load(true);
        repository.identity = "account-b"; model.syncSession();
        assertTrue(model.posts.items.isEmpty()); assertTrue(model.news.items.isEmpty());
        repository.postRequests.get(0).setValue(ForumResult.success(page(null, post("stale"))));
        assertTrue(model.posts.items.isEmpty());
        repository.postRequests.get(1).setValue(ForumResult.success(page(null, post("current"))));
        assertEquals("current", model.posts.items.get(0).id);
    }
    @Test public void successfulPublishInvalidatesOlderFeedRequestAndSelectsCommunity() {
        model.selectedTab = 1; model.load(true);
        model.published(post("created"));
        repository.postRequests.get(0).setValue(ForumResult.success(page(null, post("stale"))));
        assertEquals("created", model.posts.items.get(0).id); assertEquals(1, model.selectedTab);
        repository.postRequests.get(1).setValue(ForumResult.success(page(null, post("created"), post("older"))));
        assertEquals(2, model.posts.items.size());
    }
    @Test public void changingSearchRejectsOldResultsAndRestartsPagination() {
        model.selectedTab = 1; model.search(" Claude ");
        assertEquals("Claude", repository.queries.get(0));
        model.search("Gemini");
        repository.postRequests.get(0).setValue(ForumResult.success(page("old-cursor", post("stale"))));
        assertTrue(model.posts.items.isEmpty());
        repository.postRequests.get(1).setValue(ForumResult.success(page("new-cursor", post("current"))));
        model.load(false);
        assertEquals("new-cursor", repository.cursors.get(2)); assertEquals("Gemini", repository.queries.get(2));
        model.selectedTab = 0; model.ensureLoaded();
        assertEquals("Gemini", repository.newsQueries.get(0));
        model.search(""); assertEquals("", repository.newsQueries.get(1));
    }
    @Test public void trendingHasIndependentCacheAndTopicSearchSelectsNews() {
        model.selectedTab = 2; model.ensureLoaded();
        ForumTrending response = new ForumTrending(); response.windowDays = 7;
        repository.trendRequests.get(0).setValue(ForumResult.success(response));
        model.ensureLoaded(); assertEquals(1, repository.trendRequests.size());
        model.search("agent"); assertEquals(0, model.selectedTab);
        assertEquals("agent", repository.newsQueries.get(0));
        assertEquals(1, model.trending.items.size());
        model.selectedTab = 2; model.load(true);
        repository.identity = "other-account"; model.syncSession();
        repository.trendRequests.get(1).setValue(ForumResult.success(response));
        assertTrue(model.trending.items.isEmpty());
    }
    @Test public void successfulPublishExitsSearchSoNewPostIsVisible() {
        model.selectedTab = 1; model.search("missing"); model.published(post("created"));
        assertEquals("", model.query);
        assertEquals("", repository.queries.get(1));
        assertEquals("created", model.posts.items.get(0).id);
    }
    private static final class FakeRepository implements ForumFeedRepository {
        String identity = "account-a";
        List<String> cursors = new ArrayList<>();
        List<String> queries = new ArrayList<>(), newsQueries = new ArrayList<>();
        List<MutableLiveData<ForumResult<ForumTrending>>> trendRequests = new ArrayList<>();
        List<MutableLiveData<ForumResult<ForumPage<ForumPost>>>> postRequests = new ArrayList<>();
        List<MutableLiveData<ForumResult<ForumPage<NewsArticle>>>> newsRequests = new ArrayList<>();
        public String sessionIdentity() { return identity; }
        public LiveData<ForumResult<ForumPage<ForumPost>>> posts(String cursor, String query) {
            cursors.add(cursor); queries.add(query); MutableLiveData<ForumResult<ForumPage<ForumPost>>> data = new MutableLiveData<>(ForumResult.loading()); postRequests.add(data); return data;
        }
        public LiveData<ForumResult<ForumPage<NewsArticle>>> news(String cursor, String query) {
            newsQueries.add(query);
            MutableLiveData<ForumResult<ForumPage<NewsArticle>>> data = new MutableLiveData<>(ForumResult.loading()); newsRequests.add(data); return data;
        }
        public LiveData<ForumResult<ForumTrending>> trending() {
            MutableLiveData<ForumResult<ForumTrending>> data = new MutableLiveData<>(ForumResult.loading()); trendRequests.add(data); return data;
        }
        public LiveData<ForumResult<ForumPost>> post(String id) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumPage<ForumReply>>> replies(String id, String cursor) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumImage>> uploadImage(byte[] bytes, String mime) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumPost>> publish(PostDraft draft, String key) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumReply>> reply(String id, String body, String key) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumPost>> like(String id, boolean liked) { throw new UnsupportedOperationException(); }
    }
}
