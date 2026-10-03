package com.mobilegroup20.modelpilot.ui.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.chat.RelativeTime;
import com.mobilegroup20.modelpilot.chat.TaskKind;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.data.AccountSession;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.databinding.FragmentChatHomeBinding;
import com.mobilegroup20.modelpilot.databinding.ItemChatRowBinding;
import com.mobilegroup20.modelpilot.databinding.ItemProjectHeaderBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chat 首页（设计稿 `01-home.png`）。
 *
 * <p>版面与交互都在这个类里完成：分组渲染、折叠、按发送开一条新对话。
 * **这里不做模型选型**：输入条那颗 `Auto` 只是显示当前选择，真正的选择在模型弹层
 * （{@code ModelSheetFragment}），它算的是 {@code ModelChoices}。
 *
 * <p>三处刻意的取舍：
 *
 * <ol>
 *   <li><b>列表用 {@code LinearLayout} 手拼，不用 {@code RecyclerView}。</b>
 *       这一页是"项目分组 + 两种行型 + 折叠"，用 RecyclerView 要写一个多 viewType
 *       的适配器再加一层分组展开逻辑，代码量翻倍；而本机对话的总量是几十到几百条。
 *       等真的卡了再换（那时候数据量和现在不是一个量级）。</li>
 *   <li><b>没有项目的散聊不出现在 `Projects` 里</b>，只在 `Recent chats` 里出现。
 *       给它们编一个"未分组"的假项目会让 `Projects` 那一段不再是用户自己建的东西。</li>
 *   <li><b>折叠状态只活在内存里</b>（转屏保留、重启不保留）。它是一次浏览动作，
 *       不是用户配置——记住了反而会让下次打开时"少了一截"，看起来像丢数据。</li>
 * </ol>
 */
public final class ChatHomeFragment extends Fragment {

    /** 最近对话最多列这么多条；再往前的靠搜索或项目分组去找。 */
    private static final int RECENT_LIMIT = 20;

    private static final String STATE_COLLAPSED = "collapsed_projects";

    private FragmentChatHomeBinding binding;
    private ChatHomeViewModel model;
    /** 折起来的项目 id。默认全展开（刚建的项目要能立刻看见）。 */
    private final Set<String> collapsed = new HashSet<>();
    private ProviderRegistry registry;
    /** 最近一次从 Room 拿到的两份数据。两份都到齐才渲染，见 onViewCreated。 */
    private List<ProjectEntity> projects = Collections.emptyList();
    private List<ChatEntity> chats = Collections.emptyList();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        binding = FragmentChatHomeBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        model = new ViewModelProvider(this).get(ChatHomeViewModel.class);
        registry = RepositoryProvider.providers();
        if (saved != null) {
            ArrayList<String> ids = saved.getStringArrayList(STATE_COLLAPSED);
            if (ids != null) {
                collapsed.addAll(ids);
            }
        }
        renderHeader();
        binding.chatSend.setOnClickListener(v -> send());
        binding.chatSearch.setOnClickListener(v -> pending("Search"));
        binding.chatAvatar.setOnClickListener(v -> openAccount());
        binding.chatAttach.setOnClickListener(v -> pending("Attachments"));
        binding.chatModelChip.setOnClickListener(v -> openModelSheet());
        binding.chatProjectChip.setOnClickListener(v -> pickProject());
        binding.chatNewProject.setOnClickListener(v -> ProjectNameDialog.show(
                getChildFragmentManager(), R.string.chat_new_project, null));

        // 两个对话框/弹层都只发结果、不落库，落库在这里（见两个 Dialog 的类注释）。
        // 监听只注册一次：同一个 key 注册两次会互相覆盖，把回调写在 onViewCreated
        // 里比在点击里注册可靠得多（点击里的那份在转屏后就没了）。
        getChildFragmentManager().setFragmentResultListener(ProjectNameDialog.RESULT_KEY,
                getViewLifecycleOwner(), (key, result) -> {
                    String name = result.getString(ProjectNameDialog.BUNDLE_NAME, "");
                    model.createProject(name, () -> { });
                });
        getChildFragmentManager().setFragmentResultListener(ProjectPickerDialog.RESULT_KEY,
                getViewLifecycleOwner(), (key, result) -> {
                    model.selectProject(result.getString(ProjectPickerDialog.BUNDLE_PROJECT_ID, ""));
                    renderInputChips();
                });
        getChildFragmentManager().setFragmentResultListener(ModelSheetFragment.RESULT_KEY,
                getViewLifecycleOwner(), (key, result) -> {
                    model.selectModel(result.getString(ModelSheetFragment.BUNDLE_PROVIDER),
                            result.getString(ModelSheetFragment.BUNDLE_MODEL));
                    renderInputChips();
                });

        // 两份数据分开观察、合起来渲染：Room 的 LiveData 各自在自己的查询上失效，
        // 绑在一起写反而要处理"一个还没到、另一个到了"的中间态。
        model.projects().observe(getViewLifecycleOwner(), projects -> {
            this.projects = projects;
            renderLists();
        });
        model.chats().observe(getViewLifecycleOwner(), chats -> {
            this.chats = chats;
            renderLists();
        });
        renderInputChips();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArrayList(STATE_COLLAPSED, new ArrayList<>(collapsed));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ---- 头部 / 输入条 --------------------------------------------------

    private void renderHeader() {
        AccountSession session = AccountSession.get(requireContext());
        String name = session.accountName();
        binding.chatAvatar.setText(name == null || name.isEmpty()
                ? "?" : name.substring(0, 1).toUpperCase(Locale.US));
    }

    private void renderInputChips() {
        if (binding == null) {
            return;
        }
        if (model.manualModelId() == null) {
            binding.chatModelChipText.setText(R.string.chat_auto);
        } else {
            ModelSpec spec = registry.model(model.manualProviderId(), model.manualModelId());
            binding.chatModelChipText.setText(spec != null ? spec.displayName : model.manualModelId());
        }
        String projectId = model.projectId();
        if (projectId.isEmpty()) {
            binding.chatProjectChipText.setText(R.string.chat_no_project);
        } else {
            binding.chatProjectChipText.setText(projectName(projectId));
        }
    }

    private String projectName(String projectId) {
        for (ProjectEntity project : projects) {
            if (project.id.equals(projectId)) {
                return project.name == null || project.name.isEmpty()
                        ? getString(R.string.chat_untitled) : project.name;
            }
        }
        return getString(R.string.chat_no_project);
    }

    // ---- 列表渲染 ------------------------------------------------------

    private void renderLists() {
        if (binding == null) {
            return;
        }
        binding.chatProjectsContainer.removeAllViews();
        binding.chatRecentContainer.removeAllViews();

        // 按项目分组（保持 allChats 的"最近活跃在前"顺序，组内自然就是按时间倒序）。
        Map<String, List<ChatEntity>> byProject = new LinkedHashMap<>();
        for (ChatEntity chat : chats) {
            if (chat.projectId == null || chat.projectId.isEmpty()) {
                continue;
            }
            List<ChatEntity> bucket = byProject.get(chat.projectId);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byProject.put(chat.projectId, bucket);
            }
            bucket.add(chat);
        }

        for (ProjectEntity project : projects) {
            List<ChatEntity> kids = byProject.get(project.id);
            addProjectGroup(project, kids == null ? Collections.emptyList() : kids);
        }

        int recentCount = 0;
        for (ChatEntity chat : chats) {
            if (recentCount >= RECENT_LIMIT) {
                break;
            }
            binding.chatRecentContainer.addView(chatRow(chat));
            recentCount++;
        }

        boolean noChats = chats.isEmpty();
        binding.chatEmpty.setVisibility(noChats ? View.VISIBLE : View.GONE);
        // `Recent chats` 那一行标题永远在：它不在的话，一个空页面只剩一句提示，
        // 用户不知道这块区域本来是干什么的。
        binding.chatRecentLabel.setVisibility(View.VISIBLE);
        binding.chatProjectsEmpty.setVisibility(projects.isEmpty() ? View.VISIBLE : View.GONE);
        renderInputChips();
    }

    private void addProjectGroup(ProjectEntity project, List<ChatEntity> kids) {
        ItemProjectHeaderBinding header =
                ItemProjectHeaderBinding.inflate(getLayoutInflater(), binding.chatProjectsContainer,
                        false);
        header.projectName.setText(project.name == null || project.name.isEmpty()
                ? getString(R.string.chat_untitled) : project.name);
        header.projectCount.setText(kids.isEmpty() ? "" : String.valueOf(kids.size()));
        header.projectIcon.setColorFilter(ProjectColors.folder(requireContext(), project.colorIndex));
        header.projectAddChat.setOnClickListener(v -> startChatIn(project.id));
        boolean isCollapsed = collapsed.contains(project.id);
        header.projectChevron.setRotation(isCollapsed ? -90f : 0f);
        header.projectChevron.setContentDescription(getString(
                isCollapsed ? R.string.chat_expand_project : R.string.chat_collapse_project));
        // 整行都能折叠（不只是那个小箭头）：箭头只有 20dp，手指点不准。
        header.getRoot().setOnClickListener(v -> {
            if (collapsed.contains(project.id)) {
                collapsed.remove(project.id);
            } else {
                collapsed.add(project.id);
            }
            renderLists();
        });
        binding.chatProjectsContainer.addView(header.getRoot());

        if (isCollapsed || kids.isEmpty()) {
            return;
        }
        binding.chatProjectsContainer.addView(projectBody(project, kids));
    }

    /**
     * 组内的对话列表：左边一条竖线 + 右边若干行。
     *
     * <p>竖线不是装饰——分组表头和组内每一行的左边缘差了 20dp，
     * 没有这条线时"这一行属于哪个项目"要靠间距去猜。
     */
    private View projectBody(ProjectEntity project, List<ChatEntity> kids) {
        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        body.setLayoutParams(bodyParams);

        View line = new View(requireContext());
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(dp(1.5f),
                ViewGroup.LayoutParams.MATCH_PARENT);
        lineParams.setMarginStart(dp(9f));
        line.setLayoutParams(lineParams);
        line.setBackgroundColor(0xFFE1E7F0);
        body.addView(line);

        LinearLayout rows = new LinearLayout(requireContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rows.setLayoutParams(rowsParams);
        for (ChatEntity chat : kids) {
            rows.addView(chatRow(chat));
        }
        body.addView(rows);
        return body;
    }

    private View chatRow(ChatEntity chat) {
        ItemChatRowBinding row = ItemChatRowBinding.inflate(getLayoutInflater(),
                binding.chatRecentContainer, false);
        row.chatRowTitle.setText(chat.title == null || chat.title.isEmpty()
                ? getString(R.string.chat_untitled) : chat.title);
        row.chatRowTime.setText(relativeTime(chat.updatedAtEpochMillis));
        renderBadge(row, chat);
        row.getRoot().setOnClickListener(v -> openChat(chat.id));
        return row.getRoot();
    }

    /**
     * `Last · <模型>` 徽章。**没答过就整块 GONE**（见 `item_chat_row.xml` 的注释）——
     * 这时用一句话说明它是新对话，而不是留一块空白。
     */
    private void renderBadge(ItemChatRowBinding row, ChatEntity chat) {
        boolean answered = chat.lastModelId != null && !chat.lastModelId.isEmpty();
        if (!answered) {
            row.chatRowBadge.setVisibility(View.GONE);
            row.chatRowSubtitle.setVisibility(View.VISIBLE);
            row.chatRowSubtitle.setText(R.string.chat_no_messages);
            return;
        }
        ProviderPalette.Colors colors = ProviderPalette.of(chat.lastProviderId);
        row.chatRowBadge.setVisibility(View.VISIBLE);
        row.chatRowSubtitle.setVisibility(View.GONE);
        row.chatRowBadge.getBackground().mutate()
                .setTint(colors.badgeBackground);
        row.chatRowBadgeText.setTextColor(colors.badgeText);
        row.chatRowBadgeText.setText(getString(R.string.chat_last_model, modelName(chat)));
        row.chatRowMark.getBackground().mutate().setTint(colors.markBackground);
        row.chatRowMark.setTextColor(colors.markText);
        row.chatRowMark.setText(ProviderPalette.mark(chat.lastProviderId, chat.lastModelId));
    }

    /** 显示名取注册表；注册表里没有（用户自己填的模型名、或以后删过的模型）就显示原始 id。 */
    private String modelName(ChatEntity chat) {
        ModelSpec spec = registry.model(chat.lastProviderId, chat.lastModelId);
        if (spec != null) {
            return spec.displayName;
        }
        ProviderSpec provider = registry.provider(chat.lastProviderId);
        return provider == null ? chat.lastModelId : provider.displayName + " · " + chat.lastModelId;
    }

    private String relativeTime(long at) {
        RelativeTime.Label label =
                RelativeTime.of(at, System.currentTimeMillis(), Locale.getDefault());
        switch (label.unit) {
            case MINUTES:
                return getString(R.string.chat_time_minutes, label.amount);
            case HOURS:
                return getString(R.string.chat_time_hours, label.amount);
            case YESTERDAY:
                return getString(R.string.chat_time_yesterday);
            case NOW:
                return getString(R.string.chat_time_now);
            default:
                return label.text;
        }
    }

    // ---- 动作 ----------------------------------------------------------

    private void send() {
        String text = binding.chatInput.getText().toString().trim();
        if (text.isEmpty()) {
            // 空消息不发：各家都会 400，不如在这里就拦住（而且拦在这里不用花钱）。
            return;
        }
        binding.chatInput.setText("");
        model.startChat(text, model.projectId(), chatId -> openChat(chatId));
    }

    private void startChatIn(String projectId) {
        model.selectProject(projectId);
        // 只把项目选上并把光标放进输入框：用户点项目行上的加号是想"在这里说点什么"，
        // 直接开一条空对话会在列表里留下一条永远没有内容的东西。
        binding.chatInput.requestFocus();
        renderInputChips();
    }

    private void pickProject() {
        ProjectPickerDialog.show(getChildFragmentManager(), projects, model.projectId());
    }

    private void openModelSheet() {
        ModelSheetFragment.show(getChildFragmentManager(), model.manualProviderId(),
                model.manualModelId(), TaskKind.TEXT);
    }
    private void openChat(String chatId) {
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).openChat(chatId);
            return;
        }
        pending("Chat");
    }

    private void openAccount() {
        if (getActivity() instanceof Host) {
            ((Host) getActivity()).openAccount();
            return;
        }
        pending("Account");
    }

    /** 还没做的入口统一走这里：**说清楚是什么没做**，而不是静默不响应。 */
    private void pending(String what) {
        Toast.makeText(requireContext(), getString(R.string.nav_not_built, what),
                Toast.LENGTH_SHORT).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 首页要跳的两处（对话页、账号页）由宿主 Activity 接，Fragment 不自己换页。 */
    public interface Host {
        void openChat(String chatId);

        void openAccount();
    }
}
