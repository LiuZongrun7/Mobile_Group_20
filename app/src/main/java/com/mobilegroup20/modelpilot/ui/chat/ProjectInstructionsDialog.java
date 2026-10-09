package com.mobilegroup20.modelpilot.ui.chat;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Edits only the explicit instructions for this project; no histories are shared automatically. */
public final class ProjectInstructionsDialog extends DialogFragment {
    private TextInputEditText input;
    private TextInputLayout field;
    private boolean loaded;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    public static void open(FragmentManager manager, String projectId) {
        if (manager.findFragmentByTag("project_instructions") != null) return;
        ProjectInstructionsDialog dialog = new ProjectInstructionsDialog();
        Bundle args = new Bundle(); args.putString("project", projectId); dialog.setArguments(args);
        dialog.show(manager, "project_instructions");
    }
    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle saved) {
        input = new TextInputEditText(requireContext()); input.setMinLines(4); input.setMaxLines(10);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field = new TextInputLayout(requireContext()); field.setHint(R.string.project_instructions_hint);
        field.setCounterEnabled(true); field.setCounterMaxLength(4096); field.addView(input);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        field.setPadding(padding, padding, padding, 0);
        if (saved != null) input.setText(saved.getString("draft", ""));
        RepositoryProvider.chats().projectLive(requireArguments().getString("project")).observe(this, project -> {
            if (project == null) { field.setError(getString(R.string.project_instructions_missing)); return; }
            if (!loaded && saved == null) input.setText(project.instructions);
            loaded = true;
        });
        return new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.project_instructions_title)
                .setMessage(R.string.project_instructions_note).setView(field)
                .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create();
    }
    @Override public void onStart() {
        super.onStart();
        ((androidx.appcompat.app.AlertDialog) requireDialog()).getButton(-1).setOnClickListener(v -> {
            if (!loaded) return;
            String text = input.getText() == null ? "" : input.getText().toString().trim();
            if (text.length() > 4096) { field.setError(getString(R.string.project_instructions_too_long)); return; }
            String projectId = requireArguments().getString("project");
            v.setEnabled(false);
            io.execute(() -> {
                try {
                    int updated = RepositoryProvider.chats().updateProjectInstructions(projectId, text, System.currentTimeMillis());
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!isAdded()) return;
                        if (updated > 0) dismiss();
                        else { field.setError(getString(R.string.project_instructions_missing)); v.setEnabled(true); }
                    });
                } catch (RuntimeException failure) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (isAdded()) { field.setError(getString(R.string.project_instructions_save_failed)); v.setEnabled(true); }
                    });
                }
            });
        });
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("draft", input == null || input.getText() == null ? "" : input.getText().toString());
    }
    @Override public void onDestroy() { io.shutdown(); super.onDestroy(); }
}
