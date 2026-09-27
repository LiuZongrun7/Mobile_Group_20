package com.mobilegroup20.tokentrail.ui.auth;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import com.mobilegroup20.tokentrail.BuildConfig;
import com.mobilegroup20.tokentrail.data.TeamAccountSession;
import com.mobilegroup20.tokentrail.data.remote.TeamAccountApi;
import com.mobilegroup20.tokentrail.data.remote.ForumTestSessionApi;
import com.mobilegroup20.tokentrail.data.RepositoryProvider;
import okhttp3.OkHttpClient;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.concurrent.TimeUnit;

public final class AccountViewModel extends AndroidViewModel {
    public final MutableLiveData<String> state = new MutableLiveData<>("IDLE");
    private final SavedStateHandle saved;
    private final TeamAccountSession session;
    private Call<?> pending;
    private volatile int generation;
    private TeamAccountApi api;
    private ForumTestSessionApi testApi;
    public AccountViewModel(Application application, SavedStateHandle saved) {
        super(application); this.saved = saved; session = TeamAccountSession.get(application);
        if (BuildConfig.FORUM_BASE_URL.startsWith("https://")) {
            OkHttpClient client = new OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
            api = new Retrofit.Builder().baseUrl(BuildConfig.FORUM_BASE_URL).client(client)
                    .addConverterFactory(GsonConverterFactory.create()).build().create(TeamAccountApi.class);
            if (BuildConfig.DEBUG) testApi = new Retrofit.Builder().baseUrl(TeamAccountSession.testBaseUrl()).client(client)
                    .addConverterFactory(GsonConverterFactory.create()).build().create(ForumTestSessionApi.class);
        }
    }
    public String username() { String value = saved.get("username"); return value == null ? "" : value; }
    public void username(String value) { saved.set("username", value); }
    public boolean signedIn() { return session.signedIn(); }
    public String name() { return session.accountName(); }
    public boolean forumTest() { return session.forumTest(); }
    public void enterForumTest() {
        if ("BUSY".equals(state.getValue())) return;
        if (!BuildConfig.DEBUG || testApi == null) { state.setValue("TEST_UNAVAILABLE"); return; }
        int request = ++generation;
        state.setValue("BUSY");
        Call<ForumTestSessionApi.TestSession> enter = testApi.enter(); pending = enter;
        enter.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<ForumTestSessionApi.TestSession> call, @NonNull Response<ForumTestSessionApi.TestSession> response) {
                if (request != generation) return;
                ForumTestSessionApi.TestSession body = response.body();
                if (!response.isSuccessful() || body == null) {
                    state.setValue(response.code() == 429 ? "RATE_LIMIT" : "TEST_UNAVAILABLE"); return;
                }
                try {
                    if (body.expiresAtEpochMillis <= System.currentTimeMillis()) throw new IllegalArgumentException("Expired test session");
                    session.saveForumTest(body.token, body.accountId, body.displayName);
                    RepositoryProvider.configureForum(session.forumBaseUrl(), session);
                    state.setValue("SUCCESS");
                } catch (Exception ignored) { state.setValue("STORAGE"); }
            }
            @Override public void onFailure(@NonNull Call<ForumTestSessionApi.TestSession> call, @NonNull Throwable error) {
                if (request == generation) state.setValue("NETWORK");
            }
        });
    }
    public void signIn(String password) {
        if ("BUSY".equals(state.getValue())) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        String username = username().trim();
        if (username.isEmpty() || password.isEmpty()) { state.setValue("EMPTY"); return; }
        int request = ++generation;
        state.setValue("BUSY");
        Call<TeamAccountApi.LoginResponse> login = api.login(username, password); pending = login;
        login.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<TeamAccountApi.LoginResponse> call, @NonNull Response<TeamAccountApi.LoginResponse> response) {
                if (request != generation) return;
                TeamAccountApi.LoginResponse body = response.body();
                if (!response.isSuccessful() || body == null || body.accessToken == null || body.accessToken.isEmpty()) {
                    state.setValue(response.code() == 429 ? "RATE_LIMIT" : response.code() >= 500 ? "SERVER" : "CREDENTIALS"); return;
                }
                fetchAccount(body.accessToken, request);
            }
            @Override public void onFailure(@NonNull Call<TeamAccountApi.LoginResponse> call, @NonNull Throwable error) {
                if (request == generation) state.setValue("NETWORK");
            }
        });
    }
    private void fetchAccount(String token, int request) {
        Call<TeamAccountApi.AccountResponse> me = api.me("Bearer " + token); pending = me;
        me.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<TeamAccountApi.AccountResponse> call, @NonNull Response<TeamAccountApi.AccountResponse> response) {
                if (request != generation) return;
                TeamAccountApi.AccountResponse body = response.body();
                if (!response.isSuccessful() || body == null) { state.setValue("SERVER"); return; }
                try {
                    session.save(token, body.userId, body.username);
                    RepositoryProvider.configureForum(session.forumBaseUrl(), session);
                    state.setValue("SUCCESS");
                }
                catch (Exception ignored) { state.setValue("STORAGE"); }
            }
            @Override public void onFailure(@NonNull Call<TeamAccountApi.AccountResponse> call, @NonNull Throwable error) {
                if (request == generation) state.setValue("NETWORK");
            }
        });
    }
    public void signOut() {
        ++generation; if (pending != null) pending.cancel();
        String token = session.token(); boolean wasTest = session.forumTest(); session.clear();
        RepositoryProvider.configureForum(session.forumBaseUrl(), session);
        Call<Void> logout = token == null ? null : wasTest && testApi != null ? testApi.leave("Bearer " + token) : api == null ? null : api.logout("Bearer " + token);
        if (logout != null) logout.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) { }
            @Override public void onFailure(@NonNull Call<Void> call, @NonNull Throwable error) { }
        });
        state.setValue("SUCCESS");
    }
    @Override protected void onCleared() { ++generation; if (pending != null) pending.cancel(); }
}
