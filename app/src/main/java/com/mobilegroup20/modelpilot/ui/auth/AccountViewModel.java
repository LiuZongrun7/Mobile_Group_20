package com.mobilegroup20.modelpilot.ui.auth;

import android.app.Application;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import com.mobilegroup20.modelpilot.BuildConfig;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.data.AccountSession;
import com.mobilegroup20.modelpilot.data.remote.AccountApi;
import com.mobilegroup20.modelpilot.data.remote.ForumTestSessionApi;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import okhttp3.OkHttpClient;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;
import java.util.concurrent.TimeUnit;

/**
 * 账号的界面逻辑：邮箱注册、验证码验证、登录、退出、论坛测试身份。<b>负责人：刘宗润。</b>
 *
 * <p>打的是**我们自己的后端** `/api/account/*`（{@code account_routes.py}）。
 * 2026-02 之前这里打的是团队那台机器上的 Java 账号服务，那时「App 用户」
 * 和「论坛用户」还是两拨人；现在账号就是 App 的账号，登录论坛、读用量、
 * 游戏结算共用同一个 `userId`。
 *
 * <p><b>注册是三步，不是一步</b>（2026-02 起加邮箱验证）：
 * {@code register} 建号并发一封验证码 → 用户填码 → {@code verify} 通过后才允许
 * {@code login}（服务端对未验证的账号直接 403 `EMAIL_UNVERIFIED`）。
 * 所以「注册成功」**不等于**「已登录」，中间那个状态必须让用户看得见、
 * 有地方填码、有地方重发——这就是这个 ViewModel 里 `REGISTERED` 存在的理由。
 *
 * <p><b>注册接口不发 token</b>（服务端有意为之：注册接口被脚本刷时自动登录等于
 * 顺手帮他建一堆可用会话），验证接口也不发。验证通过后本地接着登一次，
 * 用户视角就是「验证完就进来了」。这一步放在这里而不是让用户再点一次。
 *
 * <p><b>界面分两段</b>：表单段（邮箱/用户名/密码）和验证码段（验证码 + 重发）。
 * 哪一段由 {@link #stage()} 决定，而它存在 `SavedStateHandle` 里——转屏之后
 * 对话框是重建的，段位不存下来用户会被弹回表单段，手里的验证码就白填了。
 */
public final class AccountViewModel extends AndroidViewModel {
    /** 第一段：邮箱、用户名、密码。 */
    public static final String STAGE_FORM = "FORM";
    /** 第二段：验证码。注册成功、或者登录被 403 EMAIL_UNVERIFIED 拒了之后进来。 */
    public static final String STAGE_VERIFY = "VERIFY";

    private static final String TAG = "Account";

    /** 服务端说「同一邮箱 60 秒内只能发一次码」，界面的重发冷却就按它走。 */
    private static final long RESEND_COOLDOWN_MILLIS = 60_000L;

    public final MutableLiveData<String> state = new MutableLiveData<>("IDLE");
    /** `INVALID` 时要显示哪一条本地校验的理由（字符串资源 id）。 */
    public final MutableLiveData<Integer> invalidReason = new MutableLiveData<>(0);
    /**
     * 服务端失败翻译出来的那句话（字符串资源 id）。用 {@link AccountInput#failure} 生成，
     * **不在界面里散着写**：同一句「邮箱已被占用」有注册和登录两条路径会用到。
     */
    public final MutableLiveData<Integer> notice = new MutableLiveData<>(0);
    /** 失败之后界面该做什么，取 {@link AccountInput#ACTION_NONE} 等常量。 */
    public final MutableLiveData<String> action = new MutableLiveData<>(AccountInput.ACTION_NONE);
    /**
     * 重发验证码的冷却结束时刻（`SystemClock.elapsedRealtime()`，0 = 现在就能重发）。
     *
     * <p><b>故意不放 `SavedStateHandle`：</b>elapsedRealtime 是从开机算起的，
     * 重启手机就归零，存下来会让冷却变成「几十年以后」或者立刻失效，两个方向都错。
     * 进程被杀之后按 0 处理（可以立刻点重发）也没关系——真在窗口里的话服务端会 429，
     * 那时候再按它给的 `Retry-After` 重新计时。
     */
    public final MutableLiveData<Long> resendReadyAt = new MutableLiveData<>(0L);

    private final SavedStateHandle saved;
    private final AccountSession session;
    private Call<?> pending;
    private volatile int generation;
    private AccountApi api;
    private ForumTestSessionApi testApi;
    /**
     * 刚填过的密码，**只在内存里**。
     *
     * <p>验证成功之后要自动登录一次，那时候用户已经不在表单段了，密码框也看不到了，
     * 所以必须留一份在手边。它是普通字段而不是 `SavedStateHandle` 里的键：后者会被
     * 系统写进磁盘上的 Bundle，等于把密码明文留在手机上（`AccountDialog` 里那个
     * `setSaveEnabled(false)` 防的就是同一件事）。代价是进程被杀之后这份密码没了，
     * 那时候走 {@link #STAGE_FORM} 让用户重新输一次，不做任何假装。
     */
    private volatile String pendingPassword;

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

    // ---------------------------------------------------------------- 表单字段
    public String username() { String value = saved.get("username"); return value == null ? "" : value; }
    public void username(String value) { saved.set("username", value); }

    /** 邮箱。转屏之后第二段要靠它拼「验证码已发往 …」，所以和用户名一样存起来。 */
    public String email() { String value = saved.get("email"); return value == null ? "" : value; }
    public void email(String value) { saved.set("email", value); }

    /** 当前在表单段还是验证码段。 */
    public String stage() { String value = saved.get("stage"); return value == null ? STAGE_FORM : value; }

    /** 当前是「注册」还是「登录」。默认登录；切换只改界面，不发请求。 */
    public boolean registerMode() { Boolean value = saved.get("registerMode"); return value != null && value; }
    public void registerMode(boolean value) { saved.set("registerMode", value); }

    public boolean signedIn() { return session.signedIn(); }
    public String name() { return session.accountName(); }
    public boolean forumTest() { return session.forumTest(); }

    /**
     * 「邮箱写错了？返回修改」：回到表单段，把上一条提示清掉，别的什么都不动。
     *
     * <p>坑：注册成功之后改邮箱再点一次「注册」会**建出第二个账号**——契约里没有「改邮箱」
     * 接口，服务端也不可能把已经发出去的验证码改个地址。所以第一个（邮箱写错的）账号
     * 就留在服务端占着用户名和邮箱。这是有意的取舍：与其把用户卡在一个收不到信的邮箱上，
     * 不如让他换一个邮箱重来一次，界面会把「用户名/邮箱已被占用」如实说出来。
     */
    public void backToForm() {
        saved.set("stage", STAGE_FORM);
        notice.setValue(0);
        action.setValue(AccountInput.ACTION_NONE);
        state.setValue("IDLE");
    }

    // ---------------------------------------------------------------- 论坛测试身份
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
                Log.w(TAG, "forum test session failed: " + error, error);
                if (request == generation) state.setValue("NETWORK");
            }
        });
    }

    // ---------------------------------------------------------------- 登录
    /**
     * 用邮箱或用户名登录。
     *
     * <p>没验证邮箱的账号会被服务端 403 拒掉。那不是「登录失败」而是「还差一步」：
     * 这里会把界面切到验证码段，并且把标识符里的邮箱带过去——用户刚刚就是用它登的。
     */
    public void signIn(String password) {
        if (busy()) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        String email = email().trim(), username = username().trim();
        int problem = AccountInput.loginProblem(email, username, password);
        if (problem != 0) { state.setValue("INVALID"); invalidReason.setValue(problem); return; }
        String identifier = AccountInput.loginIdentifier(email, username);
        pendingPassword = password;
        int request = ++generation;
        state.setValue("BUSY");
        Call<AccountApi.Account> login = api.login(new AccountApi.Login(identifier, password));
        pending = login;
        login.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.Account> call,
                                             @NonNull Response<AccountApi.Account> response) {
                if (request != generation) return;
                AccountApi.Account body = response.body();
                if (!response.isSuccessful() || body == null
                        || body.token == null || body.token.isEmpty()) {
                    fail(request, response, email);
                    return;
                }
                store(body, request);
            }
            @Override public void onFailure(@NonNull Call<AccountApi.Account> call, @NonNull Throwable error) {
                failed(request, error, email);
            }
        });
    }

    // ---------------------------------------------------------------- 注册
    /**
     * 注册：建号 → 服务端发验证码 → 进验证码段。**不发 token，也不在这里登录。**
     *
     * <p>2026-02 之前是「注册完立刻登一次」，那时没有邮箱验证。现在服务端对未验证的账号
     * 直接 403，所以注册的终点变成了「等用户填码」，界面必须停在那一步，
     * 不能显示成已登录——用户会以为好了，然后被论坛的 401 打回来。
     */
    public void register(String password) {
        if (busy()) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        String email = email().trim(), username = username().trim();
        int problem = AccountInput.registrationProblem(email, username, password);
        if (problem != 0) { state.setValue("INVALID"); invalidReason.setValue(problem); return; }
        pendingPassword = password;
        int request = ++generation;
        state.setValue("BUSY");
        Call<AccountApi.Account> call = api.register(new AccountApi.Registration(email, username, password));
        pending = call;
        call.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.Account> call,
                                             @NonNull Response<AccountApi.Account> response) {
                if (request != generation) return;
                AccountApi.Account body = response.body();
                if (!response.isSuccessful() || body == null) {
                    // 503 MAIL_NOT_CONFIGURED 走的就是这条路：**账号没有被创建**。
                    // 文案由 AccountInput 挑（会说清「没注册成功」），这里不额外加工，
                    // 免得把服务端的原话盖掉。
                    fail(request, response, email);
                    return;
                }
                if (body.token != null && !body.token.isEmpty()) { store(body, request); return; }
                if (body.emailVerified) {
                    // 契约说注册出来的账号一定是未验证的。万一服务端策略变了（比如把邮箱
                    // 验证设成可选），这里要能自己收尾，而不是把用户丢在一个「验证什么？」的界面上。
                    autoSignIn(request, body.email == null || body.email.isEmpty() ? email : body.email);
                    return;
                }
                enterVerifyStage(email, true);
                state.setValue("REGISTERED");
            }
            @Override public void onFailure(@NonNull Call<AccountApi.Account> call, @NonNull Throwable error) {
                failed(request, error, email);
            }
        });
    }

    // ---------------------------------------------------------------- 验证码
    /**
     * 提交验证码。通过之后自动用内存里那份密码登录一次。
     *
     * <p>没有密码（进程被杀过、或者别的原因丢了）时**不假装成功**：退回登录段，
     * 由界面告诉用户「邮箱验证成功了，请用密码登录一次」。
     */
    public void verify(String code) {
        if (busy()) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        String email = email().trim();
        int problem = AccountInput.codeProblem(code);
        if (problem != 0) { state.setValue("INVALID"); invalidReason.setValue(problem); return; }
        if (email.isEmpty()) {
            // 到不了这里（进验证段一定带着邮箱），但真有这种情况也只能让用户回去填——
            // 没有邮箱，服务端不知道这个码是谁的。
            notice.setValue(R.string.account_email_empty);
            action.setValue(AccountInput.ACTION_NONE);
            state.setValue("FAILED");
            return;
        }
        int request = ++generation;
        state.setValue("BUSY");
        String digits = code.replaceAll("\\s", "");
        Call<AccountApi.Verified> call = api.verify(new AccountApi.Verification(email, digits));
        pending = call;
        call.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.Verified> call,
                                             @NonNull Response<AccountApi.Verified> response) {
                if (request != generation) return;
                AccountApi.Verified body = response.body();
                // 200 但没确认验证：当成失败说出来，不能当成过了——不然接下来那次登录
                // 必然 403，用户看到的是「验证成功」和「登录被拒」两句自相矛盾的话。
                if (!response.isSuccessful() || body == null || !body.emailVerified) {
                    if (response.isSuccessful()) {
                        notice.setValue(R.string.account_verify_unconfirmed);
                        action.setValue(AccountInput.ACTION_NONE);
                        state.setValue("FAILED");
                    } else fail(request, response, email);
                    return;
                }
                String password = pendingPassword;
                if (password == null || password.isEmpty()) {
                    // 验证是过了的，只是本地没有密码可用。**别把「已验证」这件事丢掉**：
                    // 退回登录段，让用户用密码进来（服务端那边这个邮箱已经是已验证的了）。
                    saved.set("stage", STAGE_FORM);
                    saved.set("registerMode", false);
                    notice.setValue(0);
                    state.setValue("VERIFIED_SIGN_IN_REQUIRED");
                    return;
                }
                autoSignIn(request, body.email == null || body.email.isEmpty() ? email : body.email);
            }
            @Override public void onFailure(@NonNull Call<AccountApi.Verified> call, @NonNull Throwable error) {
                failed(request, error, email);
            }
        });
    }

    /**
     * 重发验证码。
     *
     * <p>服务端**不暴露邮箱存不存在**（防枚举），所以成功只代表「请求被受理」。
     * 界面上那句话也不能说成「已发到你的账号」——没注册过的邮箱同样返回 200。
     */
    public void resend() {
        if (busy()) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        if (remainingResendMillis() > 0) return; // 冷却里本来就点不动，这里再兜一道
        String email = email().trim();
        int problem = AccountInput.emailProblem(email);
        if (problem != 0) { state.setValue("INVALID"); invalidReason.setValue(problem); return; }
        int request = ++generation;
        state.setValue("BUSY");
        Call<AccountApi.Sent> call = api.resend(new AccountApi.Resend(email));
        pending = call;
        call.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.Sent> call,
                                             @NonNull Response<AccountApi.Sent> response) {
                if (request != generation) return;
                AccountApi.Sent body = response.body();
                if (!response.isSuccessful() || body == null || !body.sent) {
                    // 429 会带 `Retry-After`：服务端比我们清楚还要等多久，按它说的计时
                    // （本地倒计时只是个估计，以服务端为准才不会连着撞墙）。
                    if (response.code() == 429) {
                        long wait = AccountInput.retryAfterMillis(
                                response.headers().get("Retry-After"));
                        resendReadyAt.setValue(SystemClock.elapsedRealtime() + wait);
                    }
                    if (response.isSuccessful()) {
                        notice.setValue(R.string.account_resend_failed);
                        action.setValue(AccountInput.ACTION_NONE);
                        state.setValue("FAILED");
                    } else fail(request, response, email);
                    return;
                }
                resendReadyAt.setValue(SystemClock.elapsedRealtime() + RESEND_COOLDOWN_MILLIS);
                notice.setValue(R.string.account_resend_done);
                action.setValue(AccountInput.ACTION_NONE);
                state.setValue("CODE_SENT");
            }
            @Override public void onFailure(@NonNull Call<AccountApi.Sent> call, @NonNull Throwable error) {
                fail(request, null, email);
            }
        });
    }

    /** 冷却还剩多少毫秒；0 表示现在就能重发。 */
    public long remainingResendMillis() {
        Long ready = resendReadyAt.getValue();
        if (ready == null || ready <= 0) return 0;
        return Math.max(0, ready - SystemClock.elapsedRealtime());
    }

    // ---------------------------------------------------------------- 收尾
    /** 验证通过之后的自动登录。用的还是内存里那份密码。 */
    private void autoSignIn(int request, String email) {
        String password = pendingPassword;
        if (password == null || password.isEmpty()) {
            // 走到这儿说明验证是通过的、但本地没有密码（见 verify() 里那条注释）：
            // 退回登录段让用户自己登一次，**不能显示成已经登录**。
            saved.set("stage", STAGE_FORM);
            saved.set("registerMode", false);
            state.setValue("VERIFIED_SIGN_IN_REQUIRED");
            return;
        }
        state.setValue("VERIFIED");
        Call<AccountApi.Account> login = api.login(new AccountApi.Login(email, password));
        pending = login;
        login.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.Account> call,
                                             @NonNull Response<AccountApi.Account> response) {
                if (request != generation) return;
                AccountApi.Account body = response.body();
                if (!response.isSuccessful() || body == null
                        || body.token == null || body.token.isEmpty()) {
                    // 邮箱已经验证成功了，这里失败的是登录。**不能退回验证码段**：
                    // 那个码已经用掉了，用户再填多少次都只会得到 CODE_INVALID。
                    AccountInput.Failure failure = AccountInput.failure(response.code(),
                            AccountApi.errorCode(response));
                    notice.setValue(failure.message);
                    action.setValue(failure.action);
                    saved.set("stage", STAGE_FORM);
                    saved.set("registerMode", false);
                    state.setValue("VERIFIED_SIGN_IN_REQUIRED");
                    return;
                }
                store(body, request);
            }
            @Override public void onFailure(@NonNull Call<AccountApi.Account> call, @NonNull Throwable error) {
                if (request != generation) return;
                AccountInput.Failure failure = AccountInput.failure(0, null);
                notice.setValue(failure.message);
                action.setValue(AccountInput.ACTION_NONE);
                saved.set("stage", STAGE_FORM);
                saved.set("registerMode", false);
                state.setValue("VERIFIED_SIGN_IN_REQUIRED");
            }
        });
    }

    /** 把会话存下来，并把论坛那边切到当前身份。存储失败**不当登录成功**。 */
    private void store(AccountApi.Account body, int request) {
        if (request != generation) return;
        try {
            session.save(body.token, body.userId, body.username, body.email, body.emailVerified);
        } catch (Exception ignored) {
            state.setValue("STORAGE"); return;
        }
        pendingPassword = null; // 会话已经落地，密码不用再留着了
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

    /**
     * 把一次失败落成界面状态。`emailForVerifyStage` 是用户刚才在表单里填的邮箱
     * （可能为空）——登录被 403 拒了的时候，界面要带着它切到验证码段。
     */
    /**
     * 请求在**传输层**就失败了：DNS、TLS、超时、连接被掐。
     *
     * <p><b>必须把异常本身记进 logcat。</b>界面上只能显示一句"连不上、检查网络"——
     * 那句话对排障等于零，而"TLS 握手失败 / 读超时 / 域名解析不了"三种原因的修法完全不同。
     * 更要紧的是：连接根本没建立起来时**服务端的 access log 里什么都不会留下**，
     * 手机上这条日志是唯一的线索（真机上排查"点注册没反应"就是这么查的）。
     */
    private void failed(int request, Throwable error, String emailForVerifyStage) {
        Log.w(TAG, "account request failed: " + error, error);
        fail(request, null, emailForVerifyStage);
    }

    private void fail(int request, Response<?> response, String emailForVerifyStage) {
        if (request != generation) return;
        AccountInput.Failure failure = response == null
                ? AccountInput.failure(0, null)
                : AccountInput.failure(response.code(), AccountApi.errorCode(response));
        notice.setValue(failure.message);
        action.setValue(failure.action);
        // 「这个码已经废了」的两条路：过期（服务端那边早过了 60 秒窗口，可以立刻重发）和
        // 试错次数用尽（多半还在窗口里，保留冷却，等它走完再让点）。
        if (AccountInput.ACTION_RESEND_NOW.equals(failure.action)) resendReadyAt.setValue(0L);
        if (AccountInput.ACTION_VERIFY.equals(failure.action)) {
            String email = emailForVerifyStage == null ? "" : emailForVerifyStage.trim();
            if (email.isEmpty()) {
                // 用户是用**用户名**登录的，返回体里没有邮箱（错误体只有 code/message），
                // 我们不知道验证码该发到哪儿。这时候不能装作能验证：让他在邮箱栏填上
                // 注册时用的邮箱再点一次登录，下一次我们就带着邮箱进验证段了。
                notice.setValue(R.string.account_unverified_ask_email);
                action.setValue(AccountInput.ACTION_NONE);
            } else {
                enterVerifyStage(email, true);
            }
        }
        state.setValue("FAILED");
    }

    /**
     * 进验证码段。
     *
     * @param cooldown 是否从「现在」开始算 60 秒重发冷却。注册成功时服务端刚发过码，
     *                 这个估计是准的；登录被 403 拒的时候我们不知道上一封是什么时候发的，
     *                 保守地按 60 秒算——宁可让用户等一下，也不要让他点了立刻撞 429
     *                 （那时界面只能再说一句「太频繁了」，看起来像是 App 在瞎拦）。
     *                 服务端明确说「码过期了」时会把这个冷却清掉。
     */
    private void enterVerifyStage(String email, boolean cooldown) {
        saved.set("email", email);
        saved.set("stage", STAGE_VERIFY);
        if (cooldown) resendReadyAt.setValue(SystemClock.elapsedRealtime() + RESEND_COOLDOWN_MILLIS);
        else resendReadyAt.setValue(0L);
        action.setValue(AccountInput.ACTION_NONE);
    }

    private boolean busy() { return "BUSY".equals(state.getValue()); }

    @Override protected void onCleared() { ++generation; if (pending != null) pending.cancel(); }
}
