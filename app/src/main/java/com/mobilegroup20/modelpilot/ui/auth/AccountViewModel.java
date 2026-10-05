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
    /**
     * 第三段：忘记密码。**一段里做两小步**（进来自动发码 → 填码 + 新密码），
     * 不再往下分段。
     *
     * <p>为什么不像注册那样拆成两段：注册的「表单段」是用户自己填出来的，他回到那儿
     * 还有事可做（改邮箱重来）。忘记密码的人回到表单段只有一件事——重新点一次
     * 「忘记密码？」，纯属迷路。而且这一屏的东西本来就少（一个码 + 两个密码框），
     * 挤在一段里反而看得全：上面是「码发到哪了」，下面是「填码设新密码」。
     */
    public static final String STAGE_RESET = "RESET";
    /**
     * 第四段：已登录状态下改密码。只有登录着才进得来。
     *
     * <p>它和 {@link #STAGE_FORM} 在已登录时长得一样（都是那个「已登录：某某」的面板），
     * 区别是正面按钮从「退出」变成「保存新密码」、并且多出三个密码框。
     */
    public static final String STAGE_CHANGE = "CHANGE";

    private static final String TAG = "Account";

    /** 服务端说「同一邮箱 60 秒内只能发一次码」，界面的重发冷却就按它走。 */
    private static final long RESEND_COOLDOWN_MILLIS = 60_000L;

    public final MutableLiveData<String> state = new MutableLiveData<>("IDLE");
    /** `INVALID` 时要显示哪一条本地校验的理由（字符串资源 id）。 */
    public final MutableLiveData<Integer> invalidReason = new MutableLiveData<>(0);
    /**
     * 要显示给用户的那句话（**已经格式化好的文字**，空串 = 没什么好说的）。
     * 用 {@link AccountInput#failure} 生成，**不在界面里散着写**：
     * 同一句「邮箱已被占用」有注册和登录两条路径会用到。
     *
     * <p><b>为什么放的是文字而不是字符串资源 id：</b>带参数的那几句
     * （"其他设备上的 %1$d 个登录已失效"、"已登录：%1$s"）如果只传 id，
     * 界面那边 `getString(id)` **不会做格式化**，屏幕上就会原样印出 `%1$d`
     * ——2026-10-05 真机上两处都是这么挂的。文案的参数只有产生它的地方知道，
     * 所以格式化必须发生在产生它的地方（这里）。
     */
    public final MutableLiveData<CharSequence> notice = new MutableLiveData<>("");
    /** 失败之后界面该做什么，取 {@link AccountInput#ACTION_NONE} 等常量。 */
    public final MutableLiveData<String> action = new MutableLiveData<>(AccountInput.ACTION_NONE);
    /**
     * 「正在自动登录」这一步**为什么**会发生（字符串资源 id，0 = 没什么好说的）。
     *
     * <p>自动登录有两条来路：邮箱验证通过（注册那条）和密码重设成功（忘记密码那条）。
     * 两件事对用户的意义完全不同——一个是他刚验证完邮箱，一个是他刚把密码改掉、
     * 别的设备都被踢了——所以不能说同一句「邮箱验证成功，正在登录…」。
     *
     * <p>它是一个独立的 LiveData 而不是复用 `notice`：`notice` 是**失败**的文案，
     * 界面在失败分支里读它；成功分支读这个。混用一个字段的话，「上一条失败提示」
     * 会在下一次成功时突然冒出来当成功提示用。
     */
    public final MutableLiveData<Integer> autoSignInReason = new MutableLiveData<>(0);
    /**
     * 忘记密码那屏的「验证码已发往 <邮箱>」和「如果这个邮箱注册过…」用的地址。
     *
     * <p>**优先用表单里的邮箱**（`email()`），没有才退回会话里存的账号邮箱：前者是用户
     * 刚刚亲手敲进去的，他正对着它核对；后者是上次登录时服务端告诉我们的，可能已经过时。
     */
    public final MutableLiveData<String> resetAddress = new MutableLiveData<>("");
    /**
     * 忘记密码那屏用户敲的验证码（**只在内存里**）。
     *
     * <p>为什么不复用第一段那个 `code` 键：验证码段的码和忘记密码的码是两次不同的发送，
     * 共用一个键的话，用户在验证码段填了一半再去点「忘记密码？」，那个半截的码会跟着
     * 飘过来——而他根本没收到过那个码。而且两个框在不同容器里，本来也没法共用一个控件。
     *
     * <p>那一屏的**新密码**不在这里：它是密码，和登录密码一个待遇，
     * 只在对话框自己的 EditText 里活到提交那一刻（见 `AccountDialog`）。
     */
    private String resetCode = "";
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

    /** 现在是不是「验证码那一段」（注册/登录被拒之后）。已登录和免账号测试永远不是。 */
    public boolean verifyStage() { return !signedIn() && STAGE_VERIFY.equals(stage()); }

    /** 忘记密码那屏要显示的邮箱：用户填的优先，没填才用会话里记着的。 */
    public String resetAddress() {
        String value = resetAddress.getValue();
        return value == null ? "" : value;
    }

    /** 忘记密码那屏的验证码。**只在内存里**：转屏之后要重敲，理由和密码一样。 */
    public String resetCode() { return resetCode; }
    public void resetCode(String value) { resetCode = value == null ? "" : value; }

    /** 当前是「注册」还是「登录」。默认登录；切换只改界面，不发请求。 */
    public boolean registerMode() { Boolean value = saved.get("registerMode"); return value != null && value; }
    public void registerMode(boolean value) { saved.set("registerMode", value); }

    public boolean signedIn() { return session.signedIn(); }
    public String name() { return session.accountName(); }
    public boolean forumTest() { return session.forumTest(); }

    /**
     * 会话里存的账号邮箱（没登录、或者是没有这个字段的老快照，都是空串）。
     *
     * <p>忘记密码那屏要用它给已登录的用户回填「验证码发往哪儿」——
     * **不能拿它当登录表单的邮箱**：那个框是用户自己的输入，拿会话去覆盖它，
     * 用户就没法用另一个邮箱登录了。
     */
    public String accountEmail() { return session.accountEmail(); }

    /** 现在是不是「已登录 + 改了密码那一步」。已登录时表单段和它有别，所以单独问一句。 */
    public boolean changeStage() { return signedIn() && STAGE_CHANGE.equals(stage()); }

    /** 现在是不是忘记密码那一段。 */
    public boolean resetStage() { return !signedIn() && STAGE_RESET.equals(stage()); }

    /**
     * 「邮箱写错了？返回修改」：回到表单段，把上一条提示清掉，别的什么都不动。
     *
     * <p>坑：注册成功之后改邮箱再点一次「注册」会**建出第二个账号**——契约里没有「改邮箱」
     * 接口，服务端也不可能把已经发出去的验证码改个地址。所以第一个（邮箱写错的）账号
     * 就留在服务端占着用户名和邮箱。这是有意的取舍：与其把用户卡在一个收不到信的邮箱上，
     * 不如让他换一个邮箱重来一次，界面会把「用户名/邮箱已被占用」如实说出来。
     *
     * <p>从忘记密码那屏返回也走这里：`autoSignInReason` 一并清掉，
     * 否则上一次「密码已重设，正在登录…」会留在界面上变成一句已经不成立的话。
     */
    public void backToForm() {
        saved.set("stage", STAGE_FORM);
        notice.setValue("");
        action.setValue(AccountInput.ACTION_NONE);
        autoSignInReason.setValue(0);
        state.setValue("IDLE");
    }

    /**
     * 进「修改密码」那一步（**只有已登录才进得来**）。
     *
     * <p>没登录时什么都不做：这个入口在未登录的界面上根本不存在，真被点到只能是
     * 状态刚好变了（比如另一个页面把会话清了），那时候把界面弹到别处只会更乱。
     */
    public void changePasswordStage() {
        if (!signedIn()) return;
        saved.set("stage", STAGE_CHANGE);
        notice.setValue("");
        action.setValue(AccountInput.ACTION_NONE);
        autoSignInReason.setValue(0);
        state.setValue("IDLE");
    }

    // ---------------------------------------------------------------- 忘记密码
    /**
     * 「忘记密码？」：进重设段，并且**立刻发一次码**。
     *
     * <p>为什么自动发：用户点这个入口的意思就是「我收不到/我不知道密码，给我一条路」，
     * 再让他点一次「发送验证码」是多余的一步。发失败也照实说（见
     * {@link #sendResetCode(boolean)}），不假装发出去了。
     *
     * <p>邮箱为空（用用户名登录的用户）时**不发请求**：服务端只会回 `EMAIL_REQUIRED`，
     * 而用户真正要做的是回去把邮箱填上，所以这里直接告诉他这件事。
     */
    public void enterResetStage() {
        if (signedIn()) return;
        // 每次进来都从一个空的验证码框开始：上一轮那个码是**另一次**发送的（服务端一次只认
        // 最新那封），留着它用户会以为"我已经填好了"，点了重设却拿到 CODE_INVALID。
        // （新密码框不用清：它在对话框里被 setSaveEnabled(false)，转屏回去本来就是空的。）
        resetCode("");
        saved.set("stage", STAGE_RESET);
        notice.setValue("");
        action.setValue(AccountInput.ACTION_NONE);
        autoSignInReason.setValue(0);
        String address = resetEmail();
        resetAddress.setValue(address);
        if (address.isEmpty()) {
            // 界面上这句话显示在「验证码已发往 …」那个位置旁边，所以它得说清**去哪儿填邮箱**。
            invalidReason.setValue(R.string.account_reset_need_email);
            notice.setValue(getApplication().getString(R.string.account_reset_need_email));
            state.setValue("REQUIRED");
            return;
        }
        state.setValue("IDLE");
        sendResetCode(true);
    }

    /**
     * 发重设用的验证码。`fromAutomatic` 只用来区分「重发按钮点的」——两者的请求完全一样，
     * 区别只在冷却：自动那一次是我们刚替用户点的，必须按 60 秒计；手动这次已经由
     * 按钮的倒计时把关了（`remainingResendMillis() > 0` 时按钮点不动，这里再兜一道）。
     */
    private void sendResetCode(boolean fromAutomatic) {
        if (busy()) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        if (!fromAutomatic && remainingResendMillis() > 0) return;
        String email = resetEmail();
        int problem = AccountInput.emailProblem(email);
        if (problem != 0) {
            // 本地就能看出这个地址发不出去（空/少个 @）。**不吞掉**：说清是哪一种，
            // 用户才知道是回上一层填邮箱，还是把地址改对。
            notice.setValue(getApplication().getString(problem));
            action.setValue(AccountInput.ACTION_NONE);
            state.setValue("FAILED");
            return;
        }
        resetAddress.setValue(email);
        int request = ++generation;
        state.setValue("BUSY");
        Call<AccountApi.Sent> call = api.forgotPassword(new AccountApi.ForgotPassword(email));
        pending = call;
        call.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.Sent> call,
                                             @NonNull Response<AccountApi.Sent> response) {
                if (request != generation) return;
                AccountApi.Sent body = response.body();
                if (!response.isSuccessful() || body == null || !body.sent) {
                    // 429 带 `Retry-After`：服务端比本地倒计时清楚还要等多久
                    // （同一邮箱 60 秒一次、一小时 5 封）。按它重新计时才不会连着撞墙。
                    if (response.code() == 429) {
                        long wait = AccountInput.retryAfterMillis(response.headers().get("Retry-After"));
                        resendReadyAt.setValue(SystemClock.elapsedRealtime() + wait);
                    }
                    if (response.isSuccessful()) {
                        // 200 但没说发出去了：当成失败说出来。当成成功的话，用户会去
                        // 一个永远不会来新邮件的收件箱里翻。
                        notice.setValue(getApplication().getString(R.string.account_reset_failed));
                        action.setValue(AccountInput.ACTION_NONE);
                        state.setValue("FAILED");
                    } else failFor(request, response, AccountInput.Failure.OP_FORGOT_PASSWORD);
                    return;
                }
                // **200 只说「请求被受理了」**：服务端对没注册过的邮箱返回的是一模一样的
                // 响应（防枚举）。所以这里能确定的只有「如果是注册过的邮箱，码已经发了」，
                // 界面文案也必须这么说（account_reset_code_sent）。
                resendReadyAt.setValue(SystemClock.elapsedRealtime() + RESEND_COOLDOWN_MILLIS);
                notice.setValue(getApplication().getString(R.string.account_reset_code_sent));
                action.setValue(AccountInput.ACTION_NONE);
                state.setValue("CODE_SENT");
            }
            @Override public void onFailure(@NonNull Call<AccountApi.Sent> call, @NonNull Throwable error) {
                if (request != generation) return;
                Log.w(TAG, "reset code request failed: " + error, error);
                failFor(request, null, AccountInput.Failure.OP_FORGOT_PASSWORD);
            }
        });
    }

    /** 「重发验证码」按钮：和自动那一次发的是同一个请求，只是不用再判 fromAutomatic。 */
    public void resendResetCode() { sendResetCode(false); }

    /**
     * 提交「验证码 + 新密码」，重设密码。
     *
     * <p>本地只能判两件事：码的形状和两次密码是否一致（还有新密码的长度）。码对不对、
     * 这个邮箱存不存在都要问服务端，而且服务端**故意用同一个 `CODE_INVALID` 回答这两种情况**。
     *
     * <p>成功后必须立刻用新密码登录一次：服务端会把该账号**所有**会话作废，包括手上这个
     * （`sessionsRevoked` 里就含它）。不自动登的话，用户会从「密码重设成功」直接掉到
     * 未登录状态，而他会以为是自己又做错了什么。
     */
    public void resetPassword(String code, String newPassword, String confirm) {
        if (busy()) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        String email = resetEmail();
        AccountInput.ResetProblem problem =
                AccountInput.resetPasswordProblem(email, code, newPassword, confirm);
        if (problem.message != 0) {
            // 邮箱为空是唯一一件用户没法在这一屏修好的事（他得回上一层填），
            // 用 REQUIRED 让界面把这句话和「验证码发往哪儿」一起摆正。
            state.setValue(problem.askEmail ? "REQUIRED" : "INVALID");
            invalidReason.setValue(problem.message);
            notice.setValue(getApplication().getString(problem.message));
            return;
        }
        // 新密码先拿在手里：重设成功之后要拿它自动登录，而那时候用户已经不在这一屏了。
        // **只放内存**，理由见 pendingPassword 的注释。
        pendingPassword = newPassword;
        notice.setValue("");
        action.setValue(AccountInput.ACTION_NONE);
        int request = ++generation;
        state.setValue("BUSY");
        Call<AccountApi.PasswordReset> call = api.resetPassword(new AccountApi.PasswordResetRequest(
                email, code.replaceAll("\\s", ""), newPassword));
        pending = call;
        call.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.PasswordReset> call,
                                             @NonNull Response<AccountApi.PasswordReset> response) {
                if (request != generation) return;
                AccountApi.PasswordReset body = response.body();
                // `passwordChanged` 要一起看：200 + body 缺字段（老服务端/网关塞了个空体）
                // 时不能当成改成功了——那会让用户以为密码换了，然后拿着新密码登不进来。
                if (!response.isSuccessful() || body == null || !body.passwordChanged) {
                    if (response.isSuccessful()) {
                        notice.setValue(getApplication().getString(R.string.account_reset_failed));
                        action.setValue(AccountInput.ACTION_NONE);
                        state.setValue("FAILED");
                    } else {
                        failFor(request, response, AccountInput.Failure.OP_FORGOT_PASSWORD);
                    }
                    pendingPassword = null;
                    return;
                }
                // 码已经用掉了，不能留在这一屏（再点一次必然 CODE_INVALID）。
                // 冷却清掉：这条路上最可能发生的下一步是「登录失败 → 再试一次重设」。
                resendReadyAt.setValue(0L);
                autoSignInReason.setValue(R.string.account_reset_done);
                autoSignIn(request, email, true);
            }
            @Override public void onFailure(@NonNull Call<AccountApi.PasswordReset> call, @NonNull Throwable error) {
                if (request != generation) return;
                Log.w(TAG, "password reset failed: " + error, error);
                pendingPassword = null;
                failFor(request, null, AccountInput.Failure.OP_FORGOT_PASSWORD);
            }
        });
    }

    // ---------------------------------------------------------------- 改密码（已登录）
    /**
     * 已登录时改密码。
     *
     * <p>和重设不同，**这里不会掉登录**：服务端只作废其它会话，当前 token 继续有效，
     * 所以不需要重新登录（重新登录反而会让用户在弱网下多一次可能失败的往返）。
     *
     * <p>401 `CREDENTIALS`（当前密码不对）用 {@link AccountInput.Failure#passwordChange} 翻译，
     * 得到的是一句「当前密码不对」——**不是**登录失败那句。用户正登录着，说「账号或密码不对」
     * 会让他以为账号出了问题。
     */
    public void changePassword(String current, String newPassword, String confirm) {
        if (busy()) return;
        if (api == null) { state.setValue("NOT_CONFIGURED"); return; }
        if (!signedIn()) {
            // 会话在我们看这一屏的时候过期/被清了。**照实说**，别让用户对着一个
            // 一定失败的按钮反复点：告诉他登录已经失效，重新登一次再来改。
            notice.setValue(getApplication().getString(R.string.account_change_failed));
            action.setValue(AccountInput.ACTION_NONE);
            state.setValue("FAILED");
            return;
        }
        int problem = AccountInput.passwordChangeProblem(current, newPassword, confirm);
        if (problem != 0) { state.setValue("INVALID"); invalidReason.setValue(problem); return; }
        notice.setValue("");
        action.setValue(AccountInput.ACTION_NONE);
        int request = ++generation;
        state.setValue("BUSY");
        Call<AccountApi.PasswordChanged> call = api.changePassword(
                "Bearer " + session.token(), new AccountApi.PasswordChange(current, newPassword));
        pending = call;
        call.enqueue(new Callback<>() {
            @Override public void onResponse(@NonNull Call<AccountApi.PasswordChanged> call,
                                             @NonNull Response<AccountApi.PasswordChanged> response) {
                if (request != generation) return;
                AccountApi.PasswordChanged body = response.body();
                if (!response.isSuccessful() || body == null || !body.passwordChanged) {
                    if (response.isSuccessful()) {
                        notice.setValue(getApplication().getString(R.string.account_change_failed));
                        action.setValue(AccountInput.ACTION_NONE);
                        state.setValue("FAILED");
                    } else if (isSessionLost(response)) {
                        // 401 但**错误体没说是哪一步错了**：契约里改密码的 401 只有
                        // 「当前密码不对」（有 code）和「会话无效」。没有 code 的那种
                        // 就是会话过期，说成「当前密码不对」会让用户一遍遍重输一个
                        // 完全正确的密码。
                        session.clear();
                        RepositoryProvider.configureForum(session.forumBaseUrl(), session);
                        notice.setValue(getApplication().getString(R.string.account_change_session_expired));
                        action.setValue(AccountInput.ACTION_NONE);
                        state.setValue("FAILED");
                    } else {
                        failFor(request, response, AccountInput.Failure.OP_PASSWORD_CHANGE);
                    }
                    return;
                }
                // 成功。`otherSessionsRevoked` 是服务端踢掉了几台别的设备——把它说出来，
                // 否则用户在平板上会以为是自己掉线了（那不是 bug，是这次改密码的后果）。
                int revoked = body.otherSessionsRevoked;
                // **带数字的那句在这里就把参数填好**（见 notice 的注释）。
                notice.setValue(revoked > 0
                        ? getApplication().getString(R.string.account_change_done_count, revoked)
                        : getApplication().getString(R.string.account_change_done));
                action.setValue(AccountInput.ACTION_NONE);
                state.setValue("CHANGED");
            }
            @Override public void onFailure(@NonNull Call<AccountApi.PasswordChanged> call, @NonNull Throwable error) {
                if (request != generation) return;
                Log.w(TAG, "password change failed: " + error, error);
                failFor(request, null, AccountInput.Failure.OP_PASSWORD_CHANGE);
            }
        });
    }

    /**
     * 401 是不是「会话没了」而不是「当前密码错了」。
     *
     * <p>判别依据是错误体里有没有 `code`：契约里「当前密码不对」一定带
     * `CREDENTIALS`，而会话无效那条是框架层拒绝的，body 通常是空的。**这是一条推测**，
     * 所以只在 401 且没有 code 时才用它，而且文案两头都不说死（「登录可能已经失效，
     * 重新登录后再改」），猜错也不会把用户引到错误的方向。
     */
    private static boolean isSessionLost(Response<?> response) {
        return response.code() == 401 && AccountApi.errorCode(response) == null;
    }

    /** 忘记密码那屏要显示的邮箱：用户填的优先，没填才用会话里记着的。 */
    private String resetEmail() {
        String typed = email().trim();
        return typed.isEmpty() ? accountEmail().trim() : typed;
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
                    autoSignIn(request, body.email == null || body.email.isEmpty() ? email : body.email, false);
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
            notice.setValue(getApplication().getString(R.string.account_email_empty));
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
                        notice.setValue(getApplication().getString(R.string.account_verify_unconfirmed));
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
                    notice.setValue("");
                    state.setValue("VERIFIED_SIGN_IN_REQUIRED");
                    return;
                }
                autoSignIn(request, body.email == null || body.email.isEmpty() ? email : body.email, false);
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
                        notice.setValue(getApplication().getString(R.string.account_resend_failed));
                        action.setValue(AccountInput.ACTION_NONE);
                        state.setValue("FAILED");
                    } else fail(request, response, email);
                    return;
                }
                resendReadyAt.setValue(SystemClock.elapsedRealtime() + RESEND_COOLDOWN_MILLIS);
                notice.setValue(getApplication().getString(R.string.account_resend_done));
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
    /**
     * 验证通过（或密码重设成功）之后的自动登录。用的还是内存里那份密码。
     *
     * @param afterReset true = 这次自动登录是**重设密码**的收尾，不是邮箱验证的收尾。
     *                   两者失败时的收尾完全一样（都要退回表单段、都要把码那一段作废），
     *                   但**说的话不一样**：一个是「邮箱验证成功了，请登录」，一个是
     *                   「密码已经改好了，请用新密码登录」。混用会让刚改完密码的用户
     *                   以为自己刚才在验证邮箱。
     */
    private void autoSignIn(int request, String email, boolean afterReset) {
        String password = pendingPassword;
        if (password == null || password.isEmpty()) {
            // 走到这儿说明上一步（验证/重设）是成功的、但本地没有密码（见 verify() 里那条注释）：
            // 退回登录段让用户自己登一次，**不能显示成已经登录**。
            saved.set("stage", STAGE_FORM);
            saved.set("registerMode", false);
            state.setValue(afterReset ? "RESET_SIGN_IN_REQUIRED" : "VERIFIED_SIGN_IN_REQUIRED");
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
                    // 上一步已经成功了，这里失败的是登录。**绝不能退回验证码段**：
                    // 那个码已经用掉了，用户再填多少次都只会得到 CODE_INVALID。
                    AccountInput.Failure failure = AccountInput.failure(response.code(),
                            AccountApi.errorCode(response));
                    notice.setValue(getApplication().getString(failure.message));
                    action.setValue(failure.action);
                    saved.set("stage", STAGE_FORM);
                    saved.set("registerMode", false);
                    state.setValue(afterReset ? "RESET_SIGN_IN_REQUIRED" : "VERIFIED_SIGN_IN_REQUIRED");
                    return;
                }
                store(body, request);
            }
            @Override public void onFailure(@NonNull Call<AccountApi.Account> call, @NonNull Throwable error) {
                if (request != generation) return;
                AccountInput.Failure failure = AccountInput.failure(0, null);
                notice.setValue(getApplication().getString(failure.message));
                action.setValue(AccountInput.ACTION_NONE);
                saved.set("stage", STAGE_FORM);
                saved.set("registerMode", false);
                state.setValue(afterReset ? "RESET_SIGN_IN_REQUIRED" : "VERIFIED_SIGN_IN_REQUIRED");
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
        // 会话已经落地：密码不用再留着了，「上一步是为了什么」也不用再解释了。
        pendingPassword = null;
        autoSignInReason.setValue(0);
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
        notice.setValue(getApplication().getString(failure.message));
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
                notice.setValue(getApplication().getString(R.string.account_unverified_ask_email));
                action.setValue(AccountInput.ACTION_NONE);
            } else {
                enterVerifyStage(email, true);
            }
        }
        state.setValue("FAILED");
    }

    /**
     * 忘记密码 / 改密码这两条路的失败落盘。
     *
     * <p>和 {@link #fail} 的差别只有一处：**翻译时带语境**。同一个 `CREDENTIALS`（或光秃秃的
     * 401）在改密码语境下必须说「当前密码不对」，而登录那条路上说「邮箱/用户名或密码不对」。
     * 顺便把 `autoSignInReason` 清掉——自动登录失败之后那句「正在用新密码登录…」已经不成立了。
     *
     * <p>不共享 {@link #fail} 里那段 `ACTION_VERIFY` 逻辑：那会把密码重设的失败
     * 拐去「验证码已发往…」那一屏，而用户根本没有注册邮箱要验证。
     *
     * @param operation {@link AccountInput.Failure#OP_FORGOT_PASSWORD} 或
     *                  {@link AccountInput.Failure#OP_PASSWORD_CHANGE}
     */
    private void failFor(int request, Response<?> response, String operation) {
        if (request != generation) return;
        int status = response == null ? 0 : response.code();
        String code = response == null ? null : AccountApi.errorCode(response);
        // 三个语境各一条入口，调用点一眼能看出自己走的是哪条路：同一句
        // 「没注册成功 / 密码没被动过」在两个语境下是**不同的两句话**。
        AccountInput.Failure failure = AccountInput.Failure.OP_PASSWORD_CHANGE.equals(operation)
                ? AccountInput.Failure.passwordChange(status, code)
                : AccountInput.Failure.OP_FORGOT_PASSWORD.equals(operation)
                ? AccountInput.Failure.forgotPassword(status, code)
                : AccountInput.Failure.signIn(status, code);
        notice.setValue(getApplication().getString(failure.message));
        action.setValue(failure.action);
        autoSignInReason.setValue(0);
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
