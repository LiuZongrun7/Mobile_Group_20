package com.mobilegroup20.modelpilot.ui.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.CanonicalMessage;
import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.TaskKind;
import com.mobilegroup20.modelpilot.chat.UsageRecorder;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.databinding.FragmentConversationBinding;
import com.mobilegroup20.modelpilot.databinding.ItemMessageAssistantBinding;
import com.mobilegroup20.modelpilot.databinding.ItemMessageUserBinding;
import com.mobilegroup20.modelpilot.databinding.ItemRouteLineBinding;

import java.util.Collections;
import java.util.List;

/**
 * 对话页（设计稿 `conversation.png`）：消息 + 每个回答前面那行路由说明 + 输入条。
 *
 * <p>它只负责**画**和**把用户的动作转给 {@link ChatConversationViewModel}**：
 * 发送链路（压缩、选型、流式、落库、记账）全在 ViewModel 里，因为转屏之后
 * 正在跑的那次调用必须还活着——放在 Fragment 里的话，转屏会把回答连同钱一起丢掉。
 *
 * <p>三处照设计稿来的取舍：
 *
 * <ol>
 *   <li><b>用户消息有气泡，模型的回答没有。</b>回答经常是多段长文，套气泡会让每段
 *       再缩进一层，而且窄气泡会让代码块频繁折行。</li>
 *   <li><b>每个回答上面都有一行 `Auto → <模型>`</b>，哪怕连续两句是同一个模型。
 *       因为它同时也是"这句话是谁答的"的证据——用户在一条对话里换过模型之后，
 *       没有这行就分不清哪句是谁说的。</li>
 *   <li><b>回答下面没有"复制/重新生成"这排按钮</b>（设计稿里图片那轮有 Save/Edit，
 *       那是图片特有的）。文字回答先不做：多一排按钮会把"读完这句话"的视线切断，
 *       而这两个动作长按选中文本就能做。</li>
 * </ol>
 */
public final class ChatConversationFragment extends Fragment {

    private static final String ARG_CHAT_ID = "chat_id";
    /** 从首页带过来的那句话：非空 = 打开之后立刻发出去（"首页按一次发送"的语义）。 */
    private static final String ARG_PENDING_TEXT = "pending_text";
    private static final String ARG_PROVIDER = "pending_provider";
    private static final String ARG_MODEL = "pending_model";

    /** 打开某条对话（从列表点进来）。 */
    public static ChatConversationFragment open(String chatId) {
        return open(chatId, null, null, null);
    }

    /**
     * 打开某条对话。
     *
     * @param pendingText 还没发出去的那句话；非空时打开后**立刻发送**。
     *                    从首页发消息走的就是这一条——一次点击 = 一次请求，
     *                    而不是"先建一条对话、再让用户按一次发送"。
     * @param providerId  首页那颗胶囊上手动选的模型（null = Auto）；不带上它的话，
     *                    用户在首页选完模型会发现"选了没用"。
     */
    public static ChatConversationFragment open(String chatId, String pendingText,
                                               String providerId, String modelId) {
        ChatConversationFragment fragment = new ChatConversationFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CHAT_ID, chatId);
        args.putString(ARG_PENDING_TEXT, pendingText);
        args.putString(ARG_PROVIDER, providerId);
        args.putString(ARG_MODEL, modelId);
        fragment.setArguments(args);
        return fragment;
    }

    private FragmentConversationBinding binding;
    private ChatConversationViewModel model;
    private ProviderRegistry registry;
    /** 发送时用的手动选择；null = Auto。**存在 Fragment 里而不是 ViewModel 里**：
     *  它是一次界面上的临时选择，ViewModel 只关心"发的时候用哪个"。 */
    private String manualProviderId;
    private String manualModelId;
    /** 待发送的附件（这一版最多一个：一次选一张图）。 */
    private com.mobilegroup20.modelpilot.chat.CanonicalMessage.Attachment pendingAttachment;
    /** 附件大小上限。超了直接说不收——不静默截断，也不让一张原图把对话拖垮。 */
    private static final long MAX_ATTACHMENT_BYTES = 4L * 1024 * 1024;
    /** 正在流式回来的那一行。**只重渲染它**，不重建整条对话（见 renderMessages）。 */
    private ItemMessageAssistantBinding streamingRow;
    /** 流式重渲染的节流（毫秒）：Markdown + 公式排版不便宜，见 renderSendState。 */
    private static final long STREAM_RENDER_INTERVAL_MS = 120;
    private long lastStreamRenderAt;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        binding = FragmentConversationBinding.inflate(inflater, parent, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        String chatId = requireArguments().getString(ARG_CHAT_ID);
        model = new ViewModelProvider(this, new ChatConversationViewModel.Factory(chatId,
                requireContext())).get(ChatConversationViewModel.class);
        registry = com.mobilegroup20.modelpilot.data.RepositoryProvider.providers();

        binding.conversationBack.setOnClickListener(v -> goBack());
        binding.conversationMore.setOnClickListener(v -> showMore());
        binding.conversationAttach.setOnClickListener(v -> pickAttachment());
        binding.conversationAttachmentChip.setOnClickListener(v -> clearAttachment());
        binding.conversationModelChip.setOnClickListener(v -> openModelSheet());
        binding.conversationSend.setOnClickListener(v -> onSendClicked());
        // 监听只注册一次（转屏后仍然在）：注册在点击里的话，每次点开弹层都会再注册一遍。
        // 改名复用"项目名对话框"（同样是"问一个名字"），结果落到这条对话的标题上。
        getChildFragmentManager().setFragmentResultListener(ProjectNameDialog.RESULT_KEY,
                getViewLifecycleOwner(), (key, result) -> model.rename(
                        result.getString(ProjectNameDialog.BUNDLE_NAME, "")));
        getChildFragmentManager().setFragmentResultListener(ModelSheetFragment.RESULT_KEY,
                getViewLifecycleOwner(), (key, result) -> {
                    manualProviderId = result.getString(ModelSheetFragment.BUNDLE_PROVIDER);
                    manualModelId = result.getString(ModelSheetFragment.BUNDLE_MODEL);
                    renderChip();
                });

        // 草稿恢复（大纲 §4-1）：转屏由 Android 自己保，这里管的是"进程被杀掉之后"。
        binding.conversationInput.setText(
                com.mobilegroup20.modelpilot.data.Drafts.get(requireContext(), chatId));

        // 从首页带过来的那句话：**只在第一次创建视图时发**（`saved == null`）。
        // 少了这个判断，转屏会把它再发一遍——那就是白花一次钱。
        // 已经在发的那一轮由 ViewModel 撑着（它跨转屏活着），不需要重发。
        String pendingText = requireArguments().getString(ARG_PENDING_TEXT);
        if (saved == null && pendingText != null && !pendingText.trim().isEmpty()) {
            manualProviderId = requireArguments().getString(ARG_PROVIDER);
            manualModelId = requireArguments().getString(ARG_MODEL);
            renderChip();
            model.send(pendingText, manualProviderId, manualModelId);
        }
        model.chat().observe(getViewLifecycleOwner(), this::renderHeader);
        model.projectName().observe(getViewLifecycleOwner(), this::renderProject);
        renderAttachmentChip();
        model.messages().observe(getViewLifecycleOwner(), messages -> {
            // 库里的消息变了（新消息落库）：这时候必须整块重建——
            // 正在流式回来的那一行由 renderSendState 自己维护，见它的注释。
            ChatConversationViewModel.SendState state = model.sendState().getValue();
            renderMessages(messages, state != null && state.sending,
                    state == null ? "" : state.streaming);
        });
        model.sendState().observe(getViewLifecycleOwner(), this::renderSendState);
    }

    /**
     * 离开页面时把没发出去的字存下来（`onPause` 而不是 `onDestroyView`：
     * 进程随时可能在这一页还活着的时候被杀，onPause 是最后一个稳的时机）。
     */
    @Override
    public void onPause() {
        super.onPause();
        if (binding != null) {
            com.mobilegroup20.modelpilot.data.Drafts.save(requireContext(),
                    requireArguments().getString(ARG_CHAT_ID),
                    binding.conversationInput.getText().toString());
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        streamingRow = null;
        binding = null;
    }

    // ---- 顶栏 ----------------------------------------------------------

    private void renderHeader(ChatEntity chat) {
        if (binding == null || chat == null) {
            return;
        }
        binding.conversationTitle.setText(chat.title == null || chat.title.isEmpty()
                ? getString(R.string.chat_untitled) : chat.title);
    }

    /** 项目名单独观察（它来自另一张表）；没有项目时整行 GONE，不留一行空白。 */
    private void renderProject(String name) {
        if (binding == null) {
            return;
        }
        boolean has = name != null && !name.isEmpty();
        binding.conversationProject.setVisibility(has ? View.VISIBLE : View.GONE);
        binding.conversationProject.setText(has ? name : null);
    }

    // ---- 消息 ----------------------------------------------------------

    /**
     * 重画全部消息。
     *
     * <p>**每次数据变化整块重建**，不做差量更新：这里的行数是一条对话的消息数
     * （几十到几百），而差量更新的 bug（漏了一行、顺序错位）在界面上很难看出来。
     * 等实测卡了再换成 RecyclerView + DiffUtil。
     */
    private void renderMessages(List<MessageEntity> messages, boolean sending, String streaming) {
        if (binding == null) {
            return;
        }
        binding.conversationMessages.removeAllViews();
        streamingRow = null;
        LayoutInflater inflater = getLayoutInflater();
        for (MessageEntity message : messages) {
            if (isAssistant(message)) {
                // 回答上面那行路由说明：**记的是这条消息自己是谁答的**
                // （换过模型的对话里，每句话的来源都不一样）。
                addRouteLine(inflater, message.providerId, message.modelId, message.route, null);
                addAssistantText(inflater, message.text);
            } else {
                addUserBubble(inflater, message.text,
                        com.mobilegroup20.modelpilot.chat.AttachmentCodec
                                .fromJson(message.attachmentsJson));
            }
        }
        if (sending) {
            // 这一行**一开始就建好（哪怕还是空的）**：之后每来一个字只改它的文字。
            // 不这么做的话，每个 delta 都要把整条对话重建一遍——几十条消息的对话里
            // 那就是每个字都重新 inflate 上百个 View，界面会卡成幻灯片。
            streamingRow = ItemMessageAssistantBinding.inflate(inflater,
                    binding.conversationMessages, false);
            Markdown.render(streamingRow.messageText, streaming == null ? "" : streaming);
            lastStreamRenderAt = android.os.SystemClock.uptimeMillis();
            binding.conversationMessages.addView(streamingRow.getRoot());
        }
        int count = binding.conversationMessages.getChildCount();
        binding.conversationEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        // **总是跟到底部**：数据只在"刚发出一条"或"回答正在逐字回来"时变，
        // 那两种情况下用户就是在等最后一行。不做"用户翻历史时不打扰"的判断，
        // 因为那需要监听滚动位置并区分"程序滚动"和"手指滚动"，而现在的行数还不需要。
        scrollToTail();
    }

    /**
     * 这条消息是"模型说的"吗。
     *
     * <p>用 {@code CanonicalMessage.Role} 的常量比字符串，而不是在实体上再定义一套枚举：
     * 库里存的就是那个枚举的名字，两处各定一套迟早会漂。
     */
    private static boolean isAssistant(MessageEntity message) {
        return CanonicalMessage.Role.ASSISTANT.name().equals(message.role)
                || CanonicalMessage.Role.TOOL.name().equals(message.role);
    }

    private void addUserBubble(LayoutInflater inflater, String text,
                               java.util.List<com.mobilegroup20.modelpilot.chat.CanonicalMessage.Attachment> attachments) {
        ItemMessageUserBinding row = ItemMessageUserBinding.inflate(inflater,
                binding.conversationMessages, false);
        StringBuilder body = new StringBuilder();
        for (com.mobilegroup20.modelpilot.chat.CanonicalMessage.Attachment attachment : attachments) {
            // 这一版附件在气泡里只显示一行说明（名字 + 大小）。**缩略图还没做**——
            // 那要在气泡里放 ImageView、还要处理 data URL 的解码与缓存，是下一步。
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(getString(R.string.chat_attach_line, attachment.fileName,
                    attachment.bytes / 1024));
        }
        if (text != null && !text.isEmpty()) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(text);
        }
        row.messageText.setText(body.toString());
        binding.conversationMessages.addView(row.getRoot());
    }

    private void addAssistantText(LayoutInflater inflater, String text) {
        ItemMessageAssistantBinding row = ItemMessageAssistantBinding.inflate(inflater,
                binding.conversationMessages, false);
        // **模型的回答渲染 Markdown + 公式**（用户自己打的那句不渲染，理由见 Markdown 的注释）。
        Markdown.render(row.messageText, text);
        binding.conversationMessages.addView(row.getRoot());
    }

    /**
     * 那行 `✳ Auto → <模型>  Details`。
     *
     * <p>`route` 为 null（导入的、或者字段没写的旧数据）时**不写"Auto"也不写"Manual"**：
     * 那等于替一条我们不知道来源的消息表态。这时只显示模型名。
     */
    private void addRouteLine(LayoutInflater inflater, String providerId, String modelId,
                              String route, String reason) {
        ItemRouteLineBinding row = ItemRouteLineBinding.inflate(inflater,
                binding.conversationMessages, false);
        String name = modelName(providerId, modelId);
        boolean auto = route == null || UsageRecorder.Route.AUTO.name().equals(route);
        row.routeText.setText(auto
                ? getString(R.string.chat_route_auto, name)
                : getString(R.string.chat_route_manual, name));
        row.routeDetails.setOnClickListener(v -> showRouteDetails(reason));
        binding.conversationMessages.addView(row.getRoot());
    }

    private void showRouteDetails(String reason) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.chat_route_details_title)
                // reason 是 AutoRouter 给的那句话。手动选的、或这次还没算过原因时，
                // 老实说"这是你自己选的"，而不是编一段理由。
                .setMessage(reason == null || reason.isEmpty()
                        ? getString(R.string.chat_route_manual_explain) : reason)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String modelName(String providerId, String modelId) {
        if (modelId == null) {
            return getString(R.string.chat_untitled);
        }
        ModelSpec spec = registry.model(providerId, modelId);
        return spec == null ? modelId : spec.displayName;
    }

    // ---- 发送 ----------------------------------------------------------

    // ---- 附件 ----------------------------------------------------------

    /**
     * 选一个文件（系统文件选择器；`OpenDocument` 走 SAF，**不用申请存储权限**——
     * 用户选哪个我们才拿得到哪个，这也是大纲 §5 说的 "native file pickers"）。
     *
     * <p>**这一版只收图片**：图片在两个渲染器里都是现成的形态（OpenAI 用 data URL、
     * Anthropic 用 base64），不加新依赖就能真的发出去。PDF 要在本机抽文字
     * （PDFBox-Android + 页数/字数上限），是下一步；现在选了非图片会明确说"只收图片"，
     * 而不是假装收下再发一个空附件。
     */
    private void pickAttachment() {
        attachmentPicker.launch(new String[] {"image/*"});
    }

    private final androidx.activity.result.ActivityResultLauncher<String[]> attachmentPicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts
                    // **OpenDocument 的入参是 String[]、回调却是单个 Uri**
                    // （多选要用 OpenMultipleDocuments）——写错了编译期就会报。
                    .OpenDocument(), uri -> {
                if (uri == null) {
                    return;                     // 用户取消了
                }
                attachImage(uri);
            });

    /** 把选中的图片读成 data URL（渲染器要的就是这个形态，见 Attachment 的注释）。 */
    private void attachImage(android.net.Uri uri) {
        try {
            android.content.ContentResolver resolver = requireContext().getContentResolver();
            String mime = resolver.getType(uri);
            if (mime == null || !mime.startsWith("image/")) {
                toast(getString(R.string.chat_attach_only_images));
                return;
            }
            byte[] bytes;
            try (java.io.InputStream in = resolver.openInputStream(uri)) {
                bytes = in == null ? null : readAll(in);
            }
            if (bytes == null || bytes.length == 0) {
                toast(getString(R.string.chat_attach_unreadable));
                return;
            }
            if (bytes.length > MAX_ATTACHMENT_BYTES) {
                // **有上限**：data URL 会把字节变大约三分之一，整段还要进库、进请求。
                // 超了直接说不收——不静默截断，也不让一张原图把这条对话拖垮。
                toast(getString(R.string.chat_attach_too_large,
                        MAX_ATTACHMENT_BYTES / (1024 * 1024)));
                return;
            }
            String dataUrl = "data:" + mime + ";base64,"
                    + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            pendingAttachment = new com.mobilegroup20.modelpilot.chat.CanonicalMessage.Attachment(
                    com.mobilegroup20.modelpilot.chat.CanonicalMessage.Attachment.Kind.IMAGE,
                    fileName(uri, mime), dataUrl, bytes.length, null);
            renderAttachmentChip();
        } catch (Exception failed) {
            android.util.Log.e("ModelPilot", "读取附件失败", failed);
            toast(getString(R.string.chat_attach_unreadable));
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 显示名：优先问系统要（"IMG_2026.png"），拿不到就用 mime 编一个。 */
    private String fileName(android.net.Uri uri, String mime) {
        try (android.database.Cursor cursor = requireContext().getContentResolver().query(
                uri, new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (RuntimeException ignored) {
            // 拿不到名字不影响发送，用兜底的名字即可。
        }
        return "image." + (mime.contains("png") ? "png" : mime.contains("webp") ? "webp" : "jpg");
    }

    private void renderAttachmentChip() {
        if (binding == null) {
            return;
        }
        boolean has = pendingAttachment != null;
        binding.conversationAttachmentChip.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) {
            binding.conversationAttachmentChip.setText(getString(R.string.chat_attach_chip,
                    pendingAttachment.fileName, pendingAttachment.bytes / 1024));
        }
    }

    private void clearAttachment() {
        pendingAttachment = null;
        renderAttachmentChip();
    }

    private void toast(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }

    private void onSendClicked() {
        ChatConversationViewModel.SendState state = model.sendState().getValue();
        if (state != null && state.sending) {
            model.cancel();
            return;
        }
        String text = binding.conversationInput.getText().toString().trim();
        if (text.isEmpty() && pendingAttachment == null) {
            return;                        // 空消息不发（各家都会 400）；但"只有附件"是合法的
        }
        binding.conversationInput.setText("");
        // **发出去了就清草稿**：留着的话下次进来框里又出现刚发过的那句话。
        com.mobilegroup20.modelpilot.data.Drafts.clear(requireContext(),
                requireArguments().getString(ARG_CHAT_ID));
        model.send(text, manualProviderId, manualModelId,
                pendingAttachment == null
                        ? java.util.Collections
                                .<com.mobilegroup20.modelpilot.chat.CanonicalMessage.Attachment>emptyList()
                        : java.util.Collections.singletonList(pendingAttachment));
        clearAttachment();
    }

    private void renderSendState(ChatConversationViewModel.SendState state) {
        if (binding == null) {
            return;
        }
        boolean sending = state.sending;
        binding.conversationSend.setImageResource(sending ? R.drawable.ic_stop : R.drawable.ic_send);
        binding.conversationSend.setContentDescription(
                getString(sending ? R.string.chat_stop : R.string.chat_send));

        if (sending && streamingRow != null) {
            // 高频路径：只重渲染那一行（见 renderMessages 里的注释）。
            // **但要节流**：Markdown 解析 + 公式排版是实打实的开销，每来一个字就重排一次
            // 会让长回答的流式肉眼可见地卡；120ms 一次在人眼里仍然是"逐字出来"。
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastStreamRenderAt >= STREAM_RENDER_INTERVAL_MS) {
                lastStreamRenderAt = now;
                Markdown.render(streamingRow.messageText, state.streaming);
                scrollToTail();
            }
        } else {
            List<MessageEntity> messages = model.messages().getValue();
            renderMessages(messages == null ? Collections.<MessageEntity>emptyList() : messages,
                    sending, state.streaming);
        }
        renderNotice(state);
    }

    /**
     * 底部那句提示。三种情况可能同时成立，所以拼成一段：
     * 出错（真错）> 不完整（半句）> 压过上下文（信息）。
     */
    private void renderNotice(ChatConversationViewModel.SendState state) {
        StringBuilder notice = new StringBuilder();
        if (state.error != null && !state.error.isEmpty()) {
            notice.append(state.error);
        }
        if (state.incomplete) {
            append(notice, getString(R.string.chat_incomplete));
        }
        if (state.compressed) {
            append(notice, getString(R.string.chat_compressed));
        }
        binding.conversationNotice.setVisibility(notice.length() == 0 ? View.GONE : View.VISIBLE);
        binding.conversationNotice.setText(notice.toString());
    }

    private static void append(StringBuilder out, String line) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(line);
    }

    private void scrollToTail() {
        binding.conversationScroll.post(() -> {
            if (binding != null) {
                binding.conversationScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    // ---- 其它动作 ------------------------------------------------------

    private void openModelSheet() {
        ModelSheetFragment.show(getChildFragmentManager(), manualProviderId, manualModelId,
                TaskKind.TEXT);
    }

    private void renderChip() {
        if (binding == null) {
            return;
        }
        if (manualModelId == null) {
            binding.conversationModelChipText.setText(R.string.chat_auto);
        } else {
            binding.conversationModelChipText.setText(modelName(manualProviderId, manualModelId));
        }
    }

    /**
     * 对话页的 `⋮`：记忆 / 改名 / 删除。
     *
     * <p>三件事都是"这条对话整体上"的动作，所以放在一起；**每一项都真的能用**——
     * 一个点了没反应的菜单比没有菜单更让人怀疑是不是坏了。
     */
    private void showMore() {
        String[] items = {
                getString(R.string.chat_menu_memory),
                getString(R.string.chat_menu_rename),
                getString(R.string.chat_menu_delete),
        };
        new AlertDialog.Builder(requireContext())
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            MemorySheetFragment.open(getChildFragmentManager(), model.chatId());
                            break;
                        case 1:
                            ProjectNameDialog.show(getChildFragmentManager(),
                                    R.string.chat_rename_title, currentTitle());
                            break;
                        default:
                            confirmDelete();
                            break;
                    }
                })
                .show();
    }

    private String currentTitle() {
        com.mobilegroup20.modelpilot.chat.local.ChatEntity chat = model.chat().getValue();
        return chat == null ? "" : chat.title;
    }

    /**
     * 删对话前先问一次，并**说清楚删掉的是什么**（消息 + 记忆 + 本机调用记录）。
     *
     * <p>这里没有"撤销"：删完就没了，所以文案里要写明不可恢复，而不是只问一句
     * "确定吗"——用户对"确定吗"是没有判断依据的。
     */
    private void confirmDelete() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.chat_delete_title)
                .setMessage(R.string.chat_delete_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.chat_delete_confirm, (dialog, which) -> {
                    model.deleteChat();
                    // 删完必须离开这一页：停在这里会显示一条已经不存在的对话
                    // （消息空了、标题还在），看起来像坏了。
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                })
                .show();
    }

    private void goBack() {
        if (requireActivity() instanceof ChatHomeFragment.Host) {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return;
        }
        requireActivity().getSupportFragmentManager().popBackStack();
    }

    private void pending(String what) {
        Toast.makeText(requireContext(), getString(R.string.nav_not_built, what),
                Toast.LENGTH_SHORT).show();
    }
}
