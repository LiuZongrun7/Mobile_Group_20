package com.mobilegroup20.modelpilot.ui.auth;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.data.AccountSession;
import com.mobilegroup20.modelpilot.data.remote.AccountApi;
import com.mobilegroup20.modelpilot.data.remote.ForumTestSessionApi;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import okhttp3.OkHttpClient;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.concurrent.TimeUnit;

/**
 * 账号的界面逻辑：注册、登录、退出、论坛测试身份。<b>负责人：刘宗润。</b>
 *
 * <p>打的是**我们自己的后端** `/api/account/*`（{@code account_routes.py}）。
 * 2026-02 之前这里打的是团队那台机器上的 Java 账号服务，那时「App 用户」
 * 和「论坛用户」还是两拨人；现在账号就是 App 的账号，登录论坛、读用量、
 * 游戏结算共用同一个 `userId`。
 *
 * <p><b>注册两步走</b>：服务端的注册接口**不发 token**（有意为之，见
 * `account_routes.py` 的注释），所以注册成功后本地紧接着登一次。
 * 这一步放在 ViewModel 里而不是让用户再点一次——用户视角就是「注册完就进来了」，
 * 而服务端那条「一个接口只干一件事」的边界没有被破坏。
 */
public final class AccountViewModel extends AndroidViewModel {
    public final MutableLiveData<String> state = new MutableLiveData<>("IDLE");
    /** `INVALID` 时要显示哪一条本地校验的理由（字符串资源 id）。 */
    public final MutableLiveData<Integer> invalidReason = new MutableLiveData<>(0);
    private final SavedStateHandle saved;
    private final AccountSession session;
    private Call<?> pending;
    private volatile int generation;
    private AccountApi api;
    private ForumTestSessionApi testApi;

    public AccountViewModel(Application application, SavedStateHandle saved) {
        super(application); this.saved = saved; session = AccountSession.get(application);
        if (BuildConfig.FORUM_BASE_URL.startsWith("https://")) {
            OkHttpClient client = new OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
            api = new Retrofit.Builder().baseUrl(BuildConfig.FORUM_BASE_URL).client(client)
                    .addConverterFactory(GsonConverterFactory.create()).build().create(AccountApi.class);
            if (BuildConfig.DEBUG) testApi = new Retrofit.Builder().baseUrl(AccountSession.testBaseUrl()).client(client)
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
    /** 本地先挡一遍，别拿明显不合规的请求去打服务器（服务端那套才是权威）。 */
    public static int validateCredentials(String username, String password) {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty())
            return com.mobilegroup20.modelpilot.R.string.account_empty;
        if (username.trim().length() > 64)
            return com.mobilegroup20.modelpilot.R.string.account_username_long;
        if (password.length() < 8)
            return com.mobilegroup20.modelpilot.R.string.account_password_short;
        return 0;
    }

    public void signIn(String password) {
        if ("BUSY".equals(state.getValue())) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        String username = username().trim();
        int problem = validateCredentials(username, password);
        if (problem != 0) { state.setValue("INVALID"); invalidReason.setValue(problem); return; }
        int request = ++generation;
        state.setValue("BUSY");
        Call<AccountApi.Account> login = api.login(new AccountApi.Credentials(username, password));
        pending = login;
        login.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.Account> call,
                                             @NonNull Response<AccountApi.Account> response) {
                if (request != generation) return;
                AccountApi.Account body = response.body();
                if (!response.isSuccessful() || body == null
                        || body.token == null || body.token.isEmpty()) {
                    state.setValue(response.code() == 429 ? "RATE_LIMIT"
                            : response.code() >= 500 ? "SERVER" : "CREDENTIALS");
                    return;
                }
                store(body.token, body.userId, body.username, request);
            }
            @Override public void onFailure(@NonNull Call<AccountApi.Account> call, @NonNull Throwable error) {
                if (request == generation) state.setValue("NETWORK");
            }
        });
    }

    /**
     * 注册，然后立刻登录一次。
     *
     * <p>服务端注册**不发 token**，所以这里必然有第二步。两步之间用户看到的
     * 还是一个「注册中」，失败了就把失败原样显示——不做「注册成功但没登进去」
     * 这种中间态，那种状态用户没法处理。
     */
    public void register(String password) {
        if ("BUSY".equals(state.getValue())) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        String username = username().trim();
        int problem = validateCredentials(username, password);
        if (problem != 0) { state.setValue("INVALID"); invalidReason.setValue(problem); return; }
        int request = ++generation;
        state.setValue("BUSY");
        Call<AccountApi.Account> register = api.register(new AccountApi.Credentials(username, password));
        pending = register;
        register.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.Account> call,
                                             @NonNull Response<AccountApi.Account> response) {
                if (request != generation) return;
                if (response.code() == 409) { state.setValue("TAKEN"); return; }
                if (!response.isSuccessful() || response.body() == null) {
                    state.setValue(response.code() == 429 ? "RATE_LIMIT"
                            : response.code() >= 400 && response.code() < 500 ? "REQUEST" : "SERVER");
                    return;
                }
                // 注册成功，接着登录（第二轮，用同一个 request 号守住「用户已经走了」）
                Call<AccountApi.Account> login =
                        api.login(new AccountApi.Credentials(username, password));
                pending = login;
                login.enqueue(new Callback<>() {
                    @Override public void onResponse(@NonNull Call<AccountApi.Account> call,
                                                     @NonNull Response<AccountApi.Account> response) {
                        if (request != generation) return;
                        AccountApi.Account body = response.body();
                        if (!response.isSuccessful() || body == null
                                || body.token == null || body.token.isEmpty()) {
                            state.setValue("SERVER"); return;
                        }
                        store(body.token, body.userId, body.username, request);
                    }
                    @Override public void onFailure(@NonNull Call<AccountApi.Account> call,
                                                    @NonNull Throwable error) {
                        if (request == generation) state.setValue("NETWORK");
                    }
                });
            }
            @Override public void onFailure(@NonNull Call<AccountApi.Account> call, @NonNull Throwable error) {
                if (request == generation) state.setValue("NETWORK");
            }
        });
    }

    /** 把会话存下来，并把论坛那边切到当前身份。存储失败**不当登录成功**。 */
    private void store(String token, String userId, String username, int request) {
        if (request != generation) return;
        try {
            session.save(token, userId, username);
        } catch (Exception ignored) {
            state.setValue("STORAGE"); return;
        }
        RepositoryProvider.configureForum(session.forumBaseUrl(), session);
        state.setValue("SUCCESS");
    }
    public void signOut() {
        ++generation; if (pending != null) pending.cancel();
        String token = session.token(); boolean wasTest = session.forumTest();
        // **先把本地清干净再通知服务端。** 顺序反过来的话，退出请求失败时
        // 用户会留在「已经按了退出但还是登录着」的状态里，而他自己没法修。
        // 服务端的退出是幂等的，所以本地清了、请求失败也不会留下坏会话。
        session.clear();
        RepositoryProvider.configureForum(session.forumBaseUrl(), session);
        Call<Void> logout = token == null ? null
                : wasTest && testApi != null ? testApi.leave("Bearer " + token)
                : api == null ? null : api.logout("Bearer " + token);
        if (logout != null) logout.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) { }
            @Override public void onFailure(@NonNull Call<Void> call, @NonNull Throwable error) { }
        });
        state.setValue("SUCCESS");
    }
    @Override protected void onCleared() { ++generation; if (pending != null) pending.cancel(); }
}
