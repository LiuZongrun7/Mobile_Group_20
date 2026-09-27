package com.mobilegroup20.tokentrail.ui.forum;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.*;
import com.mobilegroup20.tokentrail.contract.model.*;
import com.mobilegroup20.tokentrail.data.RepositoryProvider;
import com.mobilegroup20.tokentrail.data.repository.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Retains drafts and uploaded IDs through view recreation and safe publish retries. */
public class ComposePostViewModel extends AndroidViewModel {
    public final SavedStateHandle saved;
    public final MutableLiveData<String> error = new MutableLiveData<>();
    public final MutableLiveData<String> progress = new MutableLiveData<>();
    public final MutableLiveData<ForumPost> published = new MutableLiveData<>();
    public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    public final MutableLiveData<List<String>> photos;
    private final MediatorLiveData<Object> requests = new MediatorLiveData<>();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> uploaded = new HashMap<>();
    private boolean cleared;
    private final ForumFeedRepository repository = RepositoryProvider.forumFeed();
    private final String identity;
    private PostDraft pending;
    private String pendingKey;
    public ComposePostViewModel(@NonNull Application app, SavedStateHandle saved) {
        super(app); this.saved = saved; photos = saved.getLiveData("photos", new ArrayList<>());
        if (!saved.contains("accountId")) saved.set("accountId", repository.sessionIdentity());
        identity = saved.get("accountId");
        HashMap<String, String> retained = saved.get("uploaded");
        if (retained != null) uploaded.putAll(retained);
    }
    public LiveData<Object> requests() { return requests; }
    public void addPhotos(List<Uri> selected) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        List<String> all = new ArrayList<>(photos.getValue());
        for (Uri uri : selected) {
            if (all.size() == 9) break;
            if (!all.contains(uri.toString())) {
                try { getApplication().getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { /* Some providers grant access only for this process. */ }
                all.add(uri.toString());
            }
        }
        photos.setValue(all);
    }
    public void removePhoto(String uri) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        List<String> all = new ArrayList<>(photos.getValue()); all.remove(uri); photos.setValue(all);
    }
    public void publish(String title, String body) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        saved.set("title", title); saved.set("body", body);
        if (!Objects.equals(identity, repository.sessionIdentity()) || repository != RepositoryProvider.forumFeed()) { error.setValue("SESSION_CHANGED"); return; }
        if (body.trim().isEmpty() && photos.getValue().isEmpty()) { error.setValue("EMPTY_POST"); return; }
        pending = new PostDraft(); pending.title = title.trim(); pending.body = body.trim();
        // A retry of unchanged content uses the same key, including after a process recreation.
        String fingerprint = pending.title + "\u0000" + pending.body + "\u0000" + photos.getValue();
        String oldFingerprint = saved.get("fingerprint");
        pendingKey = saved.get("requestId");
        if (!fingerprint.equals(oldFingerprint) || pendingKey == null) pendingKey = UUID.randomUUID().toString();
        saved.set("fingerprint", fingerprint); saved.set("requestId", pendingKey);
        error.setValue(null); busy.setValue(true);
        uploadNext(new ArrayList<>(photos.getValue()), 0);
    }
    private void uploadNext(List<String> uris, int index) {
        if (cleared) return;
        if (!Objects.equals(identity, repository.sessionIdentity()) || repository != RepositoryProvider.forumFeed()) { fail("SESSION_CHANGED"); return; }
        if (index == uris.size()) {
            progress.setValue("publish");
            watch(repository.publish(pending, pendingKey), result -> {
                busy.setValue(false);
                if (!Objects.equals(identity, repository.sessionIdentity()) || repository != RepositoryProvider.forumFeed()) { error.setValue("SESSION_CHANGED"); return; }
                if (result.status == ForumResult.Status.SUCCESS) published.setValue(result.data);
                else error.setValue(result.code);
            });
            return;
        }
        String value = uris.get(index);
        if (uploaded.containsKey(value)) { pending.imageIds.add(uploaded.get(value)); uploadNext(uris, index + 1); return; }
        progress.setValue((index + 1) + "/" + uris.size());
        reader.execute(() -> {
            try {
                Uri uri = Uri.parse(value);
                String mime = getApplication().getContentResolver().getType(uri);
                if (mime == null || !mime.startsWith("image/")) throw new IOException("INVALID_IMAGE");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                try (InputStream input = getApplication().getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("PHOTO_READ");
                    byte[] buffer = new byte[8192]; int size;
                    while ((size = input.read(buffer)) != -1) {
                        if (data.size() + size > 10 * 1024 * 1024) throw new IOException("INVALID_IMAGE");
                        data.write(buffer, 0, size);
                    }
                }
                byte[] bytes = data.toByteArray();
                main.post(() -> {
                    if (cleared) return;
                    if (!Objects.equals(identity, repository.sessionIdentity())) { fail("SESSION_CHANGED"); return; }
                    watch(repository.uploadImage(bytes, mime), result -> {
                        if (result.status == ForumResult.Status.SUCCESS && result.data.id != null) {
                            uploaded.put(value, result.data.id); pending.imageIds.add(result.data.id);
                            saved.set("uploaded", new HashMap<>(uploaded));
                            uploadNext(uris, index + 1);
                        } else fail(result.code == null ? "BAD_RESPONSE" : result.code);
                    });
                });
            } catch (IOException | SecurityException failure) {
                String code = "INVALID_IMAGE".equals(failure.getMessage()) ? "INVALID_IMAGE" : "PHOTO_READ";
                main.post(() -> { if (!cleared) fail(code); });
            }
        });
    }
    private void fail(String code) { busy.setValue(false); error.setValue(code); }
    private <T> void watch(LiveData<ForumResult<T>> source, Consumer<ForumResult<T>> done) {
        requests.addSource(source, result -> {
            if (result.status == ForumResult.Status.LOADING) return;
            requests.removeSource(source); done.accept(result);
        });
    }
    @Override protected void onCleared() { cleared = true; reader.shutdownNow(); }
}
