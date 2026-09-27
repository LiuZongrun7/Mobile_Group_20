package com.mobilegroup20.tokentrail.ui.forum;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.*;
import com.mobilegroup20.tokentrail.contract.model.*;
import com.mobilegroup20.tokentrail.data.repository.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.*;
import static org.junit.Assert.*;

public class PostDetailViewModelTest {
    @Rule public InstantTaskExecutorRule executor = new InstantTaskExecutorRule();
    private Fake repository;
    private PostDetailViewModel model;
    private final Observer<Object> activate = ignored -> { };
    @Before public void setUp() {
        repository = new Fake(); model = new PostDetailViewModel(new SavedStateHandle(), () -> repository);
        model.requests().observeForever(activate); model.open("p1");
    }
    @After public void tearDown() { model.requests().removeObserver(activate); }
    private static ForumReply reply(String id, long epoch) { ForumReply r = new ForumReply(); r.id = id; r.createdAtEpochMillis = epoch; return r; }
    @Test public void commentPublishedDuringPageLoadSurvivesOlderPageResponse() {
        model.send("new comment");
        repository.sent.setValue(ForumResult.success(reply("new", 200)));
        assertEquals("new", model.comments.getValue().get(0).id);
        ForumPage<ForumReply> page = new ForumPage<>(); page.items.add(reply("old", 100)); page.nextCursor = "next";
        repository.pages.get(0).setValue(ForumResult.success(page));
        assertEquals("old", model.comments.getValue().get(0).id); assertEquals("new", model.comments.getValue().get(1).id);
        model.loadComments(false);
        ForumPage<ForumReply> next = new ForumPage<>(); next.items.add(reply("middle", 150)); next.items.add(reply("new", 200));
        repository.pages.get(1).setValue(ForumResult.success(next));
        assertEquals(3, model.comments.getValue().size()); assertEquals("middle", model.comments.getValue().get(1).id);
    }
    @Test public void unchangedFailedCommentReusesKeyAndKeepsDraft() {
        model.saved.set("body", "draft"); model.send("draft");
        String key = repository.key; repository.sent.setValue(ForumResult.error("NETWORK"));
        assertEquals("draft", model.saved.get("body")); model.send("draft"); assertEquals(key, repository.key);
        repository.sent.setValue(ForumResult.error("NETWORK")); model.send("edited draft"); assertNotEquals(key, repository.key);
    }
    @Test public void switchingAccountDoesNotPublishThePreviousDraft() {
        repository.identity = "account-b"; model.send("old draft");
        assertEquals("SESSION_CHANGED", model.error.getValue()); assertNull(repository.key);
        assertTrue(model.comments.getValue().isEmpty());
    }
    private static final class Fake implements ForumFeedRepository {
        String identity = "account-a", key;
        List<MutableLiveData<ForumResult<ForumPage<ForumReply>>>> pages = new ArrayList<>();
        MutableLiveData<ForumResult<ForumReply>> sent;
        public String sessionIdentity() { return identity; }
        public LiveData<ForumResult<ForumPost>> post(String id) { ForumPost p = new ForumPost(); p.id = id; return new MutableLiveData<>(ForumResult.success(p)); }
        public LiveData<ForumResult<ForumPage<ForumReply>>> replies(String id, String cursor) {
            MutableLiveData<ForumResult<ForumPage<ForumReply>>> data = new MutableLiveData<>(ForumResult.loading()); pages.add(data); return data;
        }
        public LiveData<ForumResult<ForumReply>> reply(String id, String body, String key) {
            this.key = key; sent = new MutableLiveData<>(ForumResult.loading()); return sent;
        }
        public LiveData<ForumResult<ForumPage<ForumPost>>> posts(String cursor) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumPage<NewsArticle>>> news(String cursor) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumImage>> uploadImage(byte[] bytes, String mime) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumPost>> publish(PostDraft draft, String key) { throw new UnsupportedOperationException(); }
        public LiveData<ForumResult<ForumPost>> like(String id, boolean liked) { throw new UnsupportedOperationException(); }
    }
}
