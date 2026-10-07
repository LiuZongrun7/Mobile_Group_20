package com.mobilegroup20.modelpilot.data.remote;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import com.mobilegroup20.modelpilot.contract.model.*;
import com.mobilegroup20.modelpilot.contract.tool.*;
import com.mobilegroup20.modelpilot.data.repository.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;

public final class HttpForumRepository implements ForumFeedRepository, ForumRepository {
    private final ForumApi api;
    private final SessionProvider session;
    public HttpForumRepository(String baseUrl, SessionProvider session) {
        this.session = session;
        api = baseUrl == null || baseUrl.isEmpty() ? null : new Retrofit.Builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/")
                .client(new OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false).build())
                .addConverterFactory(GsonConverterFactory.create()).build().create(ForumApi.class);
    }
    public String sessionIdentity() { return session.accountId(); }
    private interface Request<T> { Call<T> create(String auth); }
    private <T> LiveData<ForumResult<T>> request(Request<T> request) {
        MutableLiveData<ForumResult<T>> result = new MutableLiveData<>(ForumResult.loading());
        String token = session.token();
        String identity = session.accountId();
        if (api == null) { result.setValue(ForumResult.error("NOT_CONFIGURED")); return result; }
        if (token == null || token.isEmpty() || identity == null) {
            result.setValue(ForumResult.error("UNAUTHORIZED")); return result;
        }
        request.create("Bearer " + token).enqueue(new Callback<T>() {
            private boolean changed() { return !Objects.equals(identity, session.accountId()) || !Objects.equals(token, session.token()); }
            public void onResponse(Call<T> call, Response<T> response) {
                if (changed()) { result.setValue(ForumResult.error("SESSION_CHANGED")); return; }
                if (response.isSuccessful() && response.body() != null) result.setValue(ForumResult.success(response.body()));
                else result.setValue(ForumResult.error(response.code() == 401 ? "UNAUTHORIZED" : response.code() == 404 ? "NOT_FOUND" : response.code() == 413 ? "IMAGE_TOO_LARGE" : "HTTP_" + response.code()));
            }
            public void onFailure(Call<T> call, Throwable failure) {
                result.setValue(ForumResult.error(changed() ? "SESSION_CHANGED" : "NETWORK"));
            }
        });
        return result;
    }
    public LiveData<ForumResult<ForumPage<ForumPost>>> posts(String cursor, String query) { return request(a -> api.posts(a, cursor, 20, query)); }
    public LiveData<ForumResult<ForumPage<NewsArticle>>> news(String cursor, String query) { return request(a -> api.news(a, cursor, 20, query)); }
    public LiveData<ForumResult<ForumTrending>> trending() { return request(a -> api.trending(a)); }
    public LiveData<ForumResult<ForumPost>> post(String id) { return request(a -> api.post(a, id)); }
    public LiveData<ForumResult<ForumPage<ForumReply>>> replies(String id, String cursor) { return request(a -> api.replies(a, id, cursor, 20)); }
    public LiveData<ForumResult<ForumImage>> uploadImage(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0 || bytes.length > 10 * 1024 * 1024 || mimeType == null || !mimeType.startsWith("image/"))
            return new MutableLiveData<>(ForumResult.error("INVALID_IMAGE"));
        RequestBody body = RequestBody.create(MediaType.parse(mimeType), bytes);
        return request(a -> api.upload(a, MultipartBody.Part.createFormData("image", "image", body)));
    }
    public LiveData<ForumResult<ForumPost>> publish(PostDraft draft, String requestId) {
        if (draft == null || draft.imageIds == null || draft.imageIds.size() > 9 ||
                ((draft.body == null || draft.body.trim().isEmpty()) && draft.imageIds.isEmpty()))
            return new MutableLiveData<>(ForumResult.error("EMPTY_POST"));
        return request(a -> api.publish(a, requestId, draft));
    }
    public LiveData<ForumResult<ForumReply>> reply(String id, String body, String requestId) {
        if (body == null || body.trim().isEmpty()) return new MutableLiveData<>(ForumResult.error("EMPTY_REPLY"));
        return request(a -> api.reply(a, id, requestId, new ForumApi.ReplyDraft(body.trim())));
    }
    public LiveData<ForumResult<ForumPost>> like(String id, boolean liked) { return request(a -> liked ? api.like(a, id) : api.unlike(a, id)); }
    // Compatibility for the existing read-only agent contract. Errors never become fabricated data.
    private <T> LiveData<T> legacy(LiveData<ForumResult<T>> source) {
        return Transformations.map(source, r -> r.status == ForumResult.Status.SUCCESS ? r.data : null);
    }
    public LiveData<List<ForumPost>> officialPosts(int limit) { return legacy(request(a -> api.official(a, limit))); }
    public LiveData<List<ForumPost>> hotPosts(String since, int limit) { return legacy(request(a -> api.hot(a, since, limit))); }
    public LiveData<List<ForumPost>> myPosts(String uid) { return legacy(request(a -> api.mine(a))); }
    public LiveData<ForumHighlights> highlights(String filter, String since, int limit) { return legacy(request(a -> api.highlights(a, filter, since, limit))); }
    public LiveData<MyThreads> myThreads(String uid, String since, int limit) { return legacy(request(a -> api.threads(a, since, limit))); }
    public LiveData<List<ForumReply>> repliesOf(String id) { return Transformations.map(replies(id, null), r -> r.status == ForumResult.Status.SUCCESS ? r.data.items : null); }
    public LiveData<ForumPost> createPost(ForumPost draft) {
        PostDraft body = new PostDraft(); body.title = draft.title; body.body = draft.body;
        for (ForumImage image : draft.images) body.imageIds.add(image.id);
        return legacy(publish(body, UUID.randomUUID().toString()));
    }
    public LiveData<ForumReply> createReply(ForumReply draft) { return legacy(reply(draft.postId, draft.body, UUID.randomUUID().toString())); }
}
