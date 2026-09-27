package com.mobilegroup20.tokentrail.ui.auth;

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
import com.mobilegroup20.tokentrail.R;

/** Login to the existing team account. Passwords are never saved in instance state. */
public final class AccountDialog extends DialogFragment {
    private AccountViewModel model;
    private TextInputEditText username, password;
    private TextView message;
    @NonNull @Override public Dialog onCreateDialog(Bundle state) {
        model = new ViewModelProvider(this).get(AccountViewModel.class);
        ContextThemeWrapper context = new ContextThemeWrapper(requireContext(), R.style.ForumTheme);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density); content.setPadding(pad, pad / 2, pad, 0);
        message = new TextView(context); content.addView(message);
        if (!model.signedIn()) {
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
        } else message.setText(getString(R.string.account_signed_in, model.name()));
        return new MaterialAlertDialogBuilder(context).setTitle(R.string.account_title).setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(model.signedIn() ? R.string.account_sign_out : R.string.account_sign_in, null).create();
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
            if (model.signedIn()) model.signOut(); else model.signIn(password.getText() == null ? "" : password.getText().toString());
        });
        model.state.removeObservers(this);
        model.state.observe(this, state -> {
            if ("SUCCESS".equals(state)) {
                getParentFragmentManager().setFragmentResult("accountChanged", new Bundle()); dismiss(); return;
            }
            boolean busy = "BUSY".equals(state);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            if (username != null) username.setEnabled(!busy);
            if (password != null) password.setEnabled(!busy);
            int resource = switch (state) {
                case "BUSY" -> R.string.account_signing_in;
                case "EMPTY" -> R.string.account_empty;
                case "CREDENTIALS" -> R.string.account_credentials_error;
                case "RATE_LIMIT" -> R.string.account_rate_limit;
                case "NETWORK" -> R.string.forum_network_error;
                case "NOT_CONFIGURED" -> R.string.forum_not_ready;
                case "SERVER", "STORAGE" -> R.string.forum_request_error;
                default -> 0;
            };
            if (resource != 0) message.setText(resource);
        });
    }
}
