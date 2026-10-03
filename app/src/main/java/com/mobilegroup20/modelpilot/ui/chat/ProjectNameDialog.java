package com.mobilegroup20.modelpilot.ui.chat;

import android.app.Dialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.databinding.DialogProjectNameBinding;

/**
 * 新建项目 / 重命名项目用的输入框。
 *
 * <p>**它只负责"问一个名字"，不负责落库**：结果通过 `setFragmentResult` 发给
 * 开它的那个界面（见下面 {@link #RESULT_KEY} 的说明）。这样同一个对话框既能让首页
 * 用来建项目，也能让以后的项目设置页用来改名，而"改完存到哪"始终只有一个地方知道。
 *
 * <p>名字允许为空（大纲里项目名不是必填），但**空名字在界面上不显示成空白**——
 * 列表里会显示成 `Untitled`（`chat_untitled`），所以这里不必拦着用户不让他提交空。
 */
public final class ProjectNameDialog extends DialogFragment {

    /**
     * 结果键。调用方用 {@code getChildFragmentManager().setFragmentResultListener(...)}
     * 在这个键上等结果，取 {@link #BUNDLE_NAME}。
     *
     * <p>为什么不用 Java 回调：对话框会被系统重建（转屏、进程被回收后回来），
     * 而回调对象装不进 Bundle。`FragmentResult` 走的是 FragmentManager，
     * 重建之后照样能把结果交回去。
     */
    public static final String RESULT_KEY = "project_name";
    public static final String BUNDLE_NAME = "name";

    private static final String ARG_TITLE = "title";
    private static final String ARG_INITIAL = "initial";

    /** 打开对话框。`initial` 用于重命名时带出旧名字；新建时传 null。 */
    public static void show(FragmentManager fm, int titleRes, @Nullable String initial) {
        Bundle args = new Bundle();
        args.putInt(ARG_TITLE, titleRes);
        args.putString(ARG_INITIAL, initial);
        ProjectNameDialog dialog = new ProjectNameDialog();
        dialog.setArguments(args);
        dialog.show(fm, "project_name");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle saved) {
        DialogProjectNameBinding binding = DialogProjectNameBinding.inflate(
                LayoutInflater.from(requireContext()));
        Bundle args = getArguments();
        String initial = args == null ? null : args.getString(ARG_INITIAL);
        binding.projectNameInput.setText(initial);
        if (!TextUtils.isEmpty(initial)) {
            // 光标放到末尾：用户多半是要改一个字，不是要重打一遍。
            binding.projectNameInput.setSelection(initial.length());
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(args == null ? R.string.chat_new_project : args.getInt(ARG_TITLE))
                .setView(binding.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.chat_project_save, (d, which) ->
                        publish(binding.projectNameInput.getText().toString()));
        // 键盘上那个"完成"和按保存是同一件事。少了它，用户敲完会去找按钮，
        // 而软键盘正好挡着按钮。
        binding.projectNameInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            publish(v.getText().toString());
            dismiss();
            return true;
        });
        return builder.create();
    }

    private void publish(String raw) {
        Bundle result = new Bundle();
        // 首尾空白在这里就去掉：项目名是给人看的标签，前后空格在列表里看不出来，
        // 但会让"同名"判断失效。
        result.putString(BUNDLE_NAME, raw == null ? "" : raw.trim());
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
    }
}
