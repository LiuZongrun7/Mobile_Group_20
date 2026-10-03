package com.mobilegroup20.modelpilot.data.remote;

import com.google.gson.annotations.SerializedName;
import retrofit2.Call;
import retrofit2.http.*;

/**
 * 服务端用量/预算接口。<b>负责人：汪庭栋（论坛与服务端记账）。</b>
 *
 * <p>契约见 {@code docs/SERVER_API.md}（原 {@code RELAY_API.md}）。这里只读**用量和预算**，身份一律是
 * <b>账号 token</b>（{@code Authorization: Bearer tt_app_...}，见
 * {@code AccountSession}）：账挂在账号的 `userId` 上，所以在 App 里提问的用户
 * 照样读得到自己的用量，不需要别的凭据。
 *
 * <p><b>为什么路径里还留着 {@code relay} 这个词</b>：那是历史前缀——这套接口
 * 原来属于「中转」（用户自己填上游 key、把流量转到服务器再记账），那条路已经
 * 整个删掉了，但服务端的路由前缀仍然是 {@code /api/relay/...}。改它要同时改
 * 服务端与 App，只改一边就是 404，所以等两边一起做的时候再改；在那之前
 * **路径一个字都不能动**。
 */
public interface ServerApi {

    /** 按模型聚合的用量。 */
    @GET("relay/usage") Call<UsagePage> usage(@Header("Authorization") String authorization);

    /** 按天的用量汇总——`DailyUsage` 的形状。**客户端不再自己滚日汇总**，只读这里。 */
    @GET("relay/usage/daily")
    Call<DailyPage> usageDaily(@Header("Authorization") String authorization,
                              @Query("since") long since, @Query("until") Long until);

    // ---- 预算。**只存上限，花销现算** ------------------------------

    @GET("relay/budgets/{month}")
    Call<BudgetStatusBody> budgetStatus(@Header("Authorization") String authorization,
                                        @Path("month") String month);

    @PUT("relay/budgets/{month}")
    Call<BudgetBody> saveBudget(@Header("Authorization") String authorization,
                               @Path("month") String month, @Body BudgetRequest request);

    class UsagePage {
        public String uid;
        public java.util.List<Usage> items;
    }

    class DailyPage {
        public String uid;
        public java.util.List<Daily> items;
        /** 服务端还没有价目表时是 false，此时每行的 `costMicros` 是 null。 */
        public boolean pricingAvailable;
        /** 天口径的时区，目前恒为 `Asia/Shanghai`。 */
        public String timezone;
    }

    class Daily {
        public String uid;
        /** `yyyy-MM-dd`，`Asia/Shanghai`。 */
        public String day;
        public String provider;
        public String model;
        public long input;
        @SerializedName("cacheRead") public long cacheRead;
        @SerializedName("cacheWrite") public long cacheWrite;
        public long output;
        public long calls;
        /** **算不出价时是 null，不是 0**——`CONTRACTS.md` §4 那条底线。 */
        public Long costMicros;
        public String rateVersion;
        public boolean settled;
    }

    // 赛季那几张 DTO（SeasonSnapshot / Settlement / SpendRequest / SpendResult /
    // SettledDays / SettledDay）连同上面那四个接口一起删了（2026-09-30）：
    // 游戏整块移出本工程，客户端不再有"资源余额"这个概念。
    // 服务端的 `/relay/season*` 还在，要用的时候照这几个形状重新加就行。
    //
    // 中转 key 那几张 DTO（EnrollmentRequest / UpdateRequest / Enrollment / KeyList）
    // 连同 `/relay/keys*` 那几个接口一起删了（产品方向改成"用户在我们 App 里提问、
    // 由后端调用模型"）：App 不再持有上游凭据，也就不再注册、轮换、停用 relay key。

    class BudgetRequest {
        public final long capMicros;
        public final Double warnAtRatio;

        public BudgetRequest(long capMicros, Double warnAtRatio) {
            this.capMicros = capMicros; this.warnAtRatio = warnAtRatio;
        }
    }

    /** `PUT` 的响应：存下来的那一条（不含花销和覆盖度）。 */
    class BudgetBody {
        public String uid;
        public String month;
        public long capMicros;
        @SerializedName("warnAtRatio") public double warnAtRatio;
        @SerializedName("updatedAtEpochMillis") public Long updatedAtEpochMillis;
    }

    /**
     * `GET` 的响应：预算 + 现算的花销 + 覆盖度。
     *
     * <p><b>`configured` 和 `capMicros` 必须分开读</b>：没设过预算时
     * `configured` 是 false 而 `capMicros` 是 **null**——不是 0。
     * 「你还没设预算」和「你已经超了」在界面上是两句不同的话。
     */
    class BudgetStatusBody {
        public String uid;
        public String month;
        public boolean configured;
        /** 可空：没设过时是 null。用 `Long` 而不是 `long`，否则 null 会变成 0。 */
        public Long capMicros;
        @SerializedName("warnAtRatio") public Double warnAtRatio;
        public long spentMicros;
        /**
         * 服务端有没有价目表。**现在是 false**，此时 `spentMicros` 是 0
         * 但意思是「算不出来」，界面不能显示成「你花了 0 元」。
         */
        public boolean pricingAvailable;
        public CoverageBody coverage;
    }

    class CoverageBody {
        public String from;
        public String to;
        public int daysWithData;
        public java.util.List<String> daysMissing;
    }

    class Usage {
        public String model;
        public String serviceTier;
        public long input;
        @SerializedName("cacheRead") public long cacheRead;
        @SerializedName("cacheWrite") public long cacheWrite;
        public long output;
        public long calls;
        @SerializedName("lastAtEpochMillis") public long lastAtEpochMillis;
    }
}
