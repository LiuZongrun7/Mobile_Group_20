package com.mobilegroup20.modelpilot.ui.settings;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.data.ProviderKeys;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.databinding.DialogApiKeysBinding;
import com.mobilegroup20.modelpilot.databinding.ItemProviderKeyBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 「API keys」设置页：**用户自己填 key 与请求地址**（`docs/CHAT_ENGINE.md` §4）。
 *
 * <p>三条这一页必须做到的事：
 *
 * <ol>
 *   <li><b>不显示已存的 key。</b>key 只写不读——输入框永远空白，配过的那几家
 *       用"Clear"按钮的存在来表示"这里已经有一把 key"。把 key 显示回屏幕上
 *       （哪怕打码）多一次泄露的机会，而它的用处仅仅是满足好奇心。</li>
 *   <li><b>留空 = 不改，删要用 Clear。</b>因为不预填，所以"空着"必须是"不动它"；
 *       想把某一家去掉是一个显式动作。</li>
 *   <li><b>地址填错当场拦住。</b>{@link ProviderKeys#save} 会校验 http(s)，
 *       这里的错误提示用的是它抛出来的原因，而不是等用户在聊天页里收到一句网络错误。</li>
 * </ol>
 *
 * <p>顺带放了**压缩模型**的选择：它是同一页上的同一个心智（"我在这儿配我能用的模型"），
 * 而它决定"更早的内容被压成什么"。没选过就是 Auto 挑最便宜的（见
 * `ChatConversationViewModel.compressIfNeeded`）。
 */
public final class ApiKeysDialog extends DialogFragment {

    public static void show(FragmentManager fm) {
        if (fm.findFragmentByTag("api_keys") == null) {
            new ApiKeysDialog().show(fm, "api_keys");
        }
    }

    /** 每一行对应的 providerId，按注册表顺序；保存时按同一顺序取回。 */
    private final List<String> rowProviders = new ArrayList<>();
    /** 对话框自己的 binding。**留着它只为一件事**：选完压缩模型后刷新那一行文字
     *  （`setItems` 的回调发生在对话框还开着的时候）。 */
    private DialogApiKeysBinding binding;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle saved) {
        binding = DialogApiKeysBinding.inflate(LayoutInflater.from(requireContext()));
        ProviderRegistry registry = RepositoryProvider.providers();
        List<String> configured = ProviderKeys.configuredProviders(requireContext());

        rowProviders.clear();
        binding.keysContainer.removeAllViews();
        for (ProviderSpec spec : registry.providers()) {
            rowProviders.add(spec.providerId);
            binding.keysContainer.addView(row(binding.keysContainer, spec,
                    configured.contains(spec.providerId)));
        }
        renderCompression(binding);
        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.keys_title)
                .setView(binding.getRoot())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.keys_save, (d, which) -> saveAll(binding))
                .create();
    }

    private View row(LinearLayout parent, ProviderSpec spec, boolean configured) {
        ItemProviderKeyBinding row = ItemProviderKeyBinding.inflate(getLayoutInflater(), parent,
                false);
        row.providerName.setText(spec.displayName);
        // **地址预填默认值**（那不是秘密，而且用户要改的通常只是域名那一段）；
        // key 永远不预填（见类注释第 1 条）。
        row.providerUrl.setText(configured
                ? ProviderKeys.baseUrl(requireContext(), spec.providerId) : spec.baseUrl);
        row.providerClear.setVisibility(configured ? View.VISIBLE : View.GONE);
        row.providerClear.setOnClickListener(v -> {
            ProviderKeys.clear(requireContext(), spec.providerId);
            // 删完立刻见效（下一次 providers() 重新算），并把这一行收起来。
            RepositoryProvider.reloadProviders();
            row.providerClear.setVisibility(View.GONE);
            row.providerKey.setText("");
            Toast.makeText(requireContext(), R.string.keys_saved, Toast.LENGTH_SHORT).show();
        });
        return row.getRoot();
    }

    /**
     * 保存所有行。
     *
     * <p>**一行出错不影响其它行**：六家里配错一家的地址不该让另外五家也白填一遍。
     * 出错的那句提示带上原因（`keys_save_failed`），成功的不单独提示——
     * 对话框关掉本身就是"存好了"的信号，逐行弹六个 toast 只会盖住后面的内容。
     */
    private void saveAll(DialogApiKeysBinding binding) {
        StringBuilder problems = new StringBuilder();
        for (int i = 0; i < binding.keysContainer.getChildCount() && i < rowProviders.size(); i++) {
            String providerId = rowProviders.get(i);
            ItemProviderKeyBinding row =
                    ItemProviderKeyBinding.bind(binding.keysContainer.getChildAt(i));
            String key = row.providerKey.getText() == null ? ""
                    : row.providerKey.getText().toString().trim();
            if (key.isEmpty()) {
                continue;                   // 留空 = 不改这一家（见类注释第 2 条）
            }
            String url = row.providerUrl.getText() == null ? ""
                    : row.providerUrl.getText().toString().trim();
            try {
                ProviderKeys.save(requireContext(), providerId, key, url);
            } catch (Exception failed) {
                if (problems.length() > 0) {
                    problems.append('\n');
                }
                problems.append(providerId).append(": ").append(reason(failed));
            }
        }
        RepositoryProvider.reloadProviders();
        if (problems.length() > 0) {
            // 出错时**不关对话框**：关掉的话用户填的东西全没了，还得重填一遍。
            Toast.makeText(requireContext(),
                    getString(R.string.keys_save_failed, problems), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(requireContext(), R.string.keys_saved, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    /** 把异常翻成一句人话：地址不合法是我们自己抛的，其它一律照它自己的说法。 */
    private String reason(Exception failed) {
        String message = failed.getMessage();
        if (message != null && message.contains("http")) {
            return getString(R.string.keys_bad_url);
        }
        return message == null ? failed.getClass().getSimpleName() : message;
    }

    // ---- 压缩模型 ------------------------------------------------------

    private void renderCompression(DialogApiKeysBinding binding) {
        binding.keysCompressionValue.setText(compressionLabel());
        binding.keysCompressionValue.setOnClickListener(v -> pickCompression());
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
     * <p>候选 = **配了 key 的**那几家的**全部**模型（不限能力）：
     * 压缩是纯文本任务，但用户可能就想用某个只支持文本的便宜模型，
     * 这里替他筛掉反而是多管闲事。
     */
    private void pickCompression() {
        ProviderRegistry registry = RepositoryProvider.providers();
        List<String> configured = ProviderKeys.configuredProviders(requireContext());
        final List<String[]> choices = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.keys_compression_auto));
        choices.add(null);
        for (ProviderSpec spec : registry.providers()) {
            if (!configured.contains(spec.providerId)) {
                continue;
            }
            for (ModelSpec model : spec.models) {
                labels.add(spec.displayName + " · " + model.displayName);
                choices.add(new String[] {spec.providerId, model.modelId});
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.keys_compression_pick)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String[] pick = choices.get(which);
                    ProviderKeys.saveCompressionModel(requireContext(),
                            pick == null ? null : pick[0], pick == null ? null : pick[1]);
                    if (binding != null) {
                        renderCompression(binding);
                    }
                })
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // DialoagFragment 的视图会随对话框销毁；留着这个引用就是泄漏一个已经没用的视图树。
        binding = null;
    }
}
