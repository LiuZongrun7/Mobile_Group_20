package com.mobilegroup20.modelpilot.data.remote;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import okhttp3.ResponseBody;

/**
 * APP 账号接口（后端 `/api/account/*`）。<b>负责人：刘宗润。</b>
 *
 * <p><b>为什么是自己这一套：</b>2026-02 之前打的是团队那台机器上的
 * Java 账号服务，和本项目的后端是两套东西。这一版之后，账号由我们自己的后端发，
 * 用户是 APP 的用户，登录论坛、读用量、玩游戏的结算全都用同一个 `userId`。
 *
 * <p>接口对应 `backend/tokentrail_forum/account_routes.py`（服务端包名还没改，见仓库根 README）：
 * 注册、验证邮箱、重发验证码、登录、查我、退出，以及 2026-10 加的两条密码通道：
 * 忘记密码（`forgot` + `reset`，不用登录）和改密码（`change`，要登录）。
 *
 * <p><b>注册不发 token、而且注册出来的账号一开始是没验证邮箱的</b>（服务端有意为之：
 * 注册接口被脚本刷时，自动登录等于顺手帮他建一堆可用会话）。所以注册之后本地必然
 * 还要走两步：收验证码 → {@link #verify} → {@link #login}。
 *
 * <p><b>这个契约是冻结的</b>（2026-02）：请求体字段名、错误体的 `code` 值都被 Android 端和
 * 测试依赖着。改字段名等于让所有已装的旧版本 App 静默失败，所以只能加不能改。
 */
public interface AccountApi {

    /** 注册。201 返回 {@link Account}（**不含 token**、`emailVerified=false`）；用户名重复 409。 */
    @POST("account/register") Call<Account> register(@Body Registration registration);

    /** 验证邮箱。200 返回 {@link Verified}；码错 400 `CODE_INVALID`、过期 400 `CODE_EXPIRED`。 */
    @POST("account/verify") Call<Verified> verify(@Body Verification verification);

    /**
     * 重发验证码。200 返回 {@link Sent}。
     *
     * <p>这个接口**不暴露邮箱存不存在**（防枚举）：没注册过的邮箱也返回成功，
     * 所以界面只能说「发出去了」，不能说「发到你的账号了」。
     */
    @POST("account/verify/resend") Call<Sent> resend(@Body Resend resend);

    /** 登录。返回带 `token` 的 {@link Account}；凭证错 401，邮箱没验证 403。 */
    @POST("account/login") Call<Account> login(@Body Login login);

    /** 当前账号。App 启动时用它确认会话还有效。**服务端是 GET，不是 POST。** */
    @GET("account/me") Call<Account> me(@Header("Authorization") String authorization);

    /** 退出。**幂等**：token 已经失效时也返回成功。 */
    @POST("account/logout") Call<Void> logout(@Header("Authorization") String authorization);

    /**
     * 忘记密码第一步：往邮箱发一个重设用的验证码。200 返回 {@link Sent}。
     *
     * <p><b>这个邮箱注册过没有，返回完全一样</b>——服务端有意不给探测接口（否则任何人都能
     * 拿它当「这个邮箱在不在你们站注册过」的查询器）。所以界面**不能**说「已发往你的账号」，
     * 只能说「如果这个邮箱注册过，验证码已经在路上了」这类两边都成立的话。
     *
     * <p>没配发信服务时是 503 `MAIL_NOT_CONFIGURED` / `MAIL_FAILED`，而且**所有**这种请求
     * 都返回同一个错——不能拿它反推邮箱存不存在。400 是邮箱格式（`EMAIL_REQUIRED` /
     * `EMAIL_INVALID`），429 是限流（同一邮箱 60 秒一次、一小时 5 封，带 `Retry-After`）。
     */
    @POST("account/password/forgot") Call<Sent> forgotPassword(@Body ForgotPassword forgot);

    /**
     * 忘记密码第二步：用验证码设一个新密码。200 返回 {@link PasswordReset}。
     *
     * <p><b>成功之后这个账号的所有会话都失效，包括手上这个。</b>所以调用方必须紧接着用
     * 新密码登录一次（{@link #login}），否则用户会从「密码重设成功」直接掉到未登录，
     * 而且他不会知道这是正常的。
     *
     * <p>400 `CODE_INVALID` 同时表示「码错了」和「这个邮箱不存在」——服务端有意不区分，
     * 文案也不能替它猜（猜错的那一半用户会以为自己的账号被人删了）。
     * 400 `PASSWORD_INVALID` 是新密码不合规（服务端要求 8~200 位），
     * 429 `CODE_ATTEMPTS` 是试错太多次（得重发），429 `RATE_LIMIT` 是发得太频繁。
     */
    @POST("account/password/reset") Call<PasswordReset> resetPassword(@Body PasswordResetRequest request);

    /**
     * 改密码（**要登录**）。200 返回 {@link PasswordChanged}。
     *
     * <p>和 {@link #resetPassword} 的关键区别：**当前这个会话仍然有效**，只踢掉其它设备
     * （`otherSessionsRevoked`）。所以这里不需要重新登录，界面只需要明确告诉用户
     * 「别的设备被登出了」——不说的话，他在平板上会以为是掉线。
     *
     * <p>401 `CREDENTIALS` 是当前密码不对（和登录时的 401 是同一个 code，但用户要做的事
     * 完全不同：这里是「重新输一遍当前密码」，那边是「换个账号或者找回密码」，
     * 所以界面文案必须分开，见 {@code AccountInput}）；401 无 code 是会话过期/没登录。
     */
    @POST("account/password/change") Call<PasswordChanged> changePassword(
            @Header("Authorization") String authorization, @Body PasswordChange change);

    /**
     * 注册请求体：**三个字段全必填**，邮箱是用来收验证码的那个。
     *
     * <p>`username` 是论坛里显示的昵称，和邮箱各自独立占用（409 分成
     * `USERNAME_TAKEN` 与 `EMAIL_TAKEN` 两条，界面要分开说）。
     */
    final class Registration {
        @SerializedName("email") public final String email;
        @SerializedName("username") public final String username;
        @SerializedName("password") public final String password;

        public Registration(String email, String username, String password) {
            this.email = email; this.username = username; this.password = password;
        }
    }

    /**
     * 登录请求体。
     *
     * <p><b>字段是 `identifier`，不是 `username`</b>：它装的是「邮箱或用户名」，用
     * `username` 当字段名会让「用邮箱登录」看起来像个错误用法。服务端为了兼容旧版
     * 仍然接受 `{"username","password"}`，但新代码只发 `identifier`。
     */
    final class Login {
        @SerializedName("identifier") public final String identifier;
        @SerializedName("password") public final String password;

        public Login(String identifier, String password) {
            this.identifier = identifier; this.password = password;
        }
    }

    /** 验证请求体。`code` 是邮件里那 6 位数字，服务端只认它和邮箱的配对。 */
    final class Verification {
        @SerializedName("email") public final String email;
        @SerializedName("code") public final String code;

        public Verification(String email, String code) {
            this.email = email; this.code = code;
        }
    }

    /** 重发请求体。只有邮箱——没登录也要能重发（账号还没验证过，本来就登不进去）。 */
    final class Resend {
        @SerializedName("email") public final String email;

        public Resend(String email) { this.email = email; }
    }

    /** 验证响应。**没有 token**：验证只负责把邮箱标成已验证，登录是另一件事。 */
    final class Verified {
        @SerializedName("emailVerified") public boolean emailVerified;
        @SerializedName("email") public String email;
        @SerializedName("verifiedAtEpochMillis") public long verifiedAtEpochMillis;
    }

    /** 重发响应。`sent` 只是「请求被受理了」，不代表这个邮箱真实存在（服务端有意不区分）。 */
    final class Sent {
        @SerializedName("sent") public boolean sent;
    }

    /**
     * 忘记密码第一步的请求体。字段名就是 `email`（契约里只有这一个字段）。
     *
     * <p>**这个类不能和 {@link Resend} 合并**：两个接口的路径不同、限流窗口是分开算的
     * （重发管的是「验证注册邮箱」，这里管的是「找回密码」），合并之后哪天一边改了字段
     * 另一边会跟着改，而它们并不是同一个契约。
     */
    final class ForgotPassword {
        @SerializedName("email") public final String email;

        public ForgotPassword(String email) { this.email = email; }
    }

    /**
     * 重设密码的请求体。三个字段全必填，`code` 是忘记密码那封信里的 6 位数字。
     *
     * <p>`password` 是**新**密码（服务端要求 8~200 位）。请求里没有任何 token：
     * 用户就是登不进来才走这条路的，验证码本身就证明了他能收这个邮箱的信。
     */
    final class PasswordResetRequest {
        @SerializedName("email") public final String email;
        @SerializedName("code") public final String code;
        @SerializedName("password") public final String password;

        public PasswordResetRequest(String email, String code, String password) {
            this.email = email; this.code = code; this.password = password;
        }
    }

    /**
     * 改密码的请求体。**不带邮箱/用户名**：服务端从 `Authorization` 头里的会话解出是谁，
     * 传标识符进来只会多一个「它和 token 对不上时听谁的」的问题。
     */
    final class PasswordChange {
        @SerializedName("currentPassword") public final String currentPassword;
        @SerializedName("newPassword") public final String newPassword;

        public PasswordChange(String currentPassword, String newPassword) {
            this.currentPassword = currentPassword; this.newPassword = newPassword;
        }
    }

    /**
     * 重设密码的响应。
     *
     * <p>`sessionsRevoked` 是**被作废的会话数，包括当前这个**（服务端把该账号的会话
     * 一把全撤了）。调用方据此知道接下来必须自己登一次。
     *
     * <p>Gson 缺字段不报错，所以这些字段只用来说明，**不要拿它们当「成功了没有」的依据**：
     * 成功与否看 HTTP 状态码 + `passwordChanged` 是不是 true。
     */
    final class PasswordReset {
        @SerializedName("passwordChanged") public boolean passwordChanged;
        @SerializedName("sessionsRevoked") public int sessionsRevoked;
    }

    /**
     * 改密码的响应。
     *
     * <p>字段名是 `otherSessionsRevoked`（**不是** `sessionsRevoked`）：改密码时当前会话
     * 是保留的，只有别的设备被踢。两个响应体长得像但字段名不同，抄错一个不会编译报错，
     * 只会在真机上永远显示「其他设备：0」——契约测试里钉住了这一点。
     */
    final class PasswordChanged {
        @SerializedName("passwordChanged") public boolean passwordChanged;
        @SerializedName("otherSessionsRevoked") public int otherSessionsRevoked;
    }

    /**
     * 账号响应。字段名和 `account_routes.py` 一一对应。
     *
     * <p>`token` 只在登录时非空——注册不带 token，验证接口也不发，`me` 不重复发。
     *
     * <p>`email`/`emailVerified` 是 2026-02 加邮箱注册时补的。**Gson 反序列化不会
     * 因为 JSON 里缺字段而报错**（对象字段保持 null / false），所以老服务端
     * （没有这两个字段）也不会让 App 崩，只是界面显示不出邮箱而已。
     */
    final class Account {
        @SerializedName("userId") public String userId;
        @SerializedName("username") public String username;
        @SerializedName("email") public String email;
        @SerializedName("emailVerified") public boolean emailVerified;
        @SerializedName("createdAtEpochMillis") public long createdAtEpochMillis;
        @SerializedName("token") public String token;
        @SerializedName("expiresAtEpochMillis") public long expiresAtEpochMillis;
    }

    /**
     * 统一错误体 `{"code","message"}`。
     *
     * <p>`code` 是给程序看的（界面按它选文案），`message` 是给人看的英文调试信息
     * ——**不要直接把它显示给用户**，那是服务端日志口吻，而且没有本地化。
     */
    final class Error {
        @SerializedName("code") public String code;
        @SerializedName("message") public String message;
    }

    /**
     * 从一次失败的响应里取出错误码。**拿不到就返回 null**，调用方按 HTTP 状态码兜底。
     *
     * <p>可以重复调用（底层是 peek 不是 read），见 {@link #error}。
     */
    static String errorCode(Response<?> response) {
        Error error = error(response);
        return error == null ? null : error.code;
    }

    /**
     * 解析错误体 `{"code","message"}`。JSON 坏了、是空的、或者根本不是 JSON
     * （网关返回的 HTML），都当没有，返回 null 让调用方按状态码兜底。
     *
     * <p><b>坑：`errorBody()` 是个只能读一次的流。</b>直接 `errorBody().string()` 的话，
     * 第二次调用拿到的是空串——而空串会安静地变成「没有 code」，界面于是显示一句笼统的
     * 「请求没成功」，真正的原因（比如 EMAIL_TAKEN）就这么没了。所以这里用
     * `source.request()` 把内容读进缓冲区再 clone 出来读，读多少次结果都一样。
     */
    static Error error(Response<?> response) {
        if (response == null) return null;
        ResponseBody body = response.errorBody();
        if (body == null) return null;
        try {
            // 错误体只有几十个字节。真遇到一个巨大的 body（网关塞了个 HTML 错误页），
            // 与其为了它把内存读爆，不如干脆不解析——状态码兜底照样能给用户一句话。
            if (body.contentLength() > 16 * 1024) return null;
            okio.BufferedSource source = body.source();
            source.request(Long.MAX_VALUE);
            String json = source.getBuffer().clone().readUtf8();
            return new Gson().fromJson(json, Error.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
