package com.mobilegroup20.modelpilot.ui.auth;

import android.app.Dialog;
import android.graphics.Typeface;
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
 * 账号对话框：注册 / 登录 / 邮箱验证码验证 / 忘记密码 / 改密码 / 退出，
 * 以及（Debug）免账号进论坛测试区。<b>负责人：刘宗润。</b>
 *
 * <p>账号是**这个 App 自己的**账号（后端 `/api/account/*`），不再是团队那台机器上的
 * 账号服务。同一个 `userId` 用来发帖、读用量、结算游戏资源——这就是「服务端为唯一
 * 数据源」在身份上的那一半。
 *
 * <p><b>四段</b>：表单（邮箱/用户名/密码）、验证码（注册那条路）、忘记密码
 * （验证码 + 新密码，见 {@link AccountViewModel#STAGE_RESET}）、改密码
 * （当前密码 + 新密码，见 {@link AccountViewModel#STAGE_CHANGE}）。段位存在
 * ViewModel 的 `SavedStateHandle` 里，转屏之后回到原来那一段。
 *
 * <p>各段的控件**一次建好、靠可见性切换**，不是重建对话框：重建会把用户已经敲进去的
 * 邮箱和密码丢掉，而他退回第一段改邮箱（「邮箱写错了？返回修改」）是常事。
 *
 * <p><b>密码一次都不进 instance state</b>（一律 `setSaveEnabled(false)`，也从不写进
 * `SavedStateHandle`）：系统可能把它写进磁盘上的 Bundle，那等于把密码明文留在手机上。
 * 验证码同理——它是一次性的凭证。代价是转屏之后密码框会空掉，用户得重敲一次；
 * 这个代价是有意付的。
 */
public final class AccountDialog extends DialogFragment {
    private AccountViewModel model;
    /**
     * 六块面板。全部一次建好，{@link #render()} 里按段位开关可见性。
     *
     * <p>拆成 {@link Section} 而不是散着若干个 `LinearLayout` 字段，是因为每个 Section
     * 自己管着「我这一块要不要露出来」以及「我下面的输入框要不要清空」——散着写的时候
     * 每加一段就要在 `onCreateDialog` 和 `render()` 两处各补一排 `setVisibility`，
     * 漏一个的结果是两块面板同时显示（用户会看到两组密码框，完全不知道该填哪个）。
     */
    private Section identitySection, formGroup, changeGroup, verifyGroup, resetGroup;
    private TextInputEditText email, username, password;
    /** 验证码段和忘记密码段各有一个码框：同名的控件只能挂在一个父容器下，而这两段并存会打架。 */
    private TextInputEditText code, resetCode;
    private TextInputEditText currentPassword, newPassword, confirmPassword;
    private TextInputEditText resetNewPassword, resetConfirm;
    private TextView message, verifyHeader, resetHeader, resetResend, forgotLink, changeLink;
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
        //
        // 已登录时它只用来**显示**账号（邮箱/用户名只读），因为改密码那一屏要占掉
        // 密码那一栏，所以这里拆成两块：身份（邮箱+用户名）和密码。
        identitySection = new Section(context); identitySection.group.setOrientation(LinearLayout.VERTICAL);
        identitySection.header = new TextView(context); identitySection.group.addView(identitySection.header);
        email = input(identitySection.group, R.string.account_email,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        username = input(identitySection.group, R.string.account_username,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        content.addView(identitySection.group);
        formGroup = new Section(context); formGroup.group.setOrientation(LinearLayout.VERTICAL);
        password = input(formGroup.group, R.string.account_password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false); password.setFreezesText(false);
        // 「忘记密码？」**放在表单段自己的底部**（密码框下面），不是整个对话框的底部：
        // 这个对话框只切面板、不重建，链接留在公共区的话，用户在验证码段/忘记密码段
        // 也能点到它——那时候点下去等于把刚填的码丢掉、重新发一封。
        forgotLink = new TextView(context); forgotLink.setText(R.string.account_forgot_password);
        forgotLink.setTextColor(context.getColor(R.color.account_link));
        forgotLink.setPadding(0, Math.round(12 * getResources().getDisplayMetrics().density), 0, 0);
        forgotLink.setOnClickListener(v -> { model.enterResetStage(); render(); });
        formGroup.group.addView(forgotLink);
        content.addView(formGroup.group);
        // 第二段：验证码。头一行是「验证码已发往 <邮箱>」——用户据此确认自己没把邮箱写错，
        // 写错了就往下点「返回修改」。
        verifyGroup = new Section(context); verifyGroup.group.setOrientation(LinearLayout.VERTICAL);
        verifyHeader = new TextView(context); verifyGroup.group.addView(verifyHeader);
        code = input(verifyGroup.group, R.string.account_code, InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[] { digitsOnly() });
        // 验证码是一次性凭证：和密码一样**不能被系统存进磁盘上的 Bundle**。
        code.setSaveEnabled(false); code.setFreezesText(false);
        // 「邮箱写错了？返回修改」放在内容里而不是按钮上：对话框只有三个按钮位，
        // 而第二段那两个（验证 / 重发验证码）都是主流程要用的。
        TextView back = new TextView(context); back.setText(R.string.account_back_to_form);
        back.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        back.setOnClickListener(v -> { model.backToForm(); render(); });
        verifyGroup.group.addView(back);
        content.addView(verifyGroup.group);
        // 第三段：忘记密码。**两小步挤在一段里**（上面「码发到哪了」，下面「填码设新密码」），
        // 不再往下分段——忘了密码的人已经迷路了，多一屏只会让他多迷一次路。
        resetGroup = new Section(context); resetGroup.group.setOrientation(LinearLayout.VERTICAL);
        resetHeader = new TextView(context); resetHeader.setTypeface(bold(resetHeader.getTypeface()));
        resetHeader.setPadding(0, 0, 0, Math.round(4 * getResources().getDisplayMetrics().density));
        resetGroup.group.addView(resetHeader);
        resetCode = input(resetGroup.group, R.string.account_reset_code, InputType.TYPE_CLASS_NUMBER);
        resetCode.setFilters(new InputFilter[] { digitsOnly() });
        resetCode.setSaveEnabled(false); resetCode.setFreezesText(false);
        resetNewPassword = passwordInput(resetGroup.group, R.string.account_new_password);
        resetConfirm = passwordInput(resetGroup.group, R.string.account_new_password_confirm);
        resetResend = new TextView(context); resetResend.setText(R.string.account_resend);
        resetResend.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        resetResend.setOnClickListener(v -> { model.resendResetCode(); render(); });
        resetGroup.group.addView(resetResend);
        TextView resetBack = new TextView(context); resetBack.setText(R.string.account_back_to_login);
        resetBack.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        // 返回登录时把这一屏的密码框清空：这个对话框是**看着同一个 Fragment 赖着不走**的
        // （只切面板、不 dismiss），不清的话下次再进来这两个框里的旧密码还在，
        // 用户按一次「重设密码」就把一个他以为早就作废的密码又用上了。
        resetBack.setOnClickListener(v -> {
            resetNewPassword.setText(""); resetConfirm.setText("");
            model.backToForm(); render();
        });
        resetGroup.group.addView(resetBack);
        content.addView(resetGroup.group);
        // 第四段：已登录时改密码。当前密码 / 新密码 / 确认，三个都是密码框。
        changeGroup = new Section(context); changeGroup.group.setOrientation(LinearLayout.VERTICAL);
        currentPassword = passwordInput(changeGroup.group, R.string.account_current_password);
        newPassword = passwordInput(changeGroup.group, R.string.account_new_password);
        confirmPassword = passwordInput(changeGroup.group, R.string.account_new_password_confirm);
        TextView changeBack = new TextView(context); changeBack.setText(R.string.account_change_back);
        changeBack.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        // 同上：退出这一屏就把三个密码框清掉，别让下次进来的人对着上一次的残留按保存。
        changeBack.setOnClickListener(v -> {
            currentPassword.setText(""); newPassword.setText(""); confirmPassword.setText("");
            model.backToForm(); render();
        });
        changeGroup.group.addView(changeBack);
        content.addView(changeGroup.group);
        // 「修改密码」入口。它只在已登录时出现，放在所有面板之后——已登录时露出来的
        // 面板只有身份那两块，所以它实际就落在账号信息下面，一眼能看到。
        // 颜色照 `account_back_to_form` 的路子（蓝色），一眼能认出是能点的。
        changeLink = new TextView(context); changeLink.setText(R.string.account_change_password);
        changeLink.setTextColor(context.getColor(R.color.account_link));
        changeLink.setPadding(0, Math.round(12 * getResources().getDisplayMetrics().density), 0, 0);
        changeLink.setOnClickListener(v -> { model.changePasswordStage(); render(); });
        content.addView(changeLink);
        // 第一段的两个文本框：邮箱和用户名都从 ViewModel 回填（转屏之后 EditText 是新的，
        // 值只能在 ViewModel/SavedStateHandle 里）。**已登录时不挂监听**：那时候它们
        // 只是把账号显示出来给用户看，改它们没有任何意义（也没有「改邮箱」这个接口）。
        email.setText(model.email());
        username.setText(model.username());
        if (!model.signedIn()) {
            email.addTextChangedListener(watcher(value -> model.email(value)));
            username.addTextChangedListener(watcher(value -> model.username(value)));
        }
        // 忘记密码那屏的验证码文本框：段位存在 SavedStateHandle 里，转屏之后这一屏会
        // 重新建出来，所以码得从 ViewModel 回填（**不是密码**，进 SavedStateHandle 没问题）。
        // 旁边那个新密码框**故意不回填**：它是密码，和登录密码一个待遇，只在内存里。
        resetCode.setText(model.resetCode());
        resetCode.addTextChangedListener(watcher(value -> model.resetCode(value)));
        // 第二段的按钮是「验证」+「重发验证码」，忘记密码段是「重设密码」+「重发验证码」，
        // 第一段是「注册/登录」+「注册⇄登录」，改密码段是「保存新密码」。
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
        layout(messageFor(), model.resetStage());
        if (model.resetStage()) resetHeader.setText(codeSentHeader());
        return builder.create();
    }
    /** 忘记密码那段的「验证码已发往 <邮箱>」（邮箱不知道时换成另一句）。 */
    private CharSequence codeSentHeader() {
        String address = model.resetAddress().trim();
        // 邮箱为空时**不显示一个空地址**（那会变成「验证码已发往 」）：没有邮箱的老快照、
        // 或者拿用户名登录的用户会走到这儿，说「你的注册邮箱」比露出一个空白强。
        return getString(address.isEmpty() ? R.string.account_reset_email_unknown
                : R.string.account_code_sent_to, address);
    }
    private TextInputEditText input(LinearLayout parent, int hint, int type) {
        TextInputLayout wrapper = new TextInputLayout(parent.getContext()); wrapper.setHint(hint);
        TextInputEditText field = new TextInputEditText(parent.getContext()); field.setInputType(type);
        wrapper.addView(field); parent.addView(wrapper); return field;
    }
    /** 密码框的公共部分：类型 + **绝不进 Bundle**（理由见类注释）。 */
    private TextInputEditText passwordInput(LinearLayout parent, int hint) {
        TextInputEditText field = input(parent, hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSaveEnabled(false); field.setFreezesText(false);
        return field;
    }
    /**
     * 验证码框的输入过滤：只留数字，最多 {@link AccountInput#CODE_LENGTH} 位。
     *
     * <p>验证码是从邮件里**复制粘贴**过来的，粘贴常带空格或换行（"123 456"）。
     * 在输入法这一层只留数字并截到 6 位：要是让 LengthFilter 先按字符截断，
     * "123 456" 会变成 "123 45"，用户看到的是「验证码是 6 位数字」——
     * 明明填对了却被说格式不对，这种错最难查。
     */
    private InputFilter digitsOnly() {
        return (source, start, end, dest, dstart, dend) -> {
            StringBuilder digits = new StringBuilder();
            for (int i = start; i < end && digits.length() < AccountInput.CODE_LENGTH; i++) {
                char character = source.charAt(i);
                if (Character.isDigit(character)) digits.append(character);
            }
            return digits.toString();
        };
    }
    private TextWatcher watcher(java.util.function.Consumer<String> sink) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                sink.accept(s == null ? "" : s.toString());
            }
            public void afterTextChanged(Editable value) { }
        };
    }
    private static Typeface bold(Typeface base) { return Typeface.create(base, Typeface.BOLD); }
    @Override public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (model.changeStage()) {
                model.changePassword(text(currentPassword), text(newPassword), text(confirmPassword));
                return;
            }
            if (model.signedIn()) { model.signOut(); return; }
            if (testOnly) { model.enterForumTest(); return; }
            if (model.resetStage()) {
                model.resetPassword(text(resetCode), text(resetNewPassword), text(resetConfirm));
                return;
            }
            if (model.verifyStage()) { model.verify(text(code)); return; }
            if (model.registerMode()) model.register(text(password)); else model.signIn(text(password));
        });
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                // 第二段的中性按钮是「重发验证码」，第一段是「注册 ⇄ 登录」。
                if (model.verifyStage()) { model.resend(); return; }
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
        boolean resetStage = model.resetStage();
        layout(messageFor(), resetStage);
        if (resetStage) resetHeader.setText(codeSentHeader());
        boolean busy = "BUSY".equals(state);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (model.changeStage()) {
            // 已登录 + 改密码：正面按钮从「退出」变成「保存新密码」。
            positive.setText(R.string.account_change_submit);
            positive.setEnabled(!busy);
            if (neutral != null) neutral.setEnabled(!busy);
            return;
        }
        if (model.signedIn() || testOnly) {
            // 已登录和免账号测试这两条路的按钮文字是固定的（退出/退出测试模式/重试），
            // 这里只跟着 busy 走——**保持原样**，别把别的段位的文字混进来。
            positive.setEnabled(!busy);
            if (neutral != null) neutral.setEnabled(!busy);
            return;
        }
        if (resetStage) {
            positive.setText(R.string.account_reset_submit);
            positive.setEnabled(!busy);
            // 忘记密码段没有中性按钮（对话框只有三个按钮位），重发链接在内容里。
            if (neutral != null) neutral.setEnabled(!busy);
            renderResetResend(busy);
            if (model.remainingResendMillis() > 0 && !busy) startResendTimer(); else stopResendTimer();
        } else if (model.verifyStage()) {
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
     * 按当前段位摆面板：一次把六块的可见性和几个链接的文字/色/可点性都摆正。
     *
     * <p>`render()` 和 `onCreateDialog()` **共用**这一份，是为了让「对话框刚建出来」和
     * 「状态变了一下」两条路走完全一样的代码。以前 `onCreateDialog` 里手抄了一份只摆
     * 两块的版本，加段位的时候必然漏——漏的表现是刚打开的一瞬间两块面板叠在一起。
     */
    private void layout(CharSequence prompt, boolean resetStage) {
        boolean signedIn = model.signedIn();
        boolean changeStage = model.changeStage();
        // 已登录时邮箱/用户名是只读的展示值（账号邮箱，不是表单里那个可能为空的输入）。
        // 空的时候不留一个空的只读框：那看起来像「你的账号没有邮箱」。
        boolean identity = !changeStage
                && (!signedIn || !model.accountEmail().trim().isEmpty());
        identitySection.group.setVisibility(identity ? View.VISIBLE : View.GONE);
        if (signedIn) {
            email.setText(model.accountEmail());
            username.setText(model.name() == null ? "" : model.name());
        }
        identitySection.header.setVisibility(identity && signedIn ? View.VISIBLE : View.GONE);
        if (identity && signedIn) {
            identitySection.header.setText(R.string.account_change_identity);
            identitySection.header.setTypeface(bold(identitySection.header.getTypeface()));
        }
        // 密码框：未登录时是登录/注册用的，改密码那一段有自己那三个，不能同时露出来。
        formGroup.group.setVisibility(!signedIn && !resetStage ? View.VISIBLE : View.GONE);
        changeGroup.group.setVisibility(changeStage ? View.VISIBLE : View.GONE);
        verifyGroup.group.setVisibility(model.verifyStage() ? View.VISIBLE : View.GONE);
        resetGroup.group.setVisibility(resetStage ? View.VISIBLE : View.GONE);
        // 两个入口：未登录时是「忘记密码？」，已登录时是「修改密码」。它们互斥——
        // 同时显示会让用户以为「忘记了」和「改密码」是同一件事（它们是两条不同的通道）。
        forgotLink.setVisibility(!signedIn && !testOnly ? View.VISIBLE : View.GONE);
        changeLink.setVisibility(signedIn && !testOnly ? View.VISIBLE : View.GONE);
        changeLink.setText(changeStage ? R.string.account_change_back : R.string.account_change_password);
        // 进了改密码那一段之后，这个链接就是「返回账号」了：它的动作也要跟着换，
        // 文字和动作对不上的话，用户点「返回账号」会再进一次改密码（看起来像卡住）。
        changeLink.setOnClickListener(v -> {
            if (model.changeStage()) {
                // 返回时清空三个密码框，理由同「返回账号」那个链接。
                currentPassword.setText(""); newPassword.setText(""); confirmPassword.setText("");
                model.backToForm();
            } else model.changePasswordStage();
            render();
        });
        message.setText(prompt);
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
    /** 忘记密码段的重发链接：同上，只是它是个 TextView（那一段没占中性按钮位）。 */
    private void renderResetResend(boolean busy) {
        long remaining = model.remainingResendMillis();
        int seconds = (int) Math.ceil(remaining / 1000.0);
        resetResend.setText(seconds > 0
                ? getString(R.string.account_resend_in, seconds) : getString(R.string.account_resend));
        // **冷却里也要能点**（和验证码段那个按钮不一样）：限流是服务端说了算，
        // 按 429 的 `Retry-After` 重新计时比让用户干等一个可能已经偏了的本地倒计时准。
        resetResend.setEnabled(true);
        resetResend.setAlpha(seconds > 0 || busy ? 0.5f : 1f);
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
        boolean busy = "BUSY".equals(model.state.getValue());
        // 同一个计时器服务两段：它们在编辑框上都得每秒重画，只是画在不同控件上。
        if (model.resetStage()) renderResetResend(busy); else renderResendButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), busy);
    }
    private void stopResendTimer() { if (resendTimer != null) { resendTimer.cancel(); resendTimer = null; } }
    /** 一个输入框在不在、是不是密码框。`null` 读成空串，省得每个调用点都判一次。 */
    private static String text(TextInputEditText field) {
        return field == null || field.getText() == null ? "" : field.getText().toString();
    }
    /**
     * 状态 → 提示语。**每一种失败都有自己的话**，落不到具体分支时也要说清是哪一类。
     *
     * <p>优先级：忙 → 本条状态自带的说明 → 本状态没话可说时用那一段的通用说明。
     * 忙必须排在最前：请求进行中却显示上一句失败提示，用户会以为这次又失败了。
     */
    private CharSequence messageFor() {
        String state = model.state.getValue() == null ? "IDLE" : model.state.getValue();
        boolean resetStage = model.resetStage();
        switch (state) {
            case "BUSY":
                return getString(resetStage ? R.string.account_resetting
                        : model.changeStage() ? R.string.account_changing
                        : model.verifyStage() ? R.string.account_verifying
                        : model.registerMode() ? R.string.account_registering : R.string.account_signing_in);
            // 注册成功但**还没验证**：这句话必须说清楚，否则用户以为已经完事了。
            case "REGISTERED": return getString(R.string.account_registered);
            // FAILED 的具体理由来自服务端，由 AccountInput 翻译好放在 notice 里
            // （邮箱被占 / 用户名被占 / 码错了 / 码过期了 …各是一句话）。
            case "CODE_SENT":
            case "FAILED":
            case "CHANGED": {
                int notice = noticeOr(0);
                // 忘记密码那屏发码成功时 notice 是「如果这个邮箱注册过…」，它和本段的通用
                // 说明说的是同一件事，所以有它就不再说通用那句（两句话叠在一起像复读）。
                return getString(notice == 0 ? defaultMessage(state) : notice);
            }
            case "REQUIRED": {
                // 忘记密码那屏的「没邮箱」：这一句由 ViewModel 挑好放在 notice 里——
                // 它是**唯一一件用户没法在这一屏修好的事**（要回上一层填邮箱），
                // 所以必须原样显示，不能被下面那句通用说明盖掉。
                int notice = noticeOr(0);
                return getString(notice == 0 ? R.string.account_reset_hint : notice);
            }
            case "VERIFIED": {
                // 验证成功（或密码重设成功），正在自动登录。理由放在 autoSignInReason 里——
                // 「邮箱验证成功了」和「密码已经改好了」是两件事，不能共用一句话。
                Integer reason = model.autoSignInReason.getValue();
                return getString(R.string.account_verified_signing_in,
                        getString(reason == null || reason == 0 ? R.string.account_verified : reason));
            }
            // 上一步成功了、但自动登录没成功。**必须把「哪一步成功了」说清楚**：用户
            // 得知道自己现在到底该拿哪个密码登录。
            case "VERIFIED_SIGN_IN_REQUIRED": {
                Integer reason = model.autoSignInReason.getValue();
                return getString(R.string.account_verified_manually_reason,
                        getString(reason == null || reason == 0 ? R.string.account_verified : reason));
            }
            case "RESET_SIGN_IN_REQUIRED": {
                // 重设/改密码之后自动登录失败：密码**已经改好了**，所以第一句必须是这个事实。
                int notice = noticeOr(0);
                return notice == 0 ? getString(R.string.account_reset_sign_in_required)
                        : getString(R.string.account_reset_sign_in_required_reason, getString(notice));
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
        return getString(defaultMessage(state));
    }
    /** 状态里没话可说时，这一段自己的说明（每个身份/段位各有一句）。 */
    private int defaultMessage(String state) {
        // 改密码成功之后**停在原地**（不 dismiss、不退出登录）：用户多半想确认一下
        // 「别的设备真的被踢了吗」，那句话就在这条提示里。而失败提示也必须留着——
        // 用户要能看着它决定下一步做什么。
        if ("CHANGED".equals(state)) {
            int notice = noticeOr(0);
            return notice == 0 ? R.string.account_change_done : notice;
        }        if (testOnly) return R.string.forum_test_explanation;
        if (model.changeStage()) return R.string.account_change_hint;
        if (model.signedIn()) return model.forumTest() ? R.string.forum_test_identity : R.string.account_signed_in;
        if (model.resetStage()) {
            // CODE_SENT 时上面已经用 notice 显示过「如果这个邮箱注册过…」了，
            // 走到这儿的是 IDLE/REQUIRED 那几种，说这一段的通用说明。
            return R.string.account_reset_hint;
        }
        if (model.verifyStage()) return R.string.account_code_hint;
        return model.registerMode() ? R.string.account_new : R.string.account_existing;
    }
    private int noticeOr(int fallback) {
        Integer notice = model.notice.getValue();
        return notice == null || notice == 0 ? fallback : notice;
    }
    /**
     * 一块面板：一个容器 + 一个可选的小标题。
     *
     * <p>存在的理由是「一块面板的整体可见性只写一次」。之前每加一段都要在
     * `onCreateDialog` 和 `render()` 里各补一排 `setVisibility`，漏一个就会出现
     * 两组输入框同时显示——用户完全不知道该填哪个。
     */
    private static final class Section {
        final LinearLayout group;
        TextView header;
        Section(android.content.Context context) { group = new LinearLayout(context); }
    }
}
