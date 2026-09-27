package com.mobilegroup20.tokentrail.ui.auth;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
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
import com.mobilegroup20.tokentrail.BuildConfig;
import com.mobilegroup20.tokentrail.R;

/**
 * API 中转的设置页。<b>负责人：汪庭栋。</b>
 *
 * <p>两种形态由「本机有没有 relay key」决定：
 * <ul>
 *   <li><b>没注册过</b>：填上游地址 + 上游 key（+ 可选的自定义 relay key），提交注册。</li>
 *   <li><b>已注册</b>：回显上游地址，可以改地址/换上游 key，或者停用。</li>
 * </ul>
 *
 * <p><b>上游 API key 不回显。</b>它注册时上行一次就只存在服务器上（见
 * {@code docs/DATA_SOURCES.md} §5），本机没有副本，所以这里没有「显示已保存的 key」
 * 这种功能——想做也做不到，这是有意的。
 */
public final class RelaySetupDialog extends DialogFragment {
    private RelaySetupViewModel model;
    private TextInputEditText upstreamUrl, upstreamKey, relayKey, displayName;
    private TextView message;
    /** 名下 key 清单的三块：一句标题、若干可点行、一句脚注。 */
    private TextView keyIntro, keyFootnote;
    private LinearLayout keyRows;

    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        model = new ViewModelProvider(this).get(RelaySetupViewModel.class);
        boolean configured = model.configured();
        ContextThemeWrapper context = new ContextThemeWrapper(requireContext(), R.style.ForumTheme);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad / 2, pad, 0);

        message = new TextView(context);
        content.addView(message);

        // 「我录了什么」那一块。**放在输入框上面**：用户打开这个对话框，
        // 第一个要回答的问题往往是「我之前录过吗、都录了什么」，
        // 而不是「再填一次」。
        //
        // **每一行都能单独停用**（2026-02 改）。上一版只能停「本机这条」或
        // 「列表第一条」，中间那条没有入口——而一个账号本来就可以有多条。
        // 行是代码里拼的，不是 RecyclerView：这个对话框只有几条，
        // 而 RecyclerView 要配一个 layout 文件和一套 Adapter，为了几行不值。
        keyIntro = new TextView(context);
        keyIntro.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        keyIntro.setPadding(0, pad / 2, 0, 0);
        content.addView(keyIntro);
        keyRows = new LinearLayout(context);
        keyRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(keyRows);
        keyFootnote = new TextView(context);
        keyFootnote.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        keyFootnote.setText(R.string.relay_keys_no_plaintext);
        keyFootnote.setPadding(0, pad / 4, 0, pad / 2);
        content.addView(keyFootnote);
        message.setText(configured
                ? getString(R.string.relay_configured, model.upstreamUrl())
                : getString(R.string.relay_explanation, BuildConfig.FORUM_BASE_URL + "api/relay"));

        upstreamUrl = input(content, R.string.relay_upstream_url, InputType.TYPE_TEXT_VARIATION_URI);
        if (configured) upstreamUrl.setText(model.upstreamUrl());
        // 密码型输入：上游 key 不该在屏幕上明文停留，也不该被输入法记住。
        upstreamKey = input(content, R.string.relay_upstream_key,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        upstreamKey.setSaveEnabled(false);
        upstreamKey.setFreezesText(false);
        if (!configured) {
            relayKey = input(content, R.string.relay_relay_key, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            relayKey.setSaveEnabled(false);
            displayName = input(content, R.string.relay_display_name, InputType.TYPE_CLASS_TEXT);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.relay_title).setView(content)
                .setNegativeButton(R.string.relay_close, null)
                .setPositiveButton(configured ? R.string.relay_update : R.string.relay_enroll, null);
        // 本机配了中转时留一个「停用本机这条」的快捷入口；
        // 名下的**其它** key 在下面的清单里各自有按钮。
        if (configured) builder.setNeutralButton(R.string.relay_revoke, null);
        return builder.create();
    }

    private TextInputEditText input(LinearLayout parent, int hint, int type) {
        TextInputLayout wrapper = new TextInputLayout(parent.getContext());
        wrapper.setHint(hint);
        TextInputEditText field = new TextInputEditText(parent.getContext());
        field.setInputType(type);
        wrapper.addView(field);
        parent.addView(wrapper);
        return field;
    }

    private String text(TextInputEditText field) {
        return field == null || field.getText() == null ? "" : field.getText().toString();
    }

    @Override public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) requireDialog();
        boolean configured = model.configured();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (configured) model.update(text(upstreamUrl), text(upstreamKey));
            else model.enroll(text(upstreamUrl), text(upstreamKey), text(relayKey), text(displayName));
        });
        if (configured && dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> model.revoke());

        model.keys.removeObservers(this);
        model.keys.observe(this, this::renderKeys);
        model.loadKeys();

        model.state.removeObservers(this);
        model.state.observe(this, state -> {
            if ("SUCCESS".equals(state)) {
                // 自动生成的情况下，这个 key 只在响应里出现一次，必须让用户看到再关。
                String issued = model.issuedKey.getValue();
                if (issued != null) { showIssuedKey(issued); return; }
                notifyChanged();
                return;
            }
            if ("UPDATED".equals(state)) { notifyChanged(); return; }
            // 停用某一条之后**不关对话框**：这个页面就是管理名下的 key，
            // 关掉会让人以为整套中转被关掉了。
            if ("REVOKED_ONE".equals(state)) { message.setText(R.string.relay_revoked_one); return; }
            boolean busy = "BUSY".equals(state);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!busy);
            int resource = switch (state) {
                case "BUSY" -> R.string.relay_busy;
                case "INVALID_URL", "INVALID_RELAY_KEY" -> model.validationMessage();
                case "EMPTY_KEY" -> R.string.relay_key_empty;
                case "DUPLICATE" -> R.string.relay_duplicate;
                case "INVALID_SERVER" -> R.string.relay_invalid_server;
                case "NOTHING_TO_UPDATE" -> R.string.relay_nothing_to_update;
                case "SIGNED_OUT" -> R.string.relay_signed_out;
                // 还没登录。和「key 失效」是两回事，说清楚要先去登录。
                case "NO_ACCOUNT" -> R.string.relay_needs_account;
                case "PARTIAL_REVOKE" -> R.string.relay_partial_revoke;
                case "NETWORK" -> R.string.relay_network_error;
                case "NOT_CONFIGURED" -> R.string.relay_not_ready;
                case "RATE_LIMIT" -> R.string.account_rate_limit;
                default -> R.string.forum_request_error;
            };
            if (resource != 0 && !"IDLE".equals(state)) message.setText(resource);
        });
    }

    /**
     * 注册成功后把 relay key 显示一次。
     *
     * <p>换成另一个对话框而不是改正文，是因为**这个字符串必须能被完整选中复制**，
     * 而正文里的 TextView 很容易被用户忽略掉一半。加一个复制按钮省掉手抄。
     */
    private void showIssuedKey(String issued) {
        ContextThemeWrapper context = new ContextThemeWrapper(requireContext(), R.style.ForumTheme);
        TextView body = new TextView(context);
        body.setTextIsSelectable(true);
        body.setText(getString(R.string.relay_issued_body, issued));
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad / 2, pad, 0);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.relay_issued_title).setView(body)
                .setPositiveButton(R.string.relay_close, (ignored, which) -> notifyChanged())
                .setNeutralButton(R.string.relay_issued_copy, (ignored, which) -> {
                    copy(issued);
                    notifyChanged();
                })
                .show();
    }

    /**
     * 把清单画出来：**一行一条，每行带一个「停用」**。
     *
     * <p>只写**元数据**：备注名、指向哪个上游、什么时候注册/最后用过、转发过多少次。
     * 明文 key 一条都拿不到（服务端只存 sha256），所以这里也没有——想复制 key
     * 只能在注册成功那一次复制，之后认不出来就重新注册一条。
     *
     * <p>三种状态要说三句不同的话：**查不到**（网络/登录问题）、**一条都没有**、
     * 有 N 条。把「查不到」显示成「你没有」是假信息，用户会以为 key 被删了。
     */
    private void renderKeys(java.util.List<com.mobilegroup20.tokentrail.data.remote.RelayApi.Enrollment> items) {
        if (keyRows == null) return;
        keyRows.removeAllViews();
        if (items == null) {
            keyIntro.setText(R.string.relay_keys_unknown);
            keyFootnote.setVisibility(View.GONE);
            return;
        }
        keyFootnote.setVisibility(View.VISIBLE);
        if (items.isEmpty()) {
            keyIntro.setText(R.string.relay_keys_none);
            return;
        }
        keyIntro.setText(getString(R.string.relay_keys_title, items.size()));
        for (com.mobilegroup20.tokentrail.data.remote.RelayApi.Enrollment item : items)
            keyRows.addView(keyRow(item));
    }

    /** 一条 key 的一行：左边是说明，右边是它自己的「停用」。 */
    private View keyRow(com.mobilegroup20.tokentrail.data.remote.RelayApi.Enrollment item) {
        ContextThemeWrapper context = new ContextThemeWrapper(requireContext(), R.style.ForumTheme);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);

        TextView label = new TextView(context);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        label.setText(describeKey(item));
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 已经停用的那条不再给按钮：**再点一次没有任何效果**，
        // 给一个按了没反应的按钮比不给更让人困惑。
        if (!item.disabled) {
            com.google.android.material.button.MaterialButton stop =
                    new com.google.android.material.button.MaterialButton(context, null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle);
            stop.setText(R.string.relay_revoke_one);
            stop.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            stop.setMinWidth(0);
            stop.setOnClickListener(v -> model.revokeKey(item.uid));
            row.addView(stop);
        }
        return row;
    }

    /** 一行的说明文字。 */
    private CharSequence describeKey(com.mobilegroup20.tokentrail.data.remote.RelayApi.Enrollment item) {
        String name = item.displayName == null || item.displayName.isEmpty()
                ? getString(R.string.relay_keys_unnamed) : item.displayName;
        StringBuilder text = new StringBuilder();
        text.append("· ").append(name);
        if (model.isLocal(item.uid)) text.append(getString(R.string.relay_keys_this_device));
        if (item.disabled) text.append(getString(R.string.relay_keys_disabled));
        text.append('\n').append(getString(R.string.relay_keys_upstream,
                item.upstreamHost == null
                        ? getString(R.string.relay_keys_unknown_host) : item.upstreamHost));
        text.append('\n').append(getString(R.string.relay_keys_created,
                com.mobilegroup20.tokentrail.util.TimeUtils.dayOf(item.createdAtEpochMillis)));
        text.append('\n').append(item.lastUsedAtEpochMillis == null
                ? getString(R.string.relay_keys_never_used)
                : getString(R.string.relay_keys_last_used,
                        com.mobilegroup20.tokentrail.util.TimeUtils.dayOf(item.lastUsedAtEpochMillis),
                        item.requestCount));
        return text;
    }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.relay_title), value));
    }

    private void notifyChanged() {
        getParentFragmentManager().setFragmentResult("relayChanged", new Bundle());
        dismiss();
    }
}
