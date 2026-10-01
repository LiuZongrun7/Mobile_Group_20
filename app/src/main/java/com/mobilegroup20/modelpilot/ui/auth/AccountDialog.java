package com.mobilegroup20.modelpilot.ui.auth;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.View;
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
 * 账号对话框：登录 / 注册 / 退出，以及（Debug）免账号进论坛测试区。
 * <b>负责人：刘宗润。</b>
 *
 * <p>账号是**这个 App 自己的**账号（后端 `/api/account/*`），不再是团队那台机器上的
 * 账号服务。同一个 `userId` 用来发帖、读用量、结算游戏资源——这就是「服务端为唯一
 * 数据源」在身份上的那一半。
 *
 * <p>密码**从不进 instance state**（`setSaveEnabled(false)`）：系统可能把它写进
 * 磁盘上的 Bundle，那等于把密码明文留在手机上。
 */
public final class AccountDialog extends DialogFragment {
    private AccountViewModel model;
    private TextInputEditText username, password;
    private TextView message;
    private boolean testOnly;
    /** 当前是「注册」还是「登录」。默认登录；切换只改界面，不发请求。 */
    private boolean registerMode;
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
        if (!model.signedIn() && !testOnly) {
            username = input(content, R.string.account_username, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
            password = input(content, R.string.account_password, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setSaveEnabled(false); password.setFreezesText(false);
            username.setText(model.username());
            username.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                public void onTextChanged(CharSequence s, int start, int before, int count) { model.username(s.toString()); }
                public void afterTextChanged(Editable value) { }
            });
            message.setText(R.string.account_existing);
        } else message.setText(testOnly ? getString(R.string.forum_test_explanation) : getString(model.forumTest() ? R.string.forum_test_identity : R.string.account_signed_in, model.name()));
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(testOnly ? R.string.forum_test_enter : R.string.account_title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(model.signedIn()
                        ? model.forumTest() ? R.string.forum_test_leave : R.string.account_sign_out
                        : testOnly ? R.string.forum_retry : R.string.account_sign_in, null);
        // 未登录时给两个按钮：左边「注册」（点了切换到注册模式），
        // 右边始终是当前模式的提交按钮。测试入口只在 Debug 里追加进来。
        if (!model.signedIn() && !testOnly) builder.setNeutralButton(R.string.account_register, null);
        return builder.create();
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
            String secret = password == null || password.getText() == null
                    ? "" : password.getText().toString();
            if (registerMode) model.register(secret); else model.signIn(secret);
        });
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                // 「注册 ⇄ 登录」只是切换界面，**不发请求**。切过去还能切回来。
                registerMode = !registerMode;
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(
                        registerMode ? R.string.account_register : R.string.account_sign_in);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setText(
                        registerMode ? R.string.account_sign_in : R.string.account_register);
                message.setText(registerMode ? R.string.account_new : R.string.account_existing);
            });
        }
        model.state.removeObservers(this);
        model.state.observe(this, state -> {
            if ("SUCCESS".equals(state)) {
                getParentFragmentManager().setFragmentResult("accountChanged", new Bundle()); dismiss(); return;
            }
            boolean busy = "BUSY".equals(state);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!busy);
            if (username != null) username.setEnabled(!busy);
            if (password != null) password.setEnabled(!busy);
            int resource = switch (state) {
                case "BUSY" -> registerMode ? R.string.account_registering : R.string.account_signing_in;
                case "EMPTY" -> R.string.account_empty;
                // 本地校验拦下来的：具体理由在 `invalidReason` 里，比「格式不对」有用。
                case "INVALID" -> {
                    Integer reason = model.invalidReason.getValue();
                    yield reason == null || reason == 0 ? R.string.account_empty : reason;
                }
                case "TAKEN" -> R.string.account_taken;
                case "CREDENTIALS" -> R.string.account_credentials_error;
                case "RATE_LIMIT" -> R.string.account_rate_limit;
                case "NETWORK" -> R.string.forum_network_error;
                case "NOT_CONFIGURED" -> R.string.forum_not_ready;
                case "TEST_UNAVAILABLE" -> R.string.forum_test_unavailable;
                case "SERVER", "STORAGE" -> R.string.forum_request_error;
                default -> 0;
            };
            if (resource != 0) message.setText(resource);
        });
        if (testOnly && "IDLE".equals(model.state.getValue())) model.enterForumTest();
    }
}
