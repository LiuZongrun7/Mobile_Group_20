package com.mobilegroup20.tokentrail.ui.forum;

import androidx.lifecycle.*;
import com.mobilegroup20.tokentrail.contract.model.*;
import com.mobilegroup20.tokentrail.data.RepositoryProvider;
import com.mobilegroup20.tokentrail.data.repository.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Separate feed cursors and scroll states; request generations reject obsolete refreshes. */
public class ForumViewModel extends ViewModel {
    public static final class Feed<T> {
        public final List<T> items = new ArrayList<>();
        public String cursor;
        public String error;
        public boolean loading;
        public boolean loaded;
        int generation;
    }
    public final Feed<NewsArticle> news = new Feed<>();
    public final Feed<ForumPost> posts = new Feed<>();
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    private final MediatorLiveData<Object> requests = new MediatorLiveData<>();
    public int selectedTab;
    public final android.os.Parcelable[] scrollStates = new android.os.Parcelable[2];
    private ForumFeedRepository repository;
    private final Supplier<ForumFeedRepository> provider;
    private String identity;
    private final Set<String> liking = new HashSet<>();

    public ForumViewModel() { this(RepositoryProvider::forumFeed); }
    public ForumViewModel(Supplier<ForumFeedRepository> provider) {
        this.provider = provider; repository = provider.get(); identity = repository.sessionIdentity();
    }
    /** Observe requests while this ViewModel is active, even when a detail screen is open. */
    public LiveData<Object> requests() { return requests; }
    public void syncSession() {
        ForumFeedRepository current = provider.get();
        if (current != repository || !Objects.equals(identity, current.sessionIdentity())) {
            repository = current; identity = current.sessionIdentity();
            clear(news); clear(posts); liking.clear();
            Arrays.fill(scrollStates, null); notifyChange();
        }
        ensureLoaded();
    }
    private void clear(Feed<?> feed) { feed.generation++; feed.items.clear(); feed.cursor = null; feed.error = null; feed.loading = false; feed.loaded = false; }
    public void ensureLoaded() { if (!(selectedTab == 0 ? news.loaded || news.loading : posts.loaded || posts.loading)) load(true); }
    public void load(boolean refresh) {
        if (selectedTab == 0) load(news, refresh, c -> repository.news(c), a -> a.id);
        else load(posts, refresh, c -> repository.posts(c), a -> a.id);
    }
    private interface Fetch<T> { LiveData<ForumResult<ForumPage<T>>> fetch(String cursor); }
    private interface Id<T> { String id(T item); }
    private <T> void load(Feed<T> feed, boolean refresh, Fetch<T> fetch, Id<T> id) {
        if (feed.loading || (!refresh && feed.cursor == null)) return;
        int generation = ++feed.generation;
        feed.loading = true; feed.error = null; notifyChange();
        watch(fetch.fetch(refresh ? null : feed.cursor), r -> {
            if (generation != feed.generation) return;
            feed.loading = false;
            if (r.status == ForumResult.Status.SUCCESS && r.data.items != null) {
                if (refresh) feed.items.clear();
                Set<String> existing = new HashSet<>();
                for (T item : feed.items) existing.add(id.id(item));
                for (T item : r.data.items) if (item != null && id.id(item) != null && existing.add(id.id(item))) feed.items.add(item);
                feed.cursor = r.data.nextCursor == null || r.data.nextCursor.isEmpty() ? null : r.data.nextCursor;
                feed.loaded = true;
            } else feed.error = r.code == null ? "BAD_RESPONSE" : r.code;
            notifyChange();
        });
    }
    public void toggleLike(ForumPost post) {
        if (!liking.add(post.id)) return;
        int generation = posts.generation;
        notifyChange();
        watch(repository.like(post.id, !post.likedByMe), result -> {
            liking.remove(post.id);
            if (generation != posts.generation) return;
            if (result.status == ForumResult.Status.SUCCESS) replace(result.data);
            else posts.error = result.code;
            notifyChange();
        });
    }
    public boolean isLiking(String id) { return liking.contains(id); }
    public void replace(ForumPost post) {
        for (int i = 0; i < posts.items.size(); i++) if (Objects.equals(post.id, posts.items.get(i).id)) posts.items.set(i, post);
        notifyChange();
    }
    public void published(ForumPost post) {
        selectedTab = 1;
        posts.generation++; posts.loading = false; posts.error = null;
        posts.items.removeIf(p -> Objects.equals(p.id, post.id)); posts.items.add(0, post);
        scrollStates[1] = null;
        notifyChange();
        // Refresh from the authoritative feed after retaining the successful post locally.
        load(true);
    }
    private void notifyChange() { changes.setValue(changes.getValue() + 1); }
    private <T> void watch(LiveData<ForumResult<T>> source, Consumer<ForumResult<T>> done) {
        requests.addSource(source, r -> {
            if (r.status == ForumResult.Status.LOADING) return;
            requests.removeSource(source);
            ForumFeedRepository current = provider.get();
            if (current != repository || !Objects.equals(identity, current.sessionIdentity())) syncSession();
            done.accept(r);
        });
    }
}
