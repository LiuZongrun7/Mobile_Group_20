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
    private static final class FakeRepository implements ForumFeedRepository {
        String identity = "account-a";
        List<String> cursors = new ArrayList<>();
        List<MutableLiveData<ForumResult<ForumPage<ForumPost>>>> postRequests = new ArrayList<>();
        List<MutableLiveData<ForumResult<ForumPage<NewsArticle>>>> newsRequests = new ArrayList<>();
        public String sessionIdentity() { return identity; }
        public LiveData<ForumResult<ForumPage<ForumPost>>> posts(String cursor) {
            cursors.add(cursor); MutableLiveData<ForumResult<ForumPage<ForumPost>>> data = new MutableLiveData<>(ForumResult.loading()); postRequests.add(data); return data;
        }
        public LiveData<ForumResult<ForumPage<NewsArticle>>> news(String cursor) {
            MutableLiveData<ForumResult<ForumPage<NewsArticle>>> data = new MutableLiveData<>(ForumResult.loading()); newsRequests.add(data); return data;
        }
        public LiveData<ForumResult<ForumPost>> post(String id) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumPage<ForumReply>>> replies(String id, String cursor) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumImage>> uploadImage(byte[] bytes, String mime) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumPost>> publish(PostDraft draft, String key) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumReply>> reply(String id, String body, String key) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumPost>> like(String id, boolean liked) { throw new UnsupportedOperationException(); }
    }
}
