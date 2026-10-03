package com.mobilegroup20.modelpilot.data.remote;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import com.google.gson.annotations.SerializedName;

/**
 * APP 账号接口（后端 `/api/account/*`）。<b>负责人：刘宗润。</b>
 *
 * <p><b>为什么是自己这一套：</b>2026-02 之前打的是团队那台机器上的
 * Java 账号服务，和本项目的后端是两套东西。这一版之后，账号由我们自己的后端发，
 * 用户是 APP 的用户，登录论坛、读用量、玩游戏的结算全都用同一个 `userId`。
 *
 * <p>四个接口对应 `backend/tokentrail_forum/account_routes.py`（服务端包名还没改，见仓库根 README）：
 * 注册、登录、查我、退出。注册<b>不发 token</b>（服务端有意为之：
 * 注册接口被脚本刷时，自动登录等于顺手帮他建一堆可用会话），
 * 所以注册成功之后要再走一次 {@link #login}。
 */
public interface AccountApi {

    /** 注册。201 返回 {@link Account}（**不含 token**）；用户名重复返回 409。 */
    @POST("account/register") Call<Account> register(@Body Credentials credentials);

    /** 登录。返回带 `token` 的 {@link Account}；用户名或密码错都是 401。 */
    @POST("account/login") Call<Account> login(@Body Credentials credentials);

    /** 当前账号。App 启动时用它确认会话还有效。**服务端是 GET，不是 POST。** */
    @GET("account/me") Call<Account> me(@Header("Authorization") String authorization);

    /** 退出。**幂等**：token 已经失效时也返回成功。 */
    @POST("account/logout") Call<Void> logout(@Header("Authorization") String authorization);

    /** 用户名 + 密码。两个接口共用同一个请求体。 */
    final class Credentials {
        @SerializedName("username") public final String username;
        @SerializedName("password") public final String password;

        public Credentials(String username, String password) {
            this.username = username; this.password = password;
        }
    }

    /**
     * 账号响应。字段名和 `account_routes.py` 一一对应。
     *
     * <p>`token` 只在登录时非空——注册不带 token，`me` 也不重复发。
     */
    final class Account {
        @SerializedName("userId") public String userId;
        @SerializedName("username") public String username;
        @SerializedName("createdAtEpochMillis") public long createdAtEpochMillis;
        @SerializedName("token") public String token;
        @SerializedName("expiresAtEpochMillis") public long expiresAtEpochMillis;
    }
}
