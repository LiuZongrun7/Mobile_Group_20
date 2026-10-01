package com.mobilegroup20.modelpilot.ui.auth;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.data.AccountSession;
import com.mobilegroup20.modelpilot.data.RelayCredentials;
import com.mobilegroup20.modelpilot.data.remote.RelayApi;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.concurrent.TimeUnit;

/**
 * 中转注册的界面逻辑。<b>负责人：汪庭栋。</b>
 *
 * <p>三件事：校验用户填的东西（**本地先挡一遍**，别拿明显错的请求去打服务器）、
 * 调注册接口、把 relay key 存本机。
 *
 * <p>校验和服务端是<b>两套独立的规则</b>，不是重复：本地这套为了立刻给反馈，
 * 服务端那套才是权威（它还要挡重复 key、SSRF、限流）。本地过了不代表服务端会过，
 * 所以 400/409 这些响应还是要照实显示。
 */
public final class RelaySetupViewModel extends AndroidViewModel {

    public final MutableLiveData<String> state = new MutableLiveData<>("IDLE");
    /** 注册成功后要显示给用户的那一个 key（自动生成时是唯一一次机会）。 */
    public final MutableLiveData<String> issuedKey = new MutableLiveData<>();
    public final MutableLiveData<Integer> providerLabel = new MutableLiveData<>(0);
    /**
     * 我名下所有的 relay key（元数据，**没有明文**）。
     *
     * <p>null = 还没查 / 查不了；空表 = 确实一条都没有。**两者要说两句不同的话**：
     * 「还没查出来」和「你一条都没录」在界面上完全不是一回事。
     */
    public final MutableLiveData<java.util.List<RelayApi.Enrollment>> keys = new MutableLiveData<>();

    private final RelayCredentials credentials;
    /**
     * 账号会话。**注册 relay key 要它**：relay key 挂在账号下面，
     * 服务端拿这个 token 解出 `user_id` 才肯发 key（见 `relay_routes.enroll`）。
     */
    private final AccountSession account;
    private final RelayApi api;
    private Call<?> pending;
    private volatile int generation;

    public RelaySetupViewModel(Application application) {
        super(application);
        credentials = RelayCredentials.get(application);
        account = AccountSession.get(application);
        RelayApi built = null;
        if (BuildConfig.FORUM_BASE_URL.startsWith("https://")) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .callTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();
            built = new Retrofit.Builder().baseUrl(BuildConfig.FORUM_BASE_URL).client(client)
                    .addConverterFactory(GsonConverterFactory.create()).build().create(RelayApi.class);
        }
        api = built;
    }

    /**
     * 拉一次「我名下所有的 key」。
     *
     * <p>为什么要它：`configured()` 只答得了「本机存的这一条配了没有」，
     * 答不了「我在服务端一共录了几条」。换过上游、在另一台设备上注册过的人
     * 需要看到全部，否则他会以为旧的已经没了。
     */
    public void loadKeys() {
        String authorization = account.authorization();
        if (api == null || authorization == null) { keys.setValue(null); return; }
        int request = ++generation;
        api.keys(authorization).enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<RelayApi.KeyList> call,
                                             @NonNull Response<RelayApi.KeyList> response) {
                if (request != generation) return;
                RelayApi.KeyList body = response.body();
                // 查不到就**保持 null**（界面说「查不到」），不要显示成空列表——
                // 空列表读起来是「你一条都没录」，那可能是假的。
                if (!response.isSuccessful() || body == null || body.items == null) return;
                keys.setValue(body.items);
            }
            @Override public void onFailure(@NonNull Call<RelayApi.KeyList> call, @NonNull Throwable error) { }
        });
    }

    /** 停用指定那一条（按 uid，不会改错人）。 */
    public void revokeKey(String uid) {
        String authorization = account.authorization();
        if (api == null || authorization == null || uid == null) { state.setValue("NO_ACCOUNT"); return; }
        int request = ++generation;
        state.setValue("BUSY");
        api.revokeKey(authorization, uid).enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                if (request != generation) return;
                if (!response.isSuccessful()) { state.setValue("REQUEST"); return; }
                // 本机那一份**在这里不动**：停用的可能不是本机这条，
                // 而且服务端停用之后转发会直接 403，界面有更准的信号可用
                // （`relay_signed_out`）。在这里凭 uid 猜「是不是本机那条」
                // 只会猜错——uid 是 key 的 sha256，本机存的却是明文。
                loadKeys();
                state.setValue("REVOKED_ONE");
            }
            @Override public void onFailure(@NonNull Call<Void> call, @NonNull Throwable error) {
                if (request == generation) state.setValue("NETWORK");
            }
        });
    }

    /**
     * 界面用：这条 key 是不是本机正在用的那条。
     *
     * <p>本机存的是**明文**，列表里给的是 **sha256**，所以这里得算一次 sha256 再比——
     * `RelayCredentials.digestOf` 和服务端 `relay_store.key_hash` 是同一个口径。
     * 直接用明文字符串比会在任何情况下都不相等，界面就会把每一条都标成「不是本机」。
     */
    public boolean isLocal(String uid) {
        String local = credentials.relayKey();
        return local != null && uid != null && uid.equals(RelayCredentials.digestOf(local));
    }

    /** 服务端名下有没有 key（本机有没有凭据是另一回事）。 */
    public boolean hasKeys() {
        java.util.List<RelayApi.Enrollment> items = keys.getValue();
        return items != null && !items.isEmpty();
    }

    /** 列表里第一条（按注册时间倒序，所以是最新注册的那条）。没有就返回 null。 */
    public RelayApi.Enrollment firstKey() {
        java.util.List<RelayApi.Enrollment> items = keys.getValue();
        return items == null || items.isEmpty() ? null : items.get(0);
    }

    public boolean configured() { return credentials.configured(); }
    public String upstreamUrl() { return credentials.upstreamUrl(); }
    public String relayKey() { return credentials.relayKey(); }

    /** 自定义 relay key 的本地校验。返回 0 表示通过，否则是要显示的字符串资源。 */
    public static int validateRelayKey(String value) {
        if (value == null || value.isEmpty()) return 0;          // 留空 = 让服务端生成
        if (!value.startsWith("tt_")) return com.mobilegroup20.modelpilot.R.string.relay_key_prefix;
        // 和服务端 relay_store.MIN_CUSTOM_LENGTH 一致。**它就是登录凭据**，
        // 太短等于把「读我的用量」和「用我的上游 key 转发」一起交出去。
        if (value.length() < 24) return com.mobilegroup20.modelpilot.R.string.relay_key_short;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return com.mobilegroup20.modelpilot.R.string.relay_key_space;
        }
        return 0;
    }

    /** 上游地址的本地校验。生产必须 https；只有本机调试才允许 http。 */
    public static int validateUpstreamUrl(String value) {
        if (value == null || value.trim().isEmpty()) return com.mobilegroup20.modelpilot.R.string.relay_url_empty;
        String url = value.trim();
        boolean secure = url.startsWith("https://");
        // http 只对本机放行：那不是「允许不安全」，是本地假上游没有证书。
        boolean loopback = url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost");
        if (!secure && !loopback) return com.mobilegroup20.modelpilot.R.string.relay_url_https;
        return 0;
    }

    public void enroll(String upstreamUrl, String upstreamKey, String relayKey, String displayName) {
        if ("BUSY".equals(state.getValue())) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        String url = upstreamUrl == null ? "" : upstreamUrl.trim();
        String key = upstreamKey == null ? "" : upstreamKey.trim();
        String custom = relayKey == null ? "" : relayKey.trim();
        int problem = validateUpstreamUrl(url);
        if (problem != 0) { providerLabel.setValue(problem); state.setValue("INVALID_URL"); return; }
        if (key.isEmpty()) { state.setValue("EMPTY_KEY"); return; }
        problem = validateRelayKey(custom);
        if (problem != 0) { providerLabel.setValue(problem); state.setValue("INVALID_RELAY_KEY"); return; }

        String authorization = account.authorization();
        if (authorization == null) {
            // 没登录就没法注册 relay key——**这不是可以绕过的前置条件**，
            // 而是身份方位本身：key 必须在某个账号下面。界面据此先引导登录。
            state.setValue("NO_ACCOUNT"); return;
        }
        int request = ++generation;
        state.setValue("BUSY");
        Call<RelayApi.Enrollment> call = api.enroll(authorization, new RelayApi.EnrollmentRequest(
                url, key, custom.isEmpty() ? null : custom, displayName == null ? "" : displayName.trim()));
        pending = call;
        call.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<RelayApi.Enrollment> call,
                                             @NonNull Response<RelayApi.Enrollment> response) {
                if (request != generation) return;
                RelayApi.Enrollment body = response.body();
                if (!response.isSuccessful() || body == null) {
                    state.setValue(switch (response.code()) {
                        case 409 -> "DUPLICATE";
                        case 400 -> "INVALID_SERVER";
                        case 429 -> "RATE_LIMIT";
                        default -> response.code() >= 500 ? "SERVER" : "REQUEST";
                    });
                    return;
                }
                // 自动生成时 relayKey 在响应里；自定义时服务端不回显，用用户填的那个。
                String effective = body.relayKey != null ? body.relayKey : custom;
                if (effective == null || !effective.startsWith("tt_")) { state.setValue("SERVER"); return; }
                try {
                    credentials.save(effective, url);
                } catch (Exception ignored) { state.setValue("STORAGE"); return; }
                issuedKey.setValue(effective);
                state.setValue("SUCCESS");
            }

            @Override public void onFailure(@NonNull Call<RelayApi.Enrollment> call, @NonNull Throwable error) {
                if (request == generation) state.setValue("NETWORK");
            }
        });
    }

    /** 换上游地址或上游密钥。uid 不变，历史归属不断。 */
    public void update(String upstreamUrl, String upstreamKey) {
        if ("BUSY".equals(state.getValue())) return;
        // 管理接口要的是**这条 key**，所以服务端仍然用 key 认；但账号 token 也认
        // （两条路都解到同一个账号）。手机上留的是 key，这里继续用它。
        String authorization = credentials.authorization();
        if (api == null || authorization == null) { state.setValue("NOT_CONFIGURED"); return; }
        String url = upstreamUrl == null ? "" : upstreamUrl.trim();
        String key = upstreamKey == null ? "" : upstreamKey.trim();
        if (!url.isEmpty()) {
            int problem = validateUpstreamUrl(url);
            if (problem != 0) { providerLabel.setValue(problem); state.setValue("INVALID_URL"); return; }
        }
        if (url.isEmpty() && key.isEmpty()) { state.setValue("NOTHING_TO_UPDATE"); return; }
        int request = ++generation;
        state.setValue("BUSY");
        Call<RelayApi.Enrollment> call = api.update(authorization,
                new RelayApi.UpdateRequest(url.isEmpty() ? null : url, key.isEmpty() ? null : key));
        pending = call;
        call.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<RelayApi.Enrollment> call,
                                             @NonNull Response<RelayApi.Enrollment> response) {
                if (request != generation) return;
                if (!response.isSuccessful()) {
                    state.setValue(response.code() == 401 ? "SIGNED_OUT" : "REQUEST");
                    return;
                }
                if (!url.isEmpty()) {
                    try {
                        credentials.save(credentials.relayKey(), url);
                    } catch (Exception ignored) { state.setValue("STORAGE"); return; }
                }
                state.setValue("UPDATED");
            }

            @Override public void onFailure(@NonNull Call<RelayApi.Enrollment> call, @NonNull Throwable error) {
                if (request == generation) state.setValue("NETWORK");
            }
        });
    }

    /** 撤回：先让服务端停用，再清本机。反过来会让本机没了、服务端还认这个 key。 */
    public void revoke() {
        if ("BUSY".equals(state.getValue())) return;
        String authorization = credentials.authorization();
        if (api == null || authorization == null) { credentials.clear(); state.setValue("SUCCESS"); return; }
        int request = ++generation;
        state.setValue("BUSY");
        Call<Void> call = api.revoke(authorization);
        pending = call;
        call.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                if (request != generation) return;
                // 服务端说 401 说明这个 key 在那边已经没了，本机这份留着也没用，照清。
                credentials.clear();
                state.setValue(response.isSuccessful() || response.code() == 401 ? "SUCCESS" : "PARTIAL_REVOKE");
            }

            @Override public void onFailure(@NonNull Call<Void> call, @NonNull Throwable error) {
                if (request != generation) return;
                // 网络失败**不清本机**：服务端可能还认这个 key，清掉本机就再也撤不回来了。
                state.setValue("NETWORK");
            }
        });
    }

    /** 把本地校验的字符串资源取出来给界面显示。 */
    public int validationMessage() {
        Integer value = providerLabel.getValue();
        return value == null ? 0 : value;
    }

    @Override protected void onCleared() { ++generation; if (pending != null) pending.cancel(); }
}
