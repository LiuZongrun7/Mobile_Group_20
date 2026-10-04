package com.mobilegroup20.modelpilot.ui.settings;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.data.ProviderKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * 「加哪一家」的选择框（`API keys → Add provider`）。
 *
 * <p>**它只负责选，不负责填**：选完由 {@link ProviderEditorDialog} 只显示那一家。
 * 这么拆的原因是用户要做的决定其实是两个——"加谁"和"填什么"——
 * 六个表单摊在一屏时，这两件事混在一起，人就不知道自己进行到哪一步了。
 *
 * <p>列表末尾两项是**自定义端点**：用户自己选请求格式（OpenAI 兼容 /
 * Anthropic Messages）。没有这两项的话，想接自建反代的人只能借用某一家那一行，
 * 于是模型名也被锁死在内置清单里。
 */
public final class ProviderPickerDialog extends DialogFragment {

    /** 选完发结果，取 {@link #BUNDLE_PROVIDER}（自定义端点时是 `custom-openai` 这类标识）。 */
    public static final String RESULT_KEY = "provider_pick";
    public static final String BUNDLE_PROVIDER = "provider_id";

    /** 自定义端点在"选择"这一步用的临时标识：选定之后才生成真正的 id。 */
    static final String CUSTOM_OPENAI = "custom-openai";
    static final String CUSTOM_ANTHROPIC = "custom-anthropic";

    public static void show(FragmentManager fm) {
        if (fm.findFragmentByTag("provider_picker") == null) {
            new ProviderPickerDialog().show(fm, "provider_picker");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle saved) {
        ProviderRegistry registry = com.mobilegroup20.modelpilot.data.RepositoryProvider.providers();
        List<String> configured = ProviderKeys.configuredProviders(requireContext());

        final List<String> ids = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (ProviderSpec spec : registry.providers()) {
            if (com.mobilegroup20.modelpilot.chat.CallLedger.isCustom(spec.providerId)) {
                continue;                       // 自定义的已经配过的，用"编辑"改，不在这里重复加
            }
            ids.add(spec.providerId);
            // 已配置的打一个勾：不然用户会以为"没配过"而再填一遍，
            // 而再填一遍会把原来那条覆盖掉（地址也一起换掉）。
            labels.add(spec.displayName + (configured.contains(spec.providerId) ? "  ✓" : ""));
        }
        ids.add(CUSTOM_OPENAI);
        labels.add(getString(R.string.keys_custom_openai));
        ids.add(CUSTOM_ANTHROPIC);
        labels.add(getString(R.string.keys_custom_anthropic));

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.keys_pick_provider)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    Bundle result = new Bundle();
                    result.putString(BUNDLE_PROVIDER, ids.get(which));
                    getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
                    dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }
}
