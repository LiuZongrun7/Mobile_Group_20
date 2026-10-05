package com.mobilegroup20.modelpilot.ui.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.databinding.ItemMemoryBinding;
import com.mobilegroup20.modelpilot.databinding.SheetMemoryBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「这条对话的记忆」：**看、改、丢**（大纲 §4 "inspect/correct the retained memory"）。
 *
 * <p>为什么必须有这一屏：压缩把更早的轮变成一段摘要之后，那段摘要此后每一轮都要带上。
 * 摘要要是写错了一个事实（把"7 pm"记成"8 pm"），它会**一直错下去**，
 * 而在此之前用户连看都看不到——这正是大纲把 "reviewable memory" 列为核心卖点、
 * 而不是把它当内部实现的原因。
 *
 * <p>两个动作的语义说清楚（界面上也写了）：
 * <ul>
 *   <li><b>改</b>：改的是这段摘要本身，下一次发送就用新的（引擎每次发送前从库里重读，
 *       所以不存在"改了没生效"）；</li>
 *   <li><b>丢</b>：**不是删历史**。原消息一条没少，丢掉的只是"用摘要代替它们"这件事，
 *       于是那一段重新进上下文——更准确，但更费 token。</li>
 * </ul>
 */
public final class MemorySheetFragment extends BottomSheetDialogFragment {

    private static final String ARG_CHAT = "chat_id";

    public static void open(FragmentManager fm, String chatId) {
        Bundle args = new Bundle();
        args.putString(ARG_CHAT, chatId);
        MemorySheetFragment sheet = new MemorySheetFragment();
        sheet.setArguments(args);
        sheet.show(fm, "memory_sheet");
    }

    private SheetMemoryBinding binding;
    private ChatConversationViewModel model;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        binding = SheetMemoryBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        String chatId = requireArguments().getString(ARG_CHAT);
        // 和对话页**共用同一个 ViewModel**（同一个 FragmentManager 作用域）：
        // 它们看的是同一份记忆，各拿一份的话改完这边那边不刷新。
        model = new ViewModelProvider(requireParentFragment(),
                new ChatConversationViewModel.Factory(chatId, requireContext()))
                .get(ChatConversationViewModel.class);
        model.memories().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(List<MemoryEntity> memories) {
        if (binding == null) {
            return;
        }
        binding.memoryContainer.removeAllViews();
        binding.memoryEmpty.setVisibility(memories.isEmpty() ? View.VISIBLE : View.GONE);
        ProviderRegistry registry = RepositoryProvider.providers();
        for (MemoryEntity memory : memories) {
            ItemMemoryBinding row = ItemMemoryBinding.inflate(getLayoutInflater(),
                    binding.memoryContainer, false);
            row.memoryMeta.setText(getString(R.string.memory_meta, coveredCount(memory),
                    modelName(registry, memory), timestamp(memory.createdAtEpochMillis)));
            row.memorySummary.setText(memory.summary);
            row.memoryEdited.setVisibility(memory.editedByUser ? View.VISIBLE : View.GONE);
            row.memoryEdit.setOnClickListener(v -> edit(memory));
            row.memoryDrop.setOnClickListener(v -> confirmDrop(memory));
            binding.memoryContainer.addView(row.getRoot());
        }
    }

    /**
     * 这段摘要覆盖了**几条消息**。
     *
     * <p>库里存的是首尾两条消息的 id（压缩边界），**不是条数**。为了界面上一个数字
     * 多开一次查询不值得，而且真查出来的话，用户删过消息之后这个数字会和他看到的
     * 历史对不上。所以这里只说"覆盖了一段更早的对话"，**不编一个条数**——
     * 准确的范围要靠源链接（下一步），不是靠猜一个数。
     */
    private String coveredCount(MemoryEntity memory) {
        return getString(R.string.memory_covers_earlier);
    }

    private String modelName(ProviderRegistry registry, MemoryEntity memory) {
        ModelSpec spec = registry.model(memory.madeByProvider, memory.madeByModel);
        if (spec != null) {
            return spec.displayName;
        }
        return memory.madeByModel == null || memory.madeByModel.isEmpty()
                ? getString(R.string.memory_meta_unknown_model) : memory.madeByModel;
    }

    private String timestamp(long at) {
        return new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(new Date(at));
    }

    private void edit(MemoryEntity memory) {
        EditText input = new EditText(requireContext());
        input.setText(memory.summary);
        input.setMinLines(4);
        input.setGravity(android.view.Gravity.TOP);
        // 光标放末尾：用户多半是改一句话，不是重写整段。
        input.setSelection(input.getText().length());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.memory_edit_title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.keys_save, (dialog, which) -> {
                    model.editMemory(memory.id, input.getText().toString());
                })
                .show();
    }

    private void confirmDrop(MemoryEntity memory) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.memory_drop_title)
                .setMessage(R.string.memory_drop_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.memory_drop, (dialog, which) ->
                        model.dropMemory(memory.id))
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
