package com.mobilegroup20.modelpilot.ui.settings;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.CallLedger;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.data.ProviderKeys;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.databinding.DialogProviderEditorBinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 添加 / 编辑**一家** provider 的表单。
 *
 * <p>内置那六家只有两个框（key、请求地址）；自定义端点（用户自己选格式）多出
 * 显示名、模型清单、上下文上限、`stream_options` 开关。字段按"这家是什么"显隐，
 * 而不是把所有框都摆出来让人猜哪些要填。
 *
 * <p>三条和别处一致的规矩：
 *
 * <ol>
 *   <li><b>已存的 key 不回显。</b>编辑内置家时 key 框是空的，留空 = 不改；
 *       这一页顺带给了个"显示"眼睛（`password_toggle`）——那是对**正在输入**的这一次，
 *       和"把存下来的 key 显示回屏幕"是两件事。</li>
 *   <li><b>上下文上限必填</b>（自定义端点）。木桶效应按所有已配置模型里最小的
 *       上限算压缩阈值；留空让它进去的话阈值会变成 0 = 从不压缩，
 *       表现却是"聊到一半上游报超长"，完全看不出跟这个框有关。</li>
 *   <li><b>能力一律按文本处理</b>（`ProviderKeys.read` 里那段）：用户自己填的端点
 *       我们没法验证它吃不吃图/PDF/工具，猜"能看图"会让 Auto 在图片任务上挑一个
 *       根本发不出去的家。界面上也照实说一句。</li>
 * </ol>
 */
public final class ProviderEditorDialog extends DialogFragment {

    /** 保存成功后发结果（值为 providerId），调用方据此刷新列表。 */
    public static final String RESULT_KEY = "provider_saved";
    public static final String BUNDLE_PROVIDER = "provider_id";

    private static final String ARG_PROVIDER = "provider";
    private static final String ARG_CUSTOM = "custom";

    /**
     * 打开表单。
     *
     * @param providerId 内置家用它的 id；新增自定义端点时传 `custom-openai` /
     *                   `custom-anthropic`（格式在上一步已经选定了）
     */
    public static void open(FragmentManager fm, String providerId) {
        Bundle args = new Bundle();
        args.putString(ARG_PROVIDER, providerId);
        ProviderEditorDialog dialog = new ProviderEditorDialog();
        dialog.setArguments(args);
        dialog.show(fm, "provider_editor");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle saved) {
        DialogProviderEditorBinding binding =
                DialogProviderEditorBinding.inflate(LayoutInflater.from(requireContext()));
        String requested = requireArguments().getString(ARG_PROVIDER, "");
        ProviderSpec existing = RepositoryProvider.providers().provider(requested);
        boolean customNew = ProviderPickerDialog.CUSTOM_OPENAI.equals(requested)
                || ProviderPickerDialog.CUSTOM_ANTHROPIC.equals(requested);
        boolean custom = customNew || CallLedger.isCustom(requested);
        ProviderSpec.Adapter adapter = custom
                ? (ProviderPickerDialog.CUSTOM_ANTHROPIC.equals(requested)
                        ? ProviderSpec.Adapter.ANTHROPIC
                        : ProviderSpec.Adapter.OPENAI_COMPATIBLE)
                : (existing == null ? ProviderSpec.Adapter.OPENAI_COMPATIBLE : existing.adapter);

        // 自定义端点的"格式"是在上一步选的，这里只显示出来（不能再改：改了要连带
        // 换掉模型清单与地址口径，那等于换一家，用户重加一次更清楚）。
        binding.editorHeading.setText(getString(
                customNew ? R.string.keys_add_title : R.string.keys_edit_title,
                customNew ? getString(adapter == ProviderSpec.Adapter.ANTHROPIC
                        ? R.string.keys_custom_anthropic : R.string.keys_custom_openai)
                        : (existing == null ? requested : existing.displayName)));
        binding.editorNote.setText(custom ? R.string.keys_custom_note : R.string.keys_builtin_note);
        toggle(binding.editorNameBox, custom);
        toggle(binding.editorModelsBox, custom);
        toggle(binding.editorContextBox, custom);
        toggle(binding.editorStreamUsage, custom);
        if (!custom && existing != null) {
            // 请求地址预填（那不是秘密，用户通常只改域名那一段）；**key 永远不预填**。
            binding.editorUrl.setText(existing.baseUrl);
        }

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(custom ? R.string.keys_pick_provider : R.string.keys_title)
                .setView(binding.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.keys_save, null)   // 下面自己接，校验失败不关框
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        // 自己接保存：校验不过时**不能关对话框**（关掉用户填的就全没了），
        // 而 MaterialAlertDialogBuilder 的默认行为是先关再回调。
        Dialog dialog = getDialog();
        if (dialog instanceof androidx.appcompat.app.AlertDialog) {
            ((androidx.appcompat.app.AlertDialog) dialog)
                    .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> save());
        }
    }

    private void save() {
        View root = getView();
        if (root == null) {
            return;
        }
        DialogProviderEditorBinding binding = DialogProviderEditorBinding.bind(root);
        String requested = requireArguments().getString(ARG_PROVIDER, "");
        boolean customNew = ProviderPickerDialog.CUSTOM_OPENAI.equals(requested)
                || ProviderPickerDialog.CUSTOM_ANTHROPIC.equals(requested);
        boolean custom = customNew || CallLedger.isCustom(requested);
        // 自定义端点新增时，id 在这一刻才生成：`custom-` 前缀是账本用来认出
        // "这不是内置那六家"的依据（见 CallLedger.CUSTOM_PREFIX）。
        String providerId = customNew ? CallLedger.CUSTOM_PREFIX + UUID.randomUUID()
                .toString().substring(0, 8) : requested;

        String key = text(binding.editorKey);
        String url = text(binding.editorUrl);
        if (key.isEmpty()) {
            // 编辑内置家时 key 允许留空 = 不改；自定义端点没有这个语义（它本来就没配过）。
            if (customNew || ProviderKeys.apiKey(requireContext(), providerId) == null) {
                warn(R.string.keys_need_key);
                return;
            }
        }
        if (url.isEmpty()) {
            warn(R.string.keys_need_url);
            return;
        }
        try {
            if (!custom) {
                // key 留空 = "不改 key"，但**地址照样要能改**：
                // 没有这一条的话，用户想从官方端点换到自建反代就得把 key 重输一遍。
                if (key.isEmpty()) {
                    ProviderKeys.updateBaseUrl(requireContext(), providerId, url);
                } else {
                    ProviderKeys.save(requireContext(), providerId, key, url);
                }
            } else {
                String name = text(binding.editorName);
                if (name.isEmpty()) {
                    warn(R.string.keys_need_name);
                    return;
                }
                List<String> models = splitModels(text(binding.editorModels));
                if (models.isEmpty()) {
                    warn(R.string.keys_need_models);
                    return;
                }
                Integer contextLimit = parsePositive(text(binding.editorContext));
                if (contextLimit == null) {
                    warn(R.string.keys_need_context);
                    return;
                }
                // 格式：新增时来自上一步的选择，编辑时保持原样。
                // **不要用 `providerAdapterOf(providerId)` 兜新增那条路**——
                // 新增的 id 是刚生成的，注册表里还没有它，兜底会静默变成 OpenAI 兼容
                // （用户选了 Anthropic 却存成 OpenAI，报错要到发请求时才出现）。
                ProviderSpec.Adapter adapter;
                if (ProviderPickerDialog.CUSTOM_ANTHROPIC.equals(requested)) {
                    adapter = ProviderSpec.Adapter.ANTHROPIC;
                } else if (ProviderPickerDialog.CUSTOM_OPENAI.equals(requested)) {
                    adapter = ProviderSpec.Adapter.OPENAI_COMPATIBLE;
                } else {
                    adapter = providerAdapterOf(providerId);
                }
                ProviderKeys.saveCustom(requireContext(), providerId, name, url, key, adapter,
                        models, contextLimit, binding.editorStreamUsage.isChecked());
            }
        } catch (Exception failed) {
            // 地址不合法之类：把原因照实说出来，而不是一句"保存失败"。
            Toast.makeText(requireContext(),
                    getString(R.string.keys_save_failed, String.valueOf(failed.getMessage())),
                    Toast.LENGTH_LONG).show();
            return;
        }
        RepositoryProvider.reloadProviders();
        Bundle result = new Bundle();
        result.putString(BUNDLE_PROVIDER, providerId);
        getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
        Toast.makeText(requireContext(), R.string.keys_saved, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    /** 编辑已有自定义端点时保持它原来的格式（格式不在这一页改）。 */
    private ProviderSpec.Adapter providerAdapterOf(String providerId) {
        ProviderSpec spec = RepositoryProvider.providers().provider(providerId);
        return spec == null ? ProviderSpec.Adapter.OPENAI_COMPATIBLE : spec.adapter;
    }

    private static void toggle(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static String text(android.widget.EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    /** 模型清单：逗号或换行分隔，去掉空白项。 */
    static List<String> splitModels(String raw) {
        List<String> models = new ArrayList<>();
        if (raw == null) {
            return models;
        }
        for (String part : Arrays.asList(raw.split("[,\\n]"))) {
            String clean = part.trim();
            if (!clean.isEmpty() && !models.contains(clean)) {
                models.add(clean);
            }
        }
        return models;
    }

    /** 正整数字符串 → 数字；不是正数就返回 null（由调用方提示"必填"）。 */
    static Integer parsePositive(String raw) {
        // **不用 TextUtils**：这个方法是纯 Java 的、要被单测直接调，
        // 而 android.text.TextUtils 在 JVM 单测里是"没实现"的（调用会抛 RuntimeException）。
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private void warn(int stringRes) {
        Toast.makeText(requireContext(), stringRes, Toast.LENGTH_SHORT).show();
    }
}
