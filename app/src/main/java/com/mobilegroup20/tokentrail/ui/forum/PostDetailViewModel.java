package com.mobilegroup20.tokentrail.ui.forum;

import androidx.lifecycle.*;
import com.mobilegroup20.tokentrail.contract.model.*;
import com.mobilegroup20.tokentrail.data.RepositoryProvider;
import com.mobilegroup20.tokentrail.data.repository.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PostDetailViewModel extends ViewModel {
    public final MutableLiveData<ForumPost> post = new MutableLiveData<>();
    public final MutableLiveData<List<ForumReply>> comments = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<String> error = new MutableLiveData<>();
    public final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    public final MutableLiveData<Boolean> sending = new MutableLiveData<>(false);
    public final MutableLiveData<Boolean> liking = new MutableLiveData<>(false);
    public final SavedStateHandle saved;
    public final MutableLiveData<Integer> sent = new MutableLiveData<>(0);
    private final MediatorLiveData<Object> requests = new MediatorLiveData<>();
    private final Supplier<ForumFeedRepository> provider;
    private ForumFeedRepository repository;
    private String identity;
    private final Map<String, ForumReply> createdReplies = new HashMap<>();
    private String id, cursor;
    private boolean commentsLoaded;
    private int generation;
    public PostDetailViewModel(SavedStateHandle saved) { this(saved, RepositoryProvider::forumFeed); }
    public PostDetailViewModel(SavedStateHandle saved, Supplier<ForumFeedRepository> provider) {
        this.saved = saved; this.provider = provider; repository = provider.get(); identity = repository.sessionIdentity();
        String savedIdentity = saved.get("accountId");
        if (saved.contains("accountId") && !Objects.equals(savedIdentity, identity)) saved.set("body", "");
        saved.set("accountId", identity);
    }
    public LiveData<Object> requests() { return requests; }
    public boolean hasMore() { return cursor != null; }
    public void open(String id) {
        this.id = id;
        ForumFeedRepository current = provider.get();
        if (repository != current || !Objects.equals(identity, current.sessionIdentity())) {
            repository = current; identity = current.sessionIdentity(); generation++;
            post.setValue(null); comments.setValue(new ArrayList<>()); cursor = null; commentsLoaded = false;
            loading.setValue(false); sending.setValue(false); liking.setValue(false); saved.set("body", "");
            saved.set("accountId", identity); createdReplies.clear();
        }
        if (post.getValue() == null && !Boolean.TRUE.equals(loading.getValue())) refresh();
    }
    public void refresh() {
        if (Boolean.TRUE.equals(loading.getValue())) return;
        error.setValue(null); int requestGeneration = generation;
        watch(repository.post(id), result -> {
            if (requestGeneration != generation) return;
            if (result.status == ForumResult.Status.SUCCESS) post.setValue(result.data); else error.setValue(result.code);
        });
        loadComments(true);
    }
    public void loadComments(boolean reset) {
        if (Boolean.TRUE.equals(loading.getValue()) || (!reset && commentsLoaded && cursor == null)) return;
        loading.setValue(true); int requestGeneration = generation;
        watch(repository.replies(id, reset ? null : cursor), result -> {
            if (requestGeneration != generation) return;
            loading.setValue(false);
            if (result.status == ForumResult.Status.SUCCESS && result.data.items != null) {
                List<ForumReply> all = reset ? new ArrayList<>() : new ArrayList<>(comments.getValue());
                Set<String> existing = new HashSet<>(); for (ForumReply reply : all) existing.add(reply.id);
                for (ForumReply reply : result.data.items) if (reply != null && reply.id != null && existing.add(reply.id)) all.add(reply);
                for (ForumReply reply : createdReplies.values()) if (existing.add(reply.id)) all.add(reply);
                sortComments(all);
                cursor = result.data.nextCursor; commentsLoaded = true; comments.setValue(all);
            } else error.setValue(result.code == null ? "BAD_RESPONSE" : result.code);
        });
    }
    public void send(String body) {
        if (Boolean.TRUE.equals(sending.getValue())) return;
        if (sessionChanged()) { open(id); error.setValue("SESSION_CHANGED"); return; }
        if (body.trim().isEmpty()) { error.setValue("EMPTY_REPLY"); return; }
        String oldBody = saved.get("pendingBody"); String key = saved.get("replyKey");
        if (!body.equals(oldBody) || key == null) key = UUID.randomUUID().toString();
        saved.set("pendingBody", body); saved.set("replyKey", key); sending.setValue(true); error.setValue(null);
        int requestGeneration = generation;
        watch(repository.reply(id, body, key), result -> {
            if (requestGeneration != generation) return;
            sending.setValue(false);
            if (result.status == ForumResult.Status.SUCCESS) {
                createdReplies.put(result.data.id, result.data);
                List<ForumReply> all = new ArrayList<>(comments.getValue());
                all.removeIf(r -> Objects.equals(r.id, result.data.id)); all.add(result.data); sortComments(all); comments.setValue(all);
                saved.set("body", ""); saved.set("pendingBody", null); saved.set("replyKey", null); sent.setValue(sent.getValue() + 1);
                // Keep only authoritative, chronologically ordered pages; refresh on success.
                if (!Boolean.TRUE.equals(loading.getValue())) loadComments(true);
                watch(repository.post(id), updated -> { if (requestGeneration == generation && updated.status == ForumResult.Status.SUCCESS) post.setValue(updated.data); });
            } else error.setValue(result.code);
        });
    }
    public void like() {
        if (post.getValue() == null || Boolean.TRUE.equals(liking.getValue())) return;
        if (sessionChanged()) { open(id); error.setValue("SESSION_CHANGED"); return; }
        liking.setValue(true); int requestGeneration = generation;
        watch(repository.like(id, !post.getValue().likedByMe), result -> {
            if (requestGeneration != generation) return;
            liking.setValue(false);
            if (result.status == ForumResult.Status.SUCCESS) post.setValue(result.data); else error.setValue(result.code);
        });
    }
    private <T> void watch(LiveData<ForumResult<T>> source, Consumer<ForumResult<T>> done) {
        requests.addSource(source, r -> {
            if (r.status != ForumResult.Status.LOADING) {
                requests.removeSource(source);
                if (sessionChanged()) open(id);
                done.accept(r);
            }
        });
    }
    private boolean sessionChanged() { return repository != provider.get() || !Objects.equals(identity, repository.sessionIdentity()); }
    private static void sortComments(List<ForumReply> all) {
        all.sort(Comparator.comparingLong((ForumReply r) -> r.createdAtEpochMillis).thenComparing(r -> r.id == null ? "" : r.id));
    }
}
