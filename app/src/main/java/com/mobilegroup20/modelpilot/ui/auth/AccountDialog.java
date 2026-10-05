package com.mobilegroup20.modelpilot.ui.auth;

import android.app.Dialog;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.BuildConfig;

/**
 * 账号对话框：注册 / 登录 / 邮箱验证码验证 / 退出，以及（Debug）免账号进论坛测试区。
 * <b>负责人：刘宗润。</b>
 *
 * <p>账号是**这个 App 自己的**账号（后端 `/api/account/*`），不再是团队那台机器上的
 * 账号服务。同一个 `userId` 用来发帖、读用量、结算游戏资源——这就是「服务端为唯一
 * 数据源」在身份上的那一半。
 *
 * <p><b>两段式</b>：第一段填邮箱、用户名、密码；注册成功（或登录被 403 EMAIL_UNVERIFIED
 * 拒了）之后切到第二段填验证码。两段的控件**一次建好、靠可见性切换**，不是重建对话框：
 * 重建会把用户已经敲进去的邮箱和密码丢掉，而他退回第一段改邮箱（「邮箱写错了？返回修改」）
 * 是常事。
 *
 * <p>密码**从不进 instance state**（`setSaveEnabled(false)`）：系统可能把它写进
 * 磁盘上的 Bundle，那等于把密码明文留在手机上。验证码同理——它是一次性的凭证。
 */
public final class AccountDialog extends DialogFragment {
    private AccountViewModel model;
    private TextInputEditText email, username, password, code;
    private LinearLayout formGroup, verifyGroup;
    private TextView message, verifyHeader;
    private boolean testOnly;
    /** 重发按钮上的秒数每秒都要重画；剩余时间用结束时刻现算，见 {@link #renderResendButton}。 */
    private CountDownTimer resendTimer;
    public static AccountDialog forForumTest() {
        AccountDialog dialog = new AccountDialog(); Bundle arguments = new Bundle();
        arguments.putBoolean("forumTest", true); dialog.setArguments(arguments); return dialog;
    }
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        model = new ViewModelProvider(this).get(AccountViewModel.class);
        testOnly = getArguments() != null && getArguments().getBoolean("forumTest") && BuildConfig.DEBUG;
        ContextThemeWrapper context = new ContextThemeWrapper(requireContext(), R.style.ForumTheme);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density); content.setPadding(pad, pad / 2, pad, 0);
        message = new TextView(context); content.addView(message);
        // 第一段：邮箱 + 用户名 + 密码。**两种模式都显示这三个框**——登录的 identifier
        // 服务端本来就允许邮箱或用户名，界面没必要为它分叉出一套控件（分叉之后
        // 「我到底填哪个」会变成一个新问题）。
        formGroup = new LinearLayout(context); formGroup.setOrientation(LinearLayout.VERTICAL);
        email = input(formGroup, R.string.account_email,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        username = input(formGroup, R.string.account_username,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        password = input(formGroup, R.string.account_password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false); password.setFreezesText(false);
        content.addView(formGroup);
        // 第二段：验证码。头一行是「验证码已发往 <邮箱>」——用户据此确认自己没把邮箱写错，
        // 写错了就往下点「返回修改」。
        verifyGroup = new LinearLayout(context); verifyGroup.setOrientation(LinearLayout.VERTICAL);
        verifyHeader = new TextView(context); verifyGroup.addView(verifyHeader);
        code = input(verifyGroup, R.string.account_code, InputType.TYPE_CLASS_NUMBER);
        // 验证码是从邮件里**复制粘贴**过来的，粘贴常带空格或换行（"123 456"）。
        // 在输入法这一层只留数字并截到 6 位：要是让 LengthFilter 先按字符截断，
        // "123 456" 会变成 "123 45"，用户看到的是「验证码是 6 位数字」——
        // 明明填对了却被说格式不对，这种错最难查。
        code.setFilters(new InputFilter[] { (source, start, end, dest, dstart, dend) -> {
            StringBuilder digits = new StringBuilder();
            for (int i = start; i < end && digits.length() < AccountInput.CODE_LENGTH; i++) {
                char character = source.charAt(i);
                if (Character.isDigit(character)) digits.append(character);
            }
            return digits.toString();
        } });
        // 验证码是一次性凭证：和密码一样**不能被系统存进磁盘上的 Bundle**。
        code.setSaveEnabled(false); code.setFreezesText(false);
        // 「邮箱写错了？返回修改」放在内容里而不是按钮上：对话框只有三个按钮位，
        // 而第二段那两个（验证 / 重发验证码）都是主流程要用的。
        TextView back = new TextView(context); back.setText(R.string.account_back_to_form);
        back.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        back.setOnClickListener(v -> { model.backToForm(); render(); });
        verifyGroup.addView(back);
        content.addView(verifyGroup);
        if (!model.signedIn() && !testOnly) {
            // 邮箱和用户名都从 ViewModel 回填：转屏之后 EditText 是新的，
            // 值只能在 ViewModel/SavedStateHandle 里。
            email.setText(model.email());
            username.setText(model.username());
            email.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) { model.email(s.toString()); }
                public void afterTextChanged(Editable value) { }
            });
            username.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) { model.username(s.toString()); }
                public void afterTextChanged(Editable value) { }
            });
        }
        // 第二段的按钮是「验证」+「重发验证码」，第一段是「注册/登录」+「注册⇄登录」。
        // 文字在 render() 里按当前段位改写，这里只是先把三个按钮建出来。
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(testOnly ? R.string.forum_test_enter : R.string.account_title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(model.signedIn()
                        ? model.forumTest() ? R.string.forum_test_leave : R.string.account_sign_out
                        : testOnly ? R.string.forum_retry : R.string.account_sign_in, null);
        // 未登录时给两个按钮：左边「注册」（点了切换到注册模式），
        // 右边始终是当前模式的提交按钮。测试入口只在 Debug 里追加进来。
        if (!model.signedIn() && !testOnly) builder.setNeutralButton(R.string.account_register, null);
        // 先把段位和提示语摆好再 show()：render() 是在 onStart 里跑的，而 DialogFragment
        // 自己的 onStart 就已经把对话框显示出来了——不在这里摆一次，转屏回到第二段的
        // 用户会先看见一帧「邮箱/用户名/密码」的空表单，然后才被换成验证码。
        boolean verifyStage = verifyStage();
        formGroup.setVisibility(verifyStage ? View.GONE : View.VISIBLE);
        verifyGroup.setVisibility(verifyStage ? View.VISIBLE : View.GONE);
        String current = model.state.getValue() == null ? "IDLE" : model.state.getValue();
        message.setText(messageFor(current, verifyStage));
        if (verifyStage) verifyHeader.setText(verifyHeaderText());
        return builder.create();
    }
    /** 现在是不是第二段（验证码那一段）。已登录和免账号测试这两个面板永远不是。 */
    private boolean verifyStage() {
        return !model.signedIn() && !testOnly
                && AccountViewModel.STAGE_VERIFY.equals(model.stage());
    }
    /**
     * 第二段的头一行：「验证码已发往 <邮箱>」。
     *
     * <p>邮箱为空时**不显示一个空地址**（那会变成「验证码已发往 」）：只有测试身份和
     * 没有邮箱的老快照会走到这儿，说「你的注册邮箱」比露出一个空白强。
     */
    private CharSequence verifyHeaderText() {
        String address = model.email().trim();
        return address.isEmpty() ? getString(R.string.account_code_sent_unknown)
                : getString(R.string.account_code_sent_to, address);
    }
    private TextInputEditText input(LinearLayout parent, int hint, int type) {
        TextInputLayout wrapper = new TextInputLayout(parent.getContext()); wrapper.setHint(hint);
        TextInputEditText field = new TextInputEditText(parent.getContext()); field.setInputType(type);
        wrapper.addView(field); parent.addView(wrapper); return field;
    }
    @Override public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (model.signedIn()) { model.signOut(); return; }
            if (testOnly) { model.enterForumTest(); return; }
            if (AccountViewModel.STAGE_VERIFY.equals(model.stage())) {
                model.verify(code == null || code.getText() == null ? "" : code.getText().toString());
                return;
            }
            String secret = password == null || password.getText() == null
                    ? "" : password.getText().toString();
            if (model.registerMode()) model.register(secret); else model.signIn(secret);
        });
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                // 第二段的中性按钮是「重发验证码」，第一段是「注册 ⇄ 登录」。
                if (AccountViewModel.STAGE_VERIFY.equals(model.stage())) { model.resend(); return; }
                // 「注册 ⇄ 登录」只是切换界面，**不发请求**。切过去还能切回来。
                model.registerMode(!model.registerMode());
                render();
            });
        }
        model.state.removeObservers(this);
        model.state.observe(this, state -> {
            // 成功就收尾：通知外面的页面身份变了，然后关掉。**顺序不能反**——dismiss()
            // 之后这个 fragment 已经在往外走，再 setFragmentResult 有可能没人收。
            if ("SUCCESS".equals(state)) {
                getParentFragmentManager().setFragmentResult("accountChanged", new Bundle()); dismiss(); return;
            }
            render();
        });
        if (testOnly && "IDLE".equals(model.state.getValue())) model.enterForumTest();
    }
    @Override public void onStop() {
        // 对话框看不见了就别再每秒重画按钮。计时是按结束时刻算的，
        // 下次 onStart 重新算一次 remain 就接上了，不会因为暂停而少算。
        stopResendTimer();
        super.onStop();
    }
    /**
     * 把当前状态画到界面上。**每次状态变化都整体重画**：这个函数是幂等的，
     * 所以转屏重建之后（observer 会立刻回调一次当前值）界面能自己回到正确的样子，
     * 不需要另写一套「恢复」逻辑。
     */
    private void render() {
        if (message == null || !isAdded()) return;
        AlertDialog dialog = (AlertDialog) requireDialog();
        String state = model.state.getValue() == null ? "IDLE" : model.state.getValue();
        boolean verifyStage = verifyStage();
        formGroup.setVisibility(verifyStage ? View.GONE : View.VISIBLE);
        verifyGroup.setVisibility(verifyStage ? View.VISIBLE : View.GONE);
        boolean busy = "BUSY".equals(state);
        if (verifyStage) verifyHeader.setText(verifyHeaderText());
        message.setText(messageFor(state, verifyStage));
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (model.signedIn() || testOnly) {
            // 已登录和免账号测试这两条路的按钮文字是固定的（退出/退出测试模式/重试），
            // 这里只跟着 busy 走——**保持原样**，别把两段式的文字混进来。
            positive.setEnabled(!busy);
            if (neutral != null) neutral.setEnabled(!busy);
            return;
        }
        if (verifyStage) {
            positive.setText(R.string.account_verify);
            positive.setEnabled(!busy);
            renderResendButton(neutral, busy);
            // 计时器在**状态变化时**起停就够了，不进 tick：tick 里再 start 会每秒重建一个
            // 计时器，白白多出一堆对象，而且下次 tick 的相位跟着重建时刻漂。
            if (model.remainingResendMillis() > 0 && !busy) startResendTimer(); else stopResendTimer();
        } else {
            stopResendTimer();
            positive.setText(model.registerMode() ? R.string.account_register : R.string.account_sign_in);
            positive.setEnabled(!busy);
            if (neutral != null) {
                neutral.setText(model.registerMode() ? R.string.account_sign_in : R.string.account_register);
                neutral.setEnabled(!busy);
            }
        }
    }
    /**
     * 重发按钮：冷却里显示剩余秒数并禁用。
     *
     * <p>剩余时间是**用结束时刻现算的**，不是把秒数存下来每秒减一：转屏或者切后台之后
     * 计时器会被取消重建，靠减法会把已经过去的那段时间漏掉，看起来像卡住了。
     */
    private void renderResendButton(Button neutral, boolean busy) {
        if (neutral == null) return;
        long remaining = model.remainingResendMillis();
        int seconds = (int) Math.ceil(remaining / 1000.0);
        neutral.setText(seconds > 0
                ? getString(R.string.account_resend_in, seconds) : getString(R.string.account_resend));
        neutral.setEnabled(!busy && seconds <= 0);
    }
    private void startResendTimer() {
        stopResendTimer();
        long remaining = model.remainingResendMillis();
        if (remaining <= 0) return;
        resendTimer = new CountDownTimer(remaining, 1000L) {
            @Override public void onTick(long millisUntilFinished) { tickResendButton(); }
            @Override public void onFinish() { tickResendButton(); }
        }.start();
    }
    private void tickResendButton() {
        if (!isAdded()) { stopResendTimer(); return; }
        AlertDialog dialog = (AlertDialog) requireDialog();
        renderResendButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL),
                "BUSY".equals(model.state.getValue()));
    }
    private void stopResendTimer() { if (resendTimer != null) { resendTimer.cancel(); resendTimer = null; } }
    /** 状态 → 提示语。**每一种失败都有自己的话**，落不到具体分支时也要说清是哪一类。 */
    private CharSequence messageFor(String state, boolean verifyStage) {
        switch (state) {
            case "BUSY":
                return getString(verifyStage ? R.string.account_verifying
                        : model.registerMode() ? R.string.account_registering : R.string.account_signing_in);
            // 注册成功但**还没验证**：这句话必须说清楚，否则用户以为已经完事了。
            case "REGISTERED": return getString(R.string.account_registered);
            // FAILED 的具体理由来自服务端，由 AccountInput 翻译好放在 notice 里
            // （邮箱被占 / 用户名被占 / 码错了 / 码过期了 …各是一句话）。
            case "CODE_SENT":
            case "FAILED": return getString(noticeOr(R.string.forum_request_error));
            case "VERIFIED": return getString(R.string.account_verified);
            case "VERIFIED_SIGN_IN_REQUIRED": {
                int notice = noticeOr(0);
                return notice == 0 ? getString(R.string.account_verified_manually)
                        : getString(R.string.account_verified_manually_reason, getString(notice));
            }
            // 本地校验拦下来的：具体理由在 `invalidReason` 里，比「格式不对」有用。
            case "INVALID": {
                Integer reason = model.invalidReason.getValue();
                return getString(reason == null || reason == 0 ? R.string.account_empty : reason);
            }
            case "NETWORK": return getString(R.string.forum_network_error);
            case "RATE_LIMIT": return getString(R.string.account_rate_limit);
            case "NOT_CONFIGURED": return getString(R.string.forum_not_ready);
            case "STORAGE": return getString(R.string.account_storage_error);
            case "TEST_UNAVAILABLE": return getString(R.string.forum_test_unavailable);
            case "SERVER": return getString(R.string.forum_request_error);
            default: break; // IDLE：还没发生任何事，显示当前段位/身份的说明
        }
        if (testOnly) return getString(R.string.forum_test_explanation);
        if (model.signedIn()) return getString(
                model.forumTest() ? R.string.forum_test_identity : R.string.account_signed_in, model.name());
        if (verifyStage) return getString(R.string.account_code_hint);
        return getString(model.registerMode() ? R.string.account_new : R.string.account_existing);
    }
    private int noticeOr(int fallback) {
        Integer notice = model.notice.getValue();
        return notice == null || notice == 0 ? fallback : notice;
    }
}
