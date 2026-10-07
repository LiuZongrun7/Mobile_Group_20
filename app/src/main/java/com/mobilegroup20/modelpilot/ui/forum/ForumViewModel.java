package com.mobilegroup20.modelpilot.ui.forum;

import androidx.lifecycle.*;
import com.mobilegroup20.modelpilot.contract.model.*;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.data.repository.*;
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
    public final Feed<ForumTrending> trending = new Feed<>();
    public String query = "";
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    private final MediatorLiveData<Object> requests = new MediatorLiveData<>();
    public int selectedTab;
    public final android.os.Parcelable[] scrollStates = new android.os.Parcelable[3];
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
            clear(news); clear(posts); clear(trending); liking.clear();
            Arrays.fill(scrollStates, null); notifyChange();
        }
        ensureLoaded();
    }
    private void clear(Feed<?> feed) { feed.generation++; feed.items.clear(); feed.cursor = null; feed.error = null; feed.loading = false; feed.loaded = false; }
    public Feed<?> active() { return selectedTab == 0 ? news : selectedTab == 1 ? posts : trending; }
    public void ensureLoaded() { if (!active().loaded && !active().loading) load(true); }
    public void search(String text) {
        String next = text.trim().replaceAll("\\s+", " ");
        if (!Objects.equals(query, next)) {
            query = next; clear(news); clear(posts);
            scrollStates[0] = null; scrollStates[1] = null;
        }
        if (selectedTab == 2) selectedTab = 0;
        notifyChange(); ensureLoaded();
    }
    public void load(boolean refresh) {
        final String requestedQuery = query;
        if (selectedTab == 0) load(news, refresh, c -> repository.news(c, requestedQuery), a -> a.id);
        else if (selectedTab == 1) load(posts, refresh, c -> repository.posts(c, requestedQuery), a -> a.id);
        else loadTrending();
    }
    private void loadTrending() {
        if (trending.loading) return;
        int generation = ++trending.generation;
        trending.loading = true; trending.error = null; notifyChange();
        watch(repository.trending(), result -> {
            if (generation != trending.generation) return;
            trending.loading = false;
            if (result.status == ForumResult.Status.SUCCESS && result.data.topics != null && result.data.posts != null) {
                trending.items.clear(); trending.items.add(result.data); trending.loaded = true;
            } else trending.error = result.code == null ? "BAD_RESPONSE" : result.code;
            notifyChange();
        });
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
        int trendingGeneration = trending.generation;
        boolean fromTrending = selectedTab == 2;
        notifyChange();
        watch(repository.like(post.id, !post.likedByMe), result -> {
            liking.remove(post.id);
            if (generation != posts.generation && trendingGeneration != trending.generation) return;
            if (result.status == ForumResult.Status.SUCCESS) {
                if (generation == posts.generation) replacePost(posts.items, result.data);
                if (trendingGeneration == trending.generation) replaceTrending(result.data);
            } else if (fromTrending && trendingGeneration == trending.generation) trending.error = result.code;
            else if (!fromTrending && generation == posts.generation) posts.error = result.code;
            notifyChange();
        });
    }
    public boolean isLiking(String id) { return liking.contains(id); }
    public void replace(ForumPost post) {
        replacePost(posts.items, post); replaceTrending(post);
        notifyChange();
    }
    private void replacePost(List<ForumPost> items, ForumPost post) {
        for (int i = 0; i < items.size(); i++) if (Objects.equals(post.id, items.get(i).id)) items.set(i, post);
    }
    private void replaceTrending(ForumPost post) {
        for (ForumTrending result : trending.items) {
            replacePost(result.posts, post);
            result.posts.removeIf(p -> p.likeCount + 2 * p.commentCount == 0);
            result.posts.sort(Comparator.comparingLong((ForumPost p) -> p.likeCount + 2 * p.commentCount).reversed()
                    .thenComparing(Comparator.comparingLong((ForumPost p) -> p.createdAtEpochMillis).reversed())
                    .thenComparing(p -> p.id, Comparator.reverseOrder()));
        }
    }
    public void published(ForumPost post) {
        query = ""; clear(news); clear(posts); clear(trending);
        Arrays.fill(scrollStates, null);
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
