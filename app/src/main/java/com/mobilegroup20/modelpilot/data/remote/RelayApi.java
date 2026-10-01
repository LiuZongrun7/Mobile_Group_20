package com.mobilegroup20.modelpilot.data.remote;

import com.google.gson.annotations.SerializedName;
import retrofit2.Call;
import retrofit2.http.*;

/**
 * 中转服务的接口。<b>负责人：汪庭栋（论坛与中转）。</b>
 *
 * <p>契约见 {@code docs/RELAY_API.md}。§2026-02 改§ 这里最要紧的一条是
 * <b>哪条路用哪个凭据</b>：
 * <ul>
 *   <li><b>转发</b>（{@code /relay/v1/...}，cc-switch 走的那条）用 <b>relay key</b>——
 *       只有它认，账号 token 打过去是 401。这是有意的：否则账号 token 泄露
 *       就等于上游 key 泄露。</li>
 *   <li><b>其余全部</b>（注册 key、用量、赛季、预算、智能体）用<b>账号 token</b>。
 *       服务端把两条凭据都解到账号的 `userId`，所以账只有一种归属。</li>
 * </ul>
 *
 * <p>注册请求因此**必须带账号 token**：relay key 的 `uid` 是它自己的 sha256，
 * 但那是**这条 key 的 id**，不是身份——账记在账号的 `userId` 上
 * （见 {@code CONTRACTS.md} §5）。
 */
public interface RelayApi {

    /**
     * 给**当前登录的账号**注册一个 relay key。
     *
     * <p>{@code relayKey} 传 null 就让服务端自动生成一个（响应里返回，只此一次）。
     *
     * <p><b>必须带账号 token</b>（{@code Authorization: Bearer tt_app_...}）：
     * relay key 没有自己的身份，它挂在账号下面——早期版本它自带身份，
     * 于是「换个 key 就换个人」，用量和余额跟着清零。
     */
    @POST("relay/keys") Call<Enrollment> enroll(@Header("Authorization") String authorization,
                                                @Body EnrollmentRequest request);

    /** 当前身份和上游地址。**响应里永远不含上游密钥。** */
    @GET("relay/keys") Call<Enrollment> status(@Header("Authorization") String authorization);

    /** 换上游地址或上游密钥。不动 uid，所以历史归属不会断。 */
    @PATCH("relay/keys") Call<Enrollment> update(@Header("Authorization") String authorization,
                                                 @Body UpdateRequest request);

    /** 停用**当前这一条**（凭据就是它自己）。服务端不删行：历史用量挂在账号上。 */
    @DELETE("relay/keys") Call<Void> revoke(@Header("Authorization") String authorization);

    /**
     * **我名下所有的 relay key**（2026-02 加）。要账号 token。
     *
     * <p>为什么要它：一个账号可以注册多条（换上游、轮换凭据），而「当前这一条」
     * 只有一条的视角——用户没法回答「我到底录了什么」。这个接口就是回答那个问题的。
     *
     * <p><b>永远没有 key 明文</b>：服务端只存 sha256。所以「哪条是哪条」只能靠
     * 注册时填的备注名认（{@link Enrollment#displayName}）。
     */
    @GET("relay/keys/all") Call<KeyList> keys(@Header("Authorization") String authorization);

    /** 改**指定那一条**的上游。要账号 token，而且必须指名 uid——多条 key 时不会改错。 */
    @PATCH("relay/keys/{uid}") Call<Enrollment> updateKey(@Header("Authorization") String authorization,
                                                          @Path("uid") String uid,
                                                          @Body UpdateRequest request);

    /** 停用**指定那一条**。 */
    @DELETE("relay/keys/{uid}") Call<Void> revokeKey(@Header("Authorization") String authorization,
                                                     @Path("uid") String uid);

    /** 按模型聚合的用量，用来确认转发真的记账了。 */
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

    class EnrollmentRequest {
        @SerializedName("upstreamUrl") public final String upstreamUrl;
        @SerializedName("upstreamKey") public final String upstreamKey;
        @SerializedName("relayKey") public final String relayKey;
        @SerializedName("displayName") public final String displayName;

        public EnrollmentRequest(String upstreamUrl, String upstreamKey, String relayKey, String displayName) {
            this.upstreamUrl = upstreamUrl;
            this.upstreamKey = upstreamKey;
            this.relayKey = relayKey;
            this.displayName = displayName;
        }
    }

    class UpdateRequest {
        @SerializedName("upstreamUrl") public final String upstreamUrl;
        @SerializedName("upstreamKey") public final String upstreamKey;

        public UpdateRequest(String upstreamUrl, String upstreamKey) {
            this.upstreamUrl = upstreamUrl;
            this.upstreamKey = upstreamKey;
        }
    }

    class Enrollment {
        /** relay key 的 sha256。服务端和论坛用的是同一个字段名口径。 */
        public String uid;
        /** **只有自动生成时才有值**，而且只在这一次响应里出现。 */
        @SerializedName("relayKey") public String relayKey;
        @SerializedName("displayName") public String displayName;
        @SerializedName("upstreamUrl") public String upstreamUrl;
        /**
         * 上游主机名，由服务端算好（`GET relay/keys/all` 才带）。
         *
         * <p>为什么不让客户端自己从 `upstreamUrl` 里解析：各平台的 URL 解析对端口、
         * IPv6、末尾斜杠的边界行为都不一样，而「这条 key 指向哪个上游」是用户
         * 用来分辨多条 key 的唯一依据——解析错了这句话就是假的。
         */
        @SerializedName("upstreamHost") public String upstreamHost;
        /** 推出来的提供方；认不出来是 null（不猜）。 */
        public String provider;
        @SerializedName("hasUpstreamSecret") public boolean hasUpstreamSecret;
        @SerializedName("requestCount") public int requestCount;
        @SerializedName("createdAtEpochMillis") public long createdAtEpochMillis;
        @SerializedName("lastUsedAtEpochMillis") public Long lastUsedAtEpochMillis;
        public boolean disabled;
    }

    /** `GET relay/keys/all` 的响应。 */
    class KeyList {
        /** 账号的 `userId`（这些 key 都挂在它下面）。 */
        public String uid;
        public java.util.List<Enrollment> items;
    }

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
