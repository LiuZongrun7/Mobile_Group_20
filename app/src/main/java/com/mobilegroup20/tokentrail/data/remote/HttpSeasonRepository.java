package com.mobilegroup20.tokentrail.data.remote;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import com.mobilegroup20.tokentrail.contract.model.SeasonState;
import com.mobilegroup20.tokentrail.contract.model.TokenBundle;
import com.mobilegroup20.tokentrail.data.repository.SeasonRepository;
import com.mobilegroup20.tokentrail.data.repository.SessionProvider;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 赛季与结算的 HTTP 实现。<b>负责人：游戏侧（刘宗润）。</b>
 *
 * <p><b>余额和 `lastSettledDay` 都在服务端，客户端一个字节都不存。</b>
 * 这不是为了省事——`CONTRACTS.md` §7 第 5 条的理由是「否则重装、换设备、多开都能重复领」。
 * 客户端存一份余额意味着重装一次就能把同一批 token 再领一遍资源。
 *
 * <p><b>结算逻辑也全在服务端</b>：这个类里没有一行「哪些天该结算」的判断，
 * 它只负责把 `POST /season/settle` 的结果翻成 {@link SettlementResult}。
 * 所以「今天不算」这种规则不可能在客户端被绕过——客户端根本没有那段代码。
 *
 * <p>身份用的是 <b>账号 token</b>（{@code RELAY_API.md}），不是 relay key：
 * 余额和用量的归属是账号的 `user_id`，所以 `SessionProvider.token()` 给的是
 * 账号会话 token，`accountId()` 给的是 `userId`。这一点原来和现在**正好相反**
 * （改之前归属是 relay key 的 sha256），别照旧注释理解。
 */
public final class HttpSeasonRepository implements SeasonRepository {

    private final RelayApi api;
    private final SessionProvider session;

    public HttpSeasonRepository(String baseUrl, SessionProvider session) {
        this.session = session;
        String normalized = baseUrl == null || baseUrl.isEmpty() ? null
                : baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        api = normalized == null ? null : new Retrofit.Builder().baseUrl(normalized)
                .client(new OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false).build())
                .addConverterFactory(GsonConverterFactory.create()).build().create(RelayApi.class);
    }

    /** 资源种类 → 服务端那张表的字段。三种资源互不通兑，所以是三个独立字段。 */
    private interface Request<T> {
        Call<T> create(String authorization);
    }

    private <T> LiveData<T> request(String uid, Request<T> request, T failure) {
        MutableLiveData<T> result = new MutableLiveData<>(null);
        String token = session.token();
        String identity = session.accountId();
        if (api == null || token == null || token.isEmpty()) { result.setValue(failure); return result; }
        // 传进来的 uid 和当前身份不一致时**直接拒绝**，不发请求。
        // 这不是防御性编程：`SeasonRepository` 的每个方法都带 uid 就是为了让
        // 「查了谁的余额」这件事在调用点可见（见接口注释）。对不上说明调用方拿错了
        // 对象（比如换过账号没换仓储），发出去会读到别人的余额。
        if (!Objects.equals(uid, identity)) { result.setValue(failure); return result; }
        request.create("Bearer " + token).enqueue(new Callback<>() {
            @Override public void onResponse(Call<T> call, Response<T> response) {
                // 中途换了账号：这个结果已经不属于当前界面了，丢掉而不是显示旧账号的余额。
                if (!Objects.equals(identity, session.accountId()) || !Objects.equals(token, session.token())) {
                    result.postValue(failure); return;
                }
                result.postValue(response.isSuccessful() && response.body() != null ? response.body() : failure);
            }
            @Override public void onFailure(Call<T> call, Throwable error) {
                result.postValue(failure);
            }
        });
        return result;
    }

    private static SeasonState emptyState(String uid) {
        SeasonState state = new SeasonState();
        state.uid = uid;
        state.balance = new ResourceBalance();
        return state;
    }

    /** 服务端的快照 → 契约里的 {@link SeasonState}。 */
    private static SeasonState toState(String uid, RelayApi.SeasonSnapshot snapshot) {
        SeasonState state = new SeasonState();
        state.uid = uid;
        state.balance = new ResourceBalance(snapshot.balance.input, snapshot.balance.cache,
                snapshot.balance.output);
        state.lastSettledDay = snapshot.lastSettledDay;
        // 赛季标识按**服务端给的已结算日**推，不用本机时钟：本机时区/时间不对时，
        // 界面上的赛季会和余额来自不同的月份，看起来像 bug。
        state.seasonId = snapshot.lastSettledDay != null && snapshot.lastSettledDay.length() >= 7
                ? snapshot.lastSettledDay.substring(0, 7) : null;
        state.updatedAtEpochMillis = snapshot.updatedAtEpochMillis == null ? 0L : snapshot.updatedAtEpochMillis;
        return state;
    }

    @Override public LiveData<SeasonState> currentSeason(String uid) {
        // 两步：先按服务端的响应类型（`SeasonSnapshot`）拿一次，再转成契约类型。
        // **不把转换塞进 `request`**：那个泛型参数同时受返回值和失败值约束，
        // 塞进去会让 `T` 推断不出来（编译期就报 incompatible bounds）。
        LiveData<RelayApi.SeasonSnapshot> raw = request(uid, auth -> api.season(auth), null);
        return Transformations.map(raw, snapshot ->
                snapshot == null ? emptyState(uid) : toState(uid, snapshot));
    }

    @Override public LiveData<SettlementResult> settleCompletedDays(String uid) {
        // 单独写一个方法而不是复用 `request(...)`：结算的「失败」值是一个**空的
        // SettlementResult**（`settledDays` 为空表示这次没有新的一天可结），
        // 而空结果和失败在界面上是两件事——所以这里把失败也表达成同一个形状，
        // 但调用方可以通过 `settledDays` 是否为空来区分「结完了」和「没结」。
        MutableLiveData<SettlementResult> result = new MutableLiveData<>(null);
        String token = session.token();
        String identity = session.accountId();
        if (api == null || token == null || token.isEmpty() || !Objects.equals(uid, identity)) {
            result.setValue(new SettlementResult()); return result;
        }
        api.settle("Bearer " + token).enqueue(new Callback<>() {
            @Override public void onResponse(Call<RelayApi.Settlement> call, Response<RelayApi.Settlement> response) {
                if (!Objects.equals(identity, session.accountId()) || !Objects.equals(token, session.token())) {
                    result.postValue(new SettlementResult()); return;
                }
                RelayApi.Settlement body = response.body();
                if (!response.isSuccessful() || body == null) { result.postValue(new SettlementResult()); return; }
                SettlementResult converted = new SettlementResult();
                if (body.settledDays != null) converted.settledDays.addAll(body.settledDays);
                converted.gained = balance(body.gained);
                converted.balanceAfter = balance(body.balanceAfter);
                converted.tokensCounted = tokens(body.tokensCounted);
                result.postValue(converted);
            }
            @Override public void onFailure(Call<RelayApi.Settlement> call, Throwable error) {
                result.postValue(new SettlementResult());
            }
        });
        return result;
    }

    @Override public LiveData<Boolean> spend(String uid, ResourceType type, long amount) {
        MutableLiveData<Boolean> result = new MutableLiveData<>(Boolean.FALSE);
        String token = session.token();
        String identity = session.accountId();
        if (api == null || token == null || token.isEmpty() || !Objects.equals(uid, identity)) return result;
        // 三种资源分开传：点了一种塔就只扣那一种，不会拿别的资源顶上。
        long input = type == ResourceType.INPUT ? amount : 0;
        long cache = type == ResourceType.CACHE ? amount : 0;
        long output = type == ResourceType.OUTPUT ? amount : 0;
        api.spend("Bearer " + token, new RelayApi.SpendRequest(input, cache, output))
                .enqueue(new Callback<>() {
                    @Override public void onResponse(Call<RelayApi.SpendResult> call,
                                                     Response<RelayApi.SpendResult> response) {
                        // 409 是「不够」，也是 false——界面据此提示，不用区分。
                        // 其余失败也返回 false：**扣款失败绝不能当成成功**，
                        // 那会让玩家白拿一座塔。
                        result.postValue(response.isSuccessful() && response.body() != null);
                    }
                    @Override public void onFailure(Call<RelayApi.SpendResult> call, Throwable error) {
                        result.postValue(Boolean.FALSE);
                    }
                });
        return result;
    }

    private static ResourceBalance balance(RelayApi.Balance source) {
        return source == null ? new ResourceBalance()
                : new ResourceBalance(source.input, source.cache, source.output);
    }

    private static TokenBundle tokens(RelayApi.MonthTokens source) {
        return source == null ? new TokenBundle()
                : new TokenBundle(source.input, source.cacheRead, source.cacheWrite, source.output);
    }
}
