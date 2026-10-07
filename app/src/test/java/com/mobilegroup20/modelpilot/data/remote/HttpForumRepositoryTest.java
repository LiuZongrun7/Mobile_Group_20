package com.mobilegroup20.modelpilot.data.remote;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import com.mobilegroup20.modelpilot.contract.model.*;
import com.mobilegroup20.modelpilot.data.repository.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.*;
import org.junit.*;
import static org.junit.Assert.*;

public class HttpForumRepositoryTest {
    @Rule public InstantTaskExecutorRule executor = new InstantTaskExecutorRule();
    private MockWebServer server;
    private Session session;
    private HttpForumRepository repository;
    private static final class Session implements SessionProvider {
        volatile String token = "token-a", id = "account-a";
        public String token() { return token; }
        public String accountId() { return id; }
    }
    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start(); session = new Session();
        repository = new HttpForumRepository(server.url("/api/").toString(), session);
    }
    @After public void tearDown() throws Exception { server.shutdown(); }
    private static MockResponse json(String body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body); }
    private <T> ForumResult<T> await(LiveData<ForumResult<T>> source) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<ForumResult<T>> value = new AtomicReference<>();
        Observer<ForumResult<T>> observer = r -> { if (r.status != ForumResult.Status.LOADING) { value.set(r); latch.countDown(); } };
        source.observeForever(observer);
        try { assertTrue("Request timed out", latch.await(5, TimeUnit.SECONDS)); return value.get(); }
        finally { source.removeObserver(observer); }
    }
    @Test public void feedSendsLiveAuthenticationAndOpaquePaginationCursor() throws Exception {
        server.enqueue(json("{\"items\":[{\"id\":\"p1\",\"images\":[],\"likeCount\":3,\"likedByMe\":true}],\"nextCursor\":\"next\"}"));
        ForumResult<ForumPage<ForumPost>> result = await(repository.posts("a/b +"));
        assertEquals(ForumResult.Status.SUCCESS, result.status); assertTrue(result.data.items.get(0).likedByMe);
        RecordedRequest request = server.takeRequest();
        assertEquals("Bearer token-a", request.getHeader("Authorization"));
        assertEquals("a/b +", request.getRequestUrl().queryParameter("cursor"));
        assertEquals("20", request.getRequestUrl().queryParameter("limit"));
        session.token = "token-b"; session.id = "account-b";
        server.enqueue(json("{\"items\":[],\"nextCursor\":null}")); await(repository.news(null));
        assertEquals("Bearer token-b", server.takeRequest().getHeader("Authorization"));
    }
    @Test public void staleResponseAfterAccountSwitchIsRejected() throws Exception {
        server.enqueue(json("{\"items\":[{\"id\":\"private-context\"}]}").setBodyDelay(250, TimeUnit.MILLISECONDS));
        LiveData<ForumResult<ForumPage<ForumPost>>> request = repository.posts(null);
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); session.id = "account-b"; session.token = "token-b";
        assertEquals("SESSION_CHANGED", await(request).code);
    }
    @Test public void searchEncodesQueryAndTrendingUsesSameSession() throws Exception {
        server.enqueue(json("{\"items\":[],\"nextCursor\":null}"));
        await(repository.news(null, "智能体 & Claude"));
        RecordedRequest search = server.takeRequest();
        assertEquals("智能体 & Claude", search.getRequestUrl().queryParameter("q"));
        server.enqueue(json("{\"windowDays\":7,\"topics\":[{\"name\":\"Claude\",\"query\":\"claude\",\"rank\":1,\"articleCount\":3,\"sourceCount\":2}],\"posts\":[]}"));
        ForumTrending result = await(repository.trending()).data;
        assertEquals(3, result.topics.get(0).articleCount);
        RecordedRequest trending = server.takeRequest(); assertEquals("/api/forum/trending", trending.getPath());
        assertEquals("Bearer token-a", trending.getHeader("Authorization"));
    }
    @Test public void expiredSessionAndMissingConfigurationAreNotEmptySuccesses() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)); assertEquals("UNAUTHORIZED", await(repository.posts(null)).code);
        assertEquals("NOT_CONFIGURED", await(new HttpForumRepository("", session).news(null)).code);
        session.token = null; assertEquals("UNAUTHORIZED", await(repository.posts(null)).code);
        assertEquals(1, server.getRequestCount());
    }
    @Test public void imagePostSubmitsOnlyEditableFieldsAndStableRetryKey() throws Exception {
        server.enqueue(json("{\"id\":\"photo-1\",\"url\":\"https://example.com/photo.jpg\"}"));
        ForumImage image = await(repository.uploadImage(new byte[]{1, 2, 3}, "image/jpeg")).data;
        RecordedRequest upload = server.takeRequest(); assertEquals("/api/forum/images", upload.getPath());
        assertTrue(upload.getHeader("Content-Type").startsWith("multipart/form-data"));
        assertTrue(upload.getBody().readUtf8().contains("name=\"image\""));
        PostDraft draft = new PostDraft(); draft.imageIds.add(image.id);
        server.enqueue(json("{\"id\":\"post-1\",\"authorUid\":\"account-a\",\"images\":[]}"));
        assertEquals("post-1", await(repository.publish(draft, "same-retry-key")).data.id);
        RecordedRequest publish = server.takeRequest(); String body = publish.getBody().readUtf8();
        assertEquals("same-retry-key", publish.getHeader("Idempotency-Key"));
        assertTrue(body.contains("photo-1")); assertFalse(body.contains("authorUid")); assertFalse(body.contains("source")); assertFalse(body.contains("likeCount"));
    }
    @Test public void likeAndUnlikeUseIdempotentMethods() throws Exception {
        server.enqueue(json("{\"id\":\"p1\",\"likedByMe\":true,\"likeCount\":1}"));
        assertTrue(await(repository.like("p1", true)).data.likedByMe); assertEquals("PUT", server.takeRequest().getMethod());
        server.enqueue(json("{\"id\":\"p1\",\"likedByMe\":false,\"likeCount\":0}"));
        assertEquals(0, await(repository.like("p1", false)).data.likeCount); assertEquals("DELETE", server.takeRequest().getMethod());
    }
    @Test public void commentsRetainPostPathAndRetryIdentity() throws Exception {
        server.enqueue(json("{\"id\":\"r1\",\"postId\":\"p1\",\"body\":\"Hello\"}"));
        assertEquals("r1", await(repository.reply("p1", " Hello ", "comment-key")).data.id);
        RecordedRequest request = server.takeRequest(); assertEquals("/api/forum/posts/p1/replies", request.getPath());
        assertEquals("comment-key", request.getHeader("Idempotency-Key")); assertEquals("{\"body\":\"Hello\"}", request.getBody().readUtf8());
    }
    @Test public void invalidDraftsNeverReachTheNetwork() throws Exception {
        assertEquals("EMPTY_POST", await(repository.publish(new PostDraft(), "empty")).code);
        assertEquals("EMPTY_REPLY", await(repository.reply("p1", "   ", "empty")).code);
        assertEquals("INVALID_IMAGE", await(repository.uploadImage(new byte[0], "image/jpeg")).code);
        PostDraft oversized = new PostDraft(); for (int i = 0; i < 10; i++) oversized.imageIds.add("image" + i);
        assertEquals("EMPTY_POST", await(repository.publish(oversized, "large")).code); assertEquals(0, server.getRequestCount());
    }
}
