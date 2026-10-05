package com.mobilegroup20.modelpilot.ui.settings;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.CallLedger;
import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.data.ProviderKeys;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.databinding.DialogApiKeysBinding;
import com.mobilegroup20.modelpilot.databinding.ItemConfiguredProviderBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 「API keys」设置页：**已配置的列出来，加新的走一步一屏**（2026-10-04 改版）。
 *
 * <p>改版原因：原来一打开就把六家全列出来、每家两个输入框，一共十二个框，
 * 再往下还有压缩模型。而绝大多数人只会配一两家——剩下四家纯占地方，
 * 而且"加一家"这件事被做成了"填六个表单"，用户不知道自己进行到哪一步。
 *
 * <p>现在这一页只有两件事：
 * <ol>
 *   <li><b>已配置</b>的列表（名字 + 请求地址 + 编辑 / 清除）；</li>
 *   <li>一颗 {@code Add provider} → 选一家（{@link ProviderPickerDialog}）→
 *       只填那一家的表单（{@link ProviderEditorDialog}）。</li>
 * </ol>
 *
 * <p>四条一直没变的规矩：key 只写不读（不预填、不显示）；留空 key = 不改 key
 * （但地址照样能改）；地址填错当场拦住；顺带放着的**压缩模型**选择
 * （没选过就是 Auto 挑最便宜的）。
 */
public final class ApiKeysDialog extends DialogFragment {

    private DialogApiKeysBinding binding;

    public static void show(FragmentManager fm) {
        if (fm.findFragmentByTag("api_keys") == null) {
            new ApiKeysDialog().show(fm, "api_keys");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle saved) {
        binding = DialogApiKeysBinding.inflate(LayoutInflater.from(requireContext()));
        binding.keysAdd.setOnClickListener(v -> ProviderPickerDialog.show(getChildFragmentManager()));
        binding.keysCompressionValue.setOnClickListener(v -> pickCompression());

        // 两个子对话框都只发结果，落库/刷新在这里。监听只注册一次
        // （注册在点击里的那份，转屏之后就没了）。
        //
        // **LifecycleOwner 必须是 `this`（这个 DialogFragment），不能用
        // `getViewLifecycleOwner()`**：注册发生在 `onCreateDialog` 里，那一刻
        // 对话框的视图还没建，`getViewLifecycleOwner()` 直接抛
        // `IllegalStateException: Can't access the Fragment View's LifecycleOwner…`
        // —— 2026-10-05 真机上就是这么崩的（点开 API keys 就闪退）。
        // 用 `this` 的生命周期也对：这两个监听要活到对话框自己被销毁为止。
        getChildFragmentManager().setFragmentResultListener(ProviderPickerDialog.RESULT_KEY,
                this, (key, result) -> {
                    String providerId = result.getString(ProviderPickerDialog.BUNDLE_PROVIDER);
                    if (providerId != null) {
                        ProviderEditorDialog.open(getChildFragmentManager(), providerId);
                    }
                });
        getChildFragmentManager().setFragmentResultListener(ProviderEditorDialog.RESULT_KEY,
                this, (key, result) -> renderConfigured());

        renderConfigured();
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.keys_title)
                .setView(binding.getRoot())
                .setPositiveButton(android.R.string.ok, null)
                .create();
    }

    /** 已配置的列表。**自定义端点显示用户给的名字**，内置家显示注册表里的显示名。 */
    private void renderConfigured() {
        if (binding == null) {
            return;
        }
        ProviderRegistry registry = RepositoryProvider.providers();
        List<String> configured = ProviderKeys.configuredProviders(requireContext());
        binding.keysContainer.removeAllViews();
        binding.keysEmpty.setVisibility(configured.isEmpty() ? View.VISIBLE : View.GONE);
        for (String providerId : configured) {
            ProviderSpec spec = registry.provider(providerId);
            ItemConfiguredProviderBinding row = ItemConfiguredProviderBinding.inflate(
                    getLayoutInflater(), binding.keysContainer, false);
            row.configuredName.setText(spec == null ? providerId : spec.displayName);
            row.configuredUrl.setText(ProviderKeys.baseUrl(requireContext(), providerId));
            row.configuredEdit.setOnClickListener(v ->
                    ProviderEditorDialog.open(getChildFragmentManager(), providerId));
            row.configuredClear.setOnClickListener(v -> {
                ProviderKeys.clear(requireContext(), providerId);
                // 删掉之后**下一次 providers() 必须重新算**：不重算的话这一家还在候选里，
                // 用户会看到"删了但 Auto 还在挑它"。
                RepositoryProvider.reloadProviders();
                renderConfigured();
            });
            binding.keysContainer.addView(row.getRoot());
        }
        renderCompressionLabel();
    }

    // ---- 压缩模型 ------------------------------------------------------

    private void renderCompressionLabel() {
        if (binding == null) {
            return;
        }
        binding.keysCompressionValue.setText(compressionLabel());
    }

    private String compressionLabel() {
        String[] chosen = ProviderKeys.compressionModel(requireContext());
        if (chosen == null) {
            return getString(R.string.keys_compression_auto);
        }
        ModelSpec model = RepositoryProvider.providers().model(chosen[0], chosen[1]);
        return model == null ? chosen[1] : model.displayName;
    }

    /**
     * 选压缩模型。
     *
     * <p>候选 = **配了 key 的**那几家的**全部**模型（不限能力）：压缩是纯文本任务，
     * 但用户可能就想用某个只支持文本的便宜模型，这里替他筛掉反而是多管闲事。
     */
    private void pickCompression() {
        ProviderRegistry registry = RepositoryProvider.providers();
        List<String> configured = ProviderKeys.configuredProviders(requireContext());
        final List<String[]> choices = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.keys_compression_auto));
        choices.add(null);
        for (String providerId : configured) {
            ProviderSpec spec = registry.provider(providerId);
            if (spec == null) {
                continue;
            }
            for (ModelSpec model : spec.models) {
                labels.add(spec.displayName + " · " + model.displayName);
                choices.add(new String[] {providerId, model.modelId});
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.keys_compression_pick)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String[] pick = choices.get(which);
                    ProviderKeys.saveCompressionModel(requireContext(),
                            pick == null ? null : pick[0], pick == null ? null : pick[1]);
                    renderCompressionLabel();
                })
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // DialogFragment 的视图随对话框销毁；留着它就是把一整棵没用的视图树钉在内存里。
        binding = null;
    }
}
