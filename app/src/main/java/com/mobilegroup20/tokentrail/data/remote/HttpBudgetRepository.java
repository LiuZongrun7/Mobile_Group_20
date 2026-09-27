package com.mobilegroup20.tokentrail.data.remote;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.mobilegroup20.tokentrail.contract.model.Budget;
import com.mobilegroup20.tokentrail.contract.tool.BudgetStatus;
import com.mobilegroup20.tokentrail.contract.tool.Coverage;
import com.mobilegroup20.tokentrail.data.repository.BudgetRepository;
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
 * 预算的 HTTP 实现。<b>负责人：数据侧（张莉）。</b>
 *
 * <p><b>服务端只存上限</b>（`TASKS.md` §1.1：花销从来现算，不存「已花」），
 * 所以这个类里没有一行算花销的逻辑——它把服务端的响应翻成契约类型。
 *
 * <p><b>两个必须小心的映射</b>：
 * <ol>
 *   <li>{@code configured} 和 {@code capMicros} 是两件事。没设过预算时服务端给
 *       {@code configured=false} + {@code capMicros=null}，**不是 0**。
 *       这里用可空的 {@code Long} 接，别让 null 变成 0。</li>
 *   <li>{@code spentMicros} 现在是 0，但 {@code pricingAvailable} 是 false——
 *       意思是「算不出来」而不是「花了 0 元」。见下面 {@link #status} 的说明。</li>
 * </ol>
 *
 * <p>身份用**账号 token**（和 {@code HttpSeasonRepository} 一样），不是 relay key：
 * 预算挂在账号上，换个 relay key 不该让预算消失。
 */
public final class HttpBudgetRepository implements BudgetRepository {

    private final RelayApi api;
    private final SessionProvider session;

    public HttpBudgetRepository(String baseUrl, SessionProvider session) {
        this.session = session;
        String normalized = baseUrl == null || baseUrl.isEmpty() ? null
                : baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        api = normalized == null ? null : new Retrofit.Builder().baseUrl(normalized)
                .client(new OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(false).build())
                .addConverterFactory(GsonConverterFactory.create()).build().create(RelayApi.class);
    }

    /** 认证头 + 「uid 和当前身份不一致就直接拒绝」。理由见 `HttpSeasonRepository`。 */
    private String authorizationFor(String uid) {
        String token = session.token();
        if (api == null || token == null || token.isEmpty()) return null;
        return Objects.equals(uid, session.accountId()) ? "Bearer " + token : null;
    }

    private static Budget toBudget(RelayApi.BudgetBody body) {
        Budget budget = new Budget(body.uid, body.month, body.capMicros, body.warnAtRatio);
        return budget;
    }

    @Override public LiveData<Budget> budgetOf(String uid, String month) {
        // 单独一条路径：**没设过预算时 `budgetOf` 返回 null**（接口注释：
        // 界面据此显示「还没设预算」的引导）。而 `configured=false` 正是那个信号，
        // 所以这里不能把 null 换成空 Budget——那会让界面以为设过了。
        MutableLiveData<Budget> result = new MutableLiveData<>(null);
        String authorization = authorizationFor(uid);
        if (authorization == null) { result.setValue(null); return result; }
        api.budgetStatus(authorization, month).enqueue(new Callback<>() {
            @Override public void onResponse(Call<RelayApi.BudgetStatusBody> call,
                                             Response<RelayApi.BudgetStatusBody> response) {
                RelayApi.BudgetStatusBody body = response.body();
                if (!response.isSuccessful() || body == null) { result.postValue(null); return; }
                if (!body.configured || body.capMicros == null) { result.postValue(null); return; }
                result.postValue(new Budget(body.uid, body.month, body.capMicros,
                        body.warnAtRatio == null ? 0.0 : body.warnAtRatio));
            }
            @Override public void onFailure(Call<RelayApi.BudgetStatusBody> call, Throwable error) {
                result.postValue(null);
            }
        });
        return result;
    }

    @Override public LiveData<Budget> saveBudget(Budget budget) {
        MutableLiveData<Budget> result = new MutableLiveData<>(null);
        if (budget == null) { result.setValue(null); return result; }
        String authorization = authorizationFor(budget.uid);
        if (authorization == null) { result.setValue(null); return result; }
        api.saveBudget(authorization, budget.month,
                        new RelayApi.BudgetRequest(budget.capMicros, budget.warnAtRatio))
                .enqueue(new Callback<>() {
                    @Override public void onResponse(Call<RelayApi.BudgetBody> call,
                                                     Response<RelayApi.BudgetBody> response) {
                        RelayApi.BudgetBody body = response.body();
                        // 失败返回 null 而不是原样回传入参：回传的话界面会以为存上了。
                        result.postValue(response.isSuccessful() && body != null ? toBudget(body) : null);
                    }
                    @Override public void onFailure(Call<RelayApi.BudgetBody> call, Throwable error) {
                        result.postValue(null);
                    }
                });
        return result;
    }

    @Override public LiveData<BudgetStatus> status(String uid, String month) {
        MutableLiveData<BudgetStatus> result = new MutableLiveData<>(null);
        String authorization = authorizationFor(uid);
        if (authorization == null) { result.setValue(emptyStatus(month)); return result; }
        api.budgetStatus(authorization, month).enqueue(new Callback<>() {
            @Override public void onResponse(Call<RelayApi.BudgetStatusBody> call,
                                             Response<RelayApi.BudgetStatusBody> response) {
                RelayApi.BudgetStatusBody body = response.body();
                if (!response.isSuccessful() || body == null) { result.postValue(emptyStatus(month)); return; }
                BudgetStatus status = new BudgetStatus();
                status.month = body.month;
                status.configured = body.configured;
                status.capMicros = body.capMicros == null ? 0L : body.capMicros;
                status.warnAtRatio = body.warnAtRatio == null ? 0.0 : body.warnAtRatio;
                // **`spentMicros` 原样透传，服务端说 0 就是 0。**
                // 「算不出来」这件事由 `coverage` + 服务端的 `pricingAvailable` 表达，
                // 不在这一层改写——改写会让界面失去判断依据。
                status.spentMicros = body.spentMicros;
                status.coverage = toCoverage(body.coverage);
                result.postValue(status);
            }
            @Override public void onFailure(Call<RelayApi.BudgetStatusBody> call, Throwable error) {
                result.postValue(emptyStatus(month));
            }
        });
        return result;
    }

    /** 未知状态。`configured` 是 false 而不是「有预算但花销 0」——那会显示成超支。 */
    private static BudgetStatus emptyStatus(String month) {
        BudgetStatus status = new BudgetStatus();
        status.month = month;
        status.configured = false;
        status.coverage = new Coverage();
        return status;
    }

    private static Coverage toCoverage(RelayApi.CoverageBody body) {
        Coverage coverage = new Coverage();
        if (body == null) return coverage;
        coverage.from = body.from;
        coverage.to = body.to;
        coverage.daysWithData = body.daysWithData;
        if (body.daysMissing != null) coverage.daysMissing.addAll(body.daysMissing);
        return coverage;
    }
}
