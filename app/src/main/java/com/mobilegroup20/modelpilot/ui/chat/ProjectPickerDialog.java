package com.mobilegroup20.modelpilot.ui.chat;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * "这条对话属于哪个项目"的挑选框（输入条右边那颗 `No project ▾`）。
 *
 * <p>第一项固定是 `No project`（= 不属于任何项目），后面按项目最近活跃的顺序排。
 * **当前选中的那项带勾**：这类单选列表最容易出的问题是"看不出现在是哪个"，
 * 于是用户点开只是想确认一下，反而点错。
 *
 * <p>和 {@link ProjectNameDialog} 一样，只发结果不落库，结果键见 {@link #RESULT_KEY}。
 */
public final class ProjectPickerDialog extends DialogFragment {

    public static final String RESULT_KEY = "project_pick";
    /** 选中的项目 id；**空串表示 `No project`**（和 `ChatEntity.projectId` 的口径一致）。 */
    public static final String BUNDLE_PROJECT_ID = "project_id";

    private static final String ARG_IDS = "ids";
    private static final String ARG_NAMES = "names";
    private static final String ARG_SELECTED = "selected";

    public static void show(FragmentManager fm, List<ProjectEntity> projects, String selectedId) {
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        // 第一项：No project。它不是一个真项目，所以在数据里用空串表示。
        // **名字留空**，由 onCreateDialog 用资源里的文案补上——静态方法里没有 Context，
        // 硬编一句英文会绕过 strings.xml（那正是这个项目不许干的事）。
        ids.add("");
        names.add("");
        for (ProjectEntity project : projects) {
            ids.add(project.id);
            // 没名字的项目在数据里就是空串，同样留到界面上再补 "Untitled"。
            names.add(project.name == null ? "" : project.name);
        }
        Bundle args = new Bundle();
        args.putStringArrayList(ARG_IDS, ids);
        args.putStringArrayList(ARG_NAMES, names);
        args.putString(ARG_SELECTED, selectedId == null ? "" : selectedId);
        ProjectPickerDialog dialog = new ProjectPickerDialog();
        dialog.setArguments(args);
        dialog.show(fm, "project_picker");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle saved) {
        Bundle args = requireArguments();
        ArrayList<String> ids = args.getStringArrayList(ARG_IDS);
        ArrayList<String> names = args.getStringArrayList(ARG_NAMES);
        String selected = args.getString(ARG_SELECTED, "");
        final String[] labels = new String[names == null ? 0 : names.size()];
        for (int i = 0; i < labels.length; i++) {
            String name = names.get(i);
            if (name != null && !name.isEmpty()) {
                labels[i] = name;
            } else {
                labels[i] = getString(i == 0 ? R.string.chat_no_project : R.string.chat_untitled);
            }
        }
        int checked = ids == null ? 0 : Math.max(0, ids.indexOf(selected));
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.chat_pick_project)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (ids != null && which < ids.size()) {
                        Bundle result = new Bundle();
                        result.putString(BUNDLE_PROJECT_ID, ids.get(which));
                        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
                    }
                    dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }
}
