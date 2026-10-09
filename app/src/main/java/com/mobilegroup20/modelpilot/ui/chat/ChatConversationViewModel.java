package com.mobilegroup20.modelpilot.ui.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.chat.AttachmentCodec;
import com.mobilegroup20.modelpilot.chat.AutoRouter;
import com.mobilegroup20.modelpilot.chat.CallLedger;
import com.mobilegroup20.modelpilot.chat.CanonicalMessage;
import com.mobilegroup20.modelpilot.chat.ContextEngine;
import com.mobilegroup20.modelpilot.chat.EngineTuning;
import com.mobilegroup20.modelpilot.chat.Memory;
import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.chat.RenderedContext;
import com.mobilegroup20.modelpilot.chat.Summarizer;
import com.mobilegroup20.modelpilot.chat.TaskKind;
import com.mobilegroup20.modelpilot.chat.UsageRecorder;
import com.mobilegroup20.modelpilot.chat.local.ChatDao;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.data.ProviderKeys;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.data.remote.ProviderClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;

/**
 * 对话页的大脑：**发送链路全在这里**（拼上下文 → 该压就压 → 选模型 → 渲染 → 流式收 →
 * 落库 → 记账）。
 *
 * <p>六步顺序不能换，每一步都有代价在背后：
 *
 * <ol>
 *   <li><b>先落库用户这条消息，再发。</b>反过来的话，"发出去了但界面没记下"
 *       会让人重发一遍——那是多花一次钱。</li>
 *   <li><b>整条对话读进 {@link ContextEngine}</b>（消息 + 记忆）。引擎要全量消息：
 *       压缩的边界是消息 id，不是分页游标。</li>
 *   <li><b>该压就压</b>（木桶效应，{@link ContextEngine#needsCompression()}）。
 *       压一次产出一条 provider 无关的 {@link Memory}，六份上下文一起被改写——
 *       所以这件事只花一次钱。</li>
 *   <li><b>选模型</b>：手动选的照发（Auto 不许替换），Auto 走 {@link AutoRouter}。</li>
 *   <li><b>渲染并发送</b>：缓存里那份 payload 原样发（发送时零转换）。</li>
 *   <li><b>收尾</b>：回答落库、`usage` 到了才记账。**两者都可能不来**——
 *       断了就说断了（{@link SendState#incomplete}），不假装成功。</li>
 * </ol>
 *
 * <p>线程：{@link ProviderClient} 的回调在 OkHttp 的调度线程上，所以写库都在
 * {@link #io} 里做、界面更新都 post 回主线程。这个类里没有"当前显示到第几个字"
 * 这类界面状态——那是 {@link SendState} 的活。
 */
public final class ChatConversationViewModel extends ViewModel {

    /** 一轮发送的进行状态。界面照着它画"正在思考"、路由那一行和错误提示。 */
    public static final class SendState {

        /**
         * 这一轮走到哪一步了。**它存在的理由就是"用户点了发送之后不能什么都不显示"**：
         * 从按下发送到第一个字回来，中间要经过落库、装上下文、**压缩（一次真实的模型调用！）**、
         * 选模型、建连接、等首字，慢的时候十几秒。这期间屏幕上如果什么都没有，
         * 用户只能猜"发出去了吗 / 是不是卡了"——2026-10-05 用户原话就是这么说的。
         */
        public enum Stage {
            /** 什么都没在跑。 */
            IDLE,
            /** 落库、装上下文、挑模型（还没发请求）。 */
            CONTEXT,
            /** 正在把更早的内容压成记忆（**这是一次会花钱的模型调用**，要单独说）。 */
            COMPRESSING,
            /** 请求已经发出去了，等模型吐第一个字。 */
            WAITING,
            /** 正在逐字回来。 */
            STREAMING,
            /** 这一轮结束了（正常收尾，界面还要留着路由/压缩那些信息）。 */
            DONE
        }

        public final Stage stage;
        /** 正在逐字回来的回答（没在发时是空串）。 */
        public final String streaming;
        /** 这次是谁答的：Auto 的结果或手动选的。还没定时是 null。 */
        public final String providerId;
        public final String modelId;
        /** Auto 为什么挑它。手动选的没有这句话（那是用户自己决定的）。 */
        public final String routeReason;
        public final UsageRecorder.Route route;
        /** 出错时的一句话（已本地化）；正常时 null。 */
        public final String error;
        /** 这次回答**不完整**（流断了）。界面要标出来。 */
        public final boolean incomplete;
        /** 这一轮压过上下文：界面要说明"更早的内容已收进记忆"。 */
        public final boolean compressed;
        /**
         * 这一轮**按下发送**的时刻（`SystemClock.uptimeMillis()`），界面用它显示"等了 Ns"。
         *
         * <p>放在状态里而不是 Fragment 的字段里，是为了**转屏之后秒数接着走**：
         * Fragment 会重建，而 ViewModel 活着；秒数从头数的话，转了屏就变成"刚发出去"，
         * 而那正是用户在盯着看的东西。
         */
        public final long startedAtUptimeMillis;

        SendState(Stage stage, String streaming, String providerId, String modelId,
                  String routeReason, UsageRecorder.Route route, String error,
                  boolean incomplete, boolean compressed, long startedAtUptimeMillis) {
            this.startedAtUptimeMillis = startedAtUptimeMillis;
            this.stage = stage;
            this.streaming = streaming;
            this.providerId = providerId;
            this.modelId = modelId;
            this.routeReason = routeReason;
            this.route = route;
            this.error = error;
            this.incomplete = incomplete;
            this.compressed = compressed;
        }

        /** 有请求在路上：发送键该显示成"停止"，界面该显示"正在……"。 */
        public boolean sending() {
            return stage != Stage.IDLE && stage != Stage.DONE;
        }

        /** 第一个字还没回来（在准备、在压缩、或在等）——这三种都要画思考动画。 */
        public boolean thinking() {
            return sending() && streaming.isEmpty();
        }

        static SendState idle() {
            return new SendState(Stage.IDLE, "", null, null, null, null, null, false, false, 0L);
        }

        /** 刚按下发送：先把"在准备"这件事显示出来（**这时候一个请求都还没发**）。 */
        static SendState preparing() {
            return new SendState(Stage.CONTEXT, "", null, null, null, null, null, false, false,
                    android.os.SystemClock.uptimeMillis());
        }

        /** 界面上能不能画那一行 `Auto → <模型>`。 */
        public boolean routed() {
            return providerId != null && modelId != null;
        }

        SendState at(Stage next) {
            return new SendState(next, streaming, providerId, modelId, routeReason, route, error,
                    incomplete, compressed, startedAtUptimeMillis);
        }

        SendState streaming(String text) {
            // 已经有字回来了：阶段跟着变成 STREAMING（**别的字段一个都不能丢**，
            // 尤其是 compressed —— 那句话在回答到达那一刻最容易丢，见 settled()）。
            return new SendState(Stage.STREAMING, text, providerId, modelId, routeReason, route,
                    error, incomplete, compressed, startedAtUptimeMillis);
        }

        /**
         * 这一轮结束了（成功），**但把"这轮发生过什么"留着**：压过上下文、
         * 这次是谁答的、Auto 为什么挑它。
         *
         * <p>2026-10-04 真机上发现的：原来收尾时直接换成 {@link #idle()}，
         * 于是"更早的内容已收进记忆"那句提示在回答到达的同一瞬间消失——
         * 用户根本读不到，而这件事（历史被压缩了）恰恰是他最该知道的。
         */
        SendState settled() {
            return new SendState(Stage.DONE, "", providerId, modelId, routeReason, route, error,
                    incomplete, compressed, startedAtUptimeMillis);
        }

        SendState failed(String message) {
            return new SendState(Stage.DONE, streaming, providerId, modelId, routeReason, route,
                    message, !streaming.isEmpty(), compressed, startedAtUptimeMillis);
        }
    }

    private final String chatId;
    private final Context context;
    private final ChatDao dao;
    private final ProviderRegistry registry;
    private final CallLedger ledger;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 上游为这一次回答报的真实用量（onUsage 填、saveAnswer 读；都在 io 线程上，天然有序）。 */
    private volatile long lastInputTokens;
    private volatile long lastOutputTokens;

    /** 正在跑的那次流式调用；用户离开页面时取消它（取消之后不再有回调，也不花钱）。 */
    private final AtomicReference<Call> inflight = new AtomicReference<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicReference<com.mobilegroup20.modelpilot.chat.StreamSnapshotBuffer> activeDisplay =
            new AtomicReference<>();

    private final MutableLiveData<SendState> send = new MutableLiveData<>(SendState.idle());
    /** 散聊用：一条值恒为 null 的 LiveData（`switchMap` 不能返回 null）。 */
    private final MutableLiveData<String> noProject = new MutableLiveData<>(null);
    private final LiveData<ChatEntity> chat;
    private final LiveData<List<MessageEntity>> messages;
    private final LiveData<List<MemoryEntity>> chooserMemories;

    public ChatConversationViewModel(String chatId, Context context) {
        this.chatId = chatId;
        this.context = context.getApplicationContext();
        this.dao = RepositoryProvider.chats();
        this.registry = RepositoryProvider.providers();
        this.ledger = RepositoryProvider.ledger();
        this.chat = dao.chatLive(chatId);
        this.messages = dao.messagesLive(chatId);
        this.chooserMemories = dao.memoriesLive(chatId);
    }

    public LiveData<ChatEntity> chat() {
        return chat;
    }

    /**
     * 对话页顶栏那行项目名。
     *
     * <p>用 `switchMap` 而不是"观察 chat、拿到 id 再手动去查一次"：手动查要自己管理
     * 上一个查询的观察者（不注销就是泄漏，注销早了就是空白），而这里的变化源头只有一个。
     * 没有项目（散聊）时给一条永远为 null 的 LiveData，界面据此把整行收起来。
     */
    public LiveData<String> projectName() {
        return androidx.lifecycle.Transformations.switchMap(chat, entity -> {
            String projectId = entity == null ? null : entity.projectId;
            if (projectId == null || projectId.isEmpty()) {
                return noProject;
            }
            return dao.projectName(projectId);
        });
    }

    public LiveData<List<MessageEntity>> messages() {
        return messages;
    }

    public LiveData<SendState> sendState() {
        return send;
    }

    /**
     * 这条对话的记忆（压缩产物），按时间升序。
     *
     * <p>大纲 §4 要的 "inspect/correct the retained memory"：用户得能看见
     * "更早的那些轮被压成了什么"，并且能改——摘要写错了（比如把 7pm 记成 8pm）
     * 会一直影响后面每一轮，而他现在连看都看不到。
     */
    public LiveData<List<MemoryEntity>> memories() {
        return chooserMemories;
    }

    /** 用户改了摘要。**下一次发送会重新从库里读**，所以缓存自然失效（见 loadEngine）。 */
    public void editMemory(String memoryId, String text) {
        io.execute(() -> dao.editMemory(memoryId, text == null ? "" : text.trim()));
    }

    /**
     * 删掉一条记忆。
     *
     * <p>删掉之后那一段历史**重新回到上下文里**（`activeMessages()` 不再排除它们）——
     * 这就是大纲说的 "reset"：用户觉得摘要丢了他的东西时，宁可多花 token 也要拿回原文。
     */
    public void dropMemory(String memoryId) {
        io.execute(() -> dao.deleteMemory(memoryId));
    }

    /** 改标题（对话页 `⋮ → Rename`）。 */
    public void rename(String title) {
        String clean = title == null ? "" : title.trim();
        io.execute(() -> dao.renameChat(chatId, clean, System.currentTimeMillis()));
    }

    /**
     * 删掉这条对话（消息与记忆一起删）。
     *
     * <p>调用方负责在删完之后离开这一页：删完还停在这里的话，界面会开始显示
     * 一条已经不存在的对话（消息列表变空、标题还在），看起来像坏了。
     */
    public void deleteChat() {
        io.execute(() -> {
            dao.deleteMessages(chatId);
            dao.deleteMemories(chatId);
            dao.deleteChat(chatId);
        });
    }

    /** Non-blocking chooser snapshot; authoritative checks still run on IO before each request. */
    public TaskKind currentTask(TaskKind pending) {
        java.util.List<CanonicalMessage> snapshot = new java.util.ArrayList<>();
        List<MessageEntity> values = messages.getValue();
        if (values != null) for (MessageEntity value : values) snapshot.add(toCanonical(value));
        java.util.List<Memory> retainedMemories = new java.util.ArrayList<>();
        List<MemoryEntity> saved = chooserMemories.getValue();
        if (saved != null) for (MemoryEntity value : saved) retainedMemories.add(new Memory(
                value.id, value.chatId, value.fromMessageId, value.toMessageId, value.summary,
                value.madeByProvider, value.madeByModel, value.createdAtEpochMillis,
                value.tokensIn, value.tokensOut));
        TaskKind history = com.mobilegroup20.modelpilot.chat.TaskRequirements.forMessages(
                com.mobilegroup20.modelpilot.chat.TaskRequirements.retained(snapshot, retainedMemories));
        return pending == TaskKind.IMAGE || history == TaskKind.IMAGE ? TaskKind.IMAGE
                : pending == TaskKind.PDF || history == TaskKind.PDF ? TaskKind.PDF : TaskKind.TEXT;
    }

    public String chatId() {
        return chatId;
    }

    /** 用户点了发送。`manual*` 都为 null 表示 Auto。 */
    public void send(String text, @Nullable String manualProviderId, @Nullable String manualModelId) {
        send(text, manualProviderId, manualModelId, java.util.Collections.emptyList());
    }

    /**
     * 用户点了发送（带附件）。
     *
     * <p>**只有附件、没有文字**也算一条合法消息（"这张图里是什么"完全可以不打字），
     * 所以判空要看"文字和附件都空"。
     */
    public void send(String text, @Nullable String manualProviderId, @Nullable String manualModelId,
                     List<CanonicalMessage.Attachment> attachments) {
        String body = text == null ? "" : text.trim();
        List<CanonicalMessage.Attachment> files = attachments == null
                ? java.util.Collections.<CanonicalMessage.Attachment>emptyList() : attachments;
        if (body.isEmpty() && files.isEmpty()) {
            return;                     // 空消息各家都会 400，在本地就拦住（不用花钱）
        }
        if (!busy.compareAndSet(false, true)) {
            return;                     // 已经在发：不排队（排队会变成两条并发的回答）
        }
        // **先把"在准备"发出去**：这一拍之后才是落库、装上下文、压缩、选模型，
        // 慢的时候能有好几秒。以前这里发的是 idle（什么都不显示），
        // 于是用户按下发送后屏幕上一点反应都没有——他没法判断"到底收到没有"。
        send.setValue(SendState.preparing());
        io.execute(() -> run(body, manualProviderId, manualModelId, files));
    }

    /**
     * 用户按了停止 / 离开了这一页。
     *
     * <p>取消之后 OkHttp 一个回调都不会再来（`ProviderClient` 的类注释），
     * 所以**已经收到的字不会落库**——半句回答存进去，下次打开会以为模型就答了这么多。
     */
    public void cancel() {
        com.mobilegroup20.modelpilot.chat.StreamSnapshotBuffer display = activeDisplay.getAndSet(null);
        if (display != null) display.discard();
        Call call = inflight.getAndSet(null);
        if (call != null) {
            call.cancel();
        }
        busy.set(false);
        main.post(() -> send.setValue(SendState.idle()));
    }

    // ---- 发送链路 ------------------------------------------------------

    private void run(String body, @Nullable String manualProviderId, @Nullable String manualModelId,
                     List<CanonicalMessage.Attachment> attachments) {
        long startedAt = System.currentTimeMillis();
        // 界面显示"等了 Ns"用的时刻（从按下发送算起，见 SendState.startedAtUptimeMillis）。
        long sentAtMillis = android.os.SystemClock.uptimeMillis();
        // **这一轮提问的任务号**：回答、压缩（将来还有工具）都挂在它下面。
        // 大纲要的"任务级成本"就是这个 id 的聚合——没有它，账本只能回答
        // "这个月花了多少"，回答不了"把这份 PDF 总结完花了多少"。
        String taskId = "task:" + UUID.randomUUID().toString().substring(0, 12);
        try {
            // 1) 先落库。
            MessageEntity userMessage = new MessageEntity();
            userMessage.id = UUID.randomUUID().toString();
            userMessage.chatId = chatId;
            userMessage.role = CanonicalMessage.Role.USER.name();
            userMessage.text = body;
            // 附件与文字一起落库：落的是**数据 URL（图片）或抽出来的正文（PDF）**，
            // 于是"重开这条对话再发一条"时，附件仍然能进上下文（见 AttachmentCodec）。
            userMessage.attachmentsJson = AttachmentCodec.toJson(attachments);
            userMessage.createdAtEpochMillis = startedAt;
            dao.upsertMessage(userMessage);

            // 2) 整条对话进引擎。
            ContextEngine engine = loadEngine();

            // 3) 木桶效应。压一次（如果压成了）会**单独记一行账**，和回答共用 taskId。
            // Do not charge for compression before rejecting an incompatible manual model.
            if (manualModelId != null) {
                ModelSpec selected = registry.model(manualProviderId, manualModelId);
                if (selected != null && !selected.supports(engine.requiredTask())) {
                    fail(R.string.chat_error_task_unsupported);
                    return;
                }
            }
            boolean compressed = compressIfNeeded(engine, taskId);

            // 4) 选模型。
            String providerId;
            String modelId;
            String reason;
            UsageRecorder.Route route;
            if (manualModelId != null) {
                providerId = manualProviderId;
                modelId = manualModelId;
                route = UsageRecorder.Route.MANUAL;
                reason = null;
            } else {
                AutoRouter.Decision decision =
                        new AutoRouter(registry).choose(enabledProviders(),
                                new AutoRouter.Request(engine.requiredTask(), engine.currentTokens(),
                                        1024, ContextEngine.RESERVE_FOR_OUTPUT,
                                        com.mobilegroup20.modelpilot.data.RoutingPreferences.preference(context),
                                        com.mobilegroup20.modelpilot.data.RoutingPreferences.preferredProvider(context)),
                                new AutoRouter.ContextFit() {
                                    @Override public long inputTokens(ModelSpec candidate) {
                                        return engine.render(candidate.providerId, candidate.modelId).estimatedTokens;
                                    }
                                    @Override public long usableTokens(ModelSpec candidate) {
                                        return engine.usableTokens(candidate.contextLimit);
                                    }
                                });
                if (!decision.available()) {
                    fail(R.string.chat_error_no_route);
                    return;
                }
                providerId = decision.providerId;
                modelId = decision.modelId;
                route = UsageRecorder.Route.AUTO;
                reason = decision.reason + (decision.estimatedCostMicros == null ? ""
                        : " 本次参考费用估算：" + com.mobilegroup20.modelpilot.data.Currency.format(
                                context, decision.estimatedCostMicros) + "。");
            }
            ProviderSpec provider = registry.provider(providerId);
            ModelSpec model = registry.model(providerId, modelId);
            if (provider == null || model == null) {
                fail(R.string.chat_error_unknown_model);
                return;
            }
            String apiKey = ProviderKeys.apiKey(context, providerId);
            if (apiKey == null || apiKey.trim().isEmpty()) {
                fail(R.string.chat_error_no_key);
                return;
            }

            // 5) 渲染。**allowTruncate = false**：宁可在这里报"装不下"，
            //    也不偷偷丢掉最老的几轮（`truncatedFromId` 那条规矩）。
            RenderedContext rendered;
            try {
                rendered = engine.renderFitting(providerId, modelId, false);
                // renderFitting(false) returns the full payload even when too large; check explicitly.
                if (rendered.estimatedTokens > engine.usableTokens(model.contextLimit)) {
                    fail(R.string.chat_error_context);
                    return;
                }
            } catch (RuntimeException unusable) {
                fail(R.string.chat_error_context);
                return;
            }
            // 请求马上要发出去了：阶段从"准备"变成"等首字"（思考动画照旧，只是文案变了）。
            publish(new SendState(SendState.Stage.WAITING, "", providerId, modelId, reason, route,
                    null, false, compressed, sentAtMillis));

            String assistantId = UUID.randomUUID().toString();
            com.mobilegroup20.modelpilot.chat.StreamSnapshotBuffer answer =
                    new com.mobilegroup20.modelpilot.chat.StreamSnapshotBuffer();
            activeDisplay.set(answer);
            Call call = ProviderClient.stream(provider, model, apiKey, rendered,
                    new ProviderClient.Listener() {
                        @Override public void onDelta(String text) {
                            if (answer.append(text)) main.postDelayed(() -> {
                                String snapshot = answer.poll();
                                if (snapshot != null && activeDisplay.get() == answer) {
                                    withCurrent(providerId, modelId, reason, route,
                                            compressed, state -> state.streaming(snapshot));
                                }
                            }, com.mobilegroup20.modelpilot.chat.StreamSnapshotBuffer.UI_INTERVAL_MS);
                        }

                        @Override public void onUsage(
                                com.mobilegroup20.modelpilot.contract.model.TokenBundle tokens) {
                            // usage 先到、onDone 后到，而两者都排在同一个 io 线程上，
                            // 所以这里的赋值一定发生在 saveAnswer 读它之前。
                            lastInputTokens = tokens.input + tokens.cacheRead + tokens.cacheWrite;
                            lastOutputTokens = tokens.output;
                            // 记账要写库，所以在 io 线程上做。金额算不出来时那一行留 null
                            // （界面显示"价格未知"），**不填 0**。
                            // 带上 reason：大纲 §6 要的是"route + policy version + reason"都留下，
                            // 只在界面上闪一下的理由，重启之后没人能回答"当时为什么挑它"。
                            io.execute(() -> ledger.record(providerId, modelId, route, chatId,
                                    taskId, UsageRecorder.Kind.ANSWER, tokens, startedAt, reason));
                        }

                        @Override public void onDone() {
                            String finalText = answer.finish();
                            if (finalText == null) return;
                            io.execute(() -> saveAnswer(assistantId, finalText, providerId,
                                    modelId, route, taskId));
                            main.post(() -> {
                                if (!activeDisplay.compareAndSet(answer, null)) return;
                                SendState value = send.getValue();
                                // settled() 而不是 idle()：留住 compressed / 路由信息，
                                // 否则压缩提示在回答到达的瞬间就没了（见 settled 的注释）。
                                send.setValue(value == null ? SendState.idle() : value.settled());
                                // 这一轮到此结束，下一句可以发了。放在这里而不是 finally：
                                // finally 在"请求刚发出去"时就会走到（stream 是异步的）。
                                busy.set(false);
                            });
                        }

                        @Override public void onError(Throwable failure) {
                            // **已经吐出来的字不撤回**（用户看到的字是真的），但要落库并标成不完整。
                            String partial = answer.finish();
                            if (partial == null) return;
                            io.execute(() -> saveAnswer(assistantId, partial, providerId,
                                    modelId, route, taskId));
                            main.post(() -> {
                                if (!activeDisplay.compareAndSet(answer, null)) return;
                                String message = message(failure);
                                withCurrent(providerId, modelId, reason, route, compressed,
                                        state -> state.streaming(partial).failed(message));
                                busy.set(false);
                            });
                        }
                    });
            inflight.set(call);
        } catch (RuntimeException unexpected) {
            // 同步步骤（渲染、参数校验）出的意外也要变成一句人话，
            // 否则界面会永远停在"正在输入"，而那看起来像网络卡住了。
            // **同时必须落日志**：上面那句人话是给用户的，而"到底哪一行炸了"只有日志能回答。
            // 这是真机上用血换的：没有这行日志时，一个后台线程 setValue 的
            // IllegalStateException 表现成"发送失败"，查了半小时。
            android.util.Log.e("ModelPilot", "send failed before the request went out", unexpected);
            fail(R.string.chat_error_send_failed);
        } finally {
            if (inflight.get() == null) {
                // 连请求都没发出去（渲染/校验就失败了）：这一轮已经结束，放开发送键。
                busy.set(false);
            }
        }
    }

    /**
     * 回答落库。
     *
     * <p>**空的回答不落库**（模型一个字都没说，存一条空消息只会让对话里多一块空白），
     * 但 `touchChat` 照做——它记的是"最后一次是谁答的"，这跟回答长短无关。
     */
    private void saveAnswer(String assistantId, String text, String providerId, String modelId,
                            UsageRecorder.Route route, String taskId) {
        MessageEntity answer = null;
        if (!text.isEmpty()) {
            answer = new MessageEntity();
            answer.id = assistantId;
            answer.chatId = chatId;
            answer.role = CanonicalMessage.Role.ASSISTANT.name();
            answer.text = text;
            answer.providerId = providerId;
            answer.modelId = modelId;
            answer.route = route.name();
            // **真实用量回填到这条回答上**（大纲 §6 那条 "message-level token fields"）。
            // 上游没报 usage 时保持 0 = 不知道，**不按估算填**——估算是用来判断阈值的，
            // 拿它冒充账单数字是这个项目一直避免的那类错。
            answer.tokensIn = lastInputTokens;
            answer.tokensOut = lastOutputTokens;
            answer.createdAtEpochMillis = System.currentTimeMillis();

        }
        // 首页那枚 `Last · <模型>` 徽章读的就是这两个字段：**到这一刻才有资格写**
        // （真的答过了），而不是用户选模型的时候。
        dao.completeReply(chatId, answer, providerId, modelId, System.currentTimeMillis());
    }

    /** 读整条对话（记忆 + 消息）并重建引擎。 */
    private ContextEngine loadEngine() {
        // 两个旋钮来自 EngineTuning：release 包恒为默认值，debug 包里可以在
        // 「我的」页临时调小，好让压缩在真机上真的发生一次（见那个类的注释）。
        ContextEngine engine = new ContextEngine(registry, chatId, enabledProviders(),
                EngineTuning.reserveForOutput(context), EngineTuning.compressHeadroom(context));
        com.mobilegroup20.modelpilot.chat.local.ChatHistorySnapshot history = dao.readHistory(chatId);
        if (history.project != null) engine.setProjectInstructions(history.project.instructions);
        for (MemoryEntity memory : history.memories) {
            engine.applyCompression(memory.fromMessageId, memory.toMessageId, memory.summary,
                    memory.madeByProvider, memory.madeByModel, memory.tokensIn, memory.tokensOut,
                    memory.createdAtEpochMillis);
        }
        for (MessageEntity message : history.messages) {
            engine.append(toCanonical(message));
        }
        return engine;
    }

    /**
     * 木桶效应：最短的那块板快到顶就先压一次。返回是否真的压成了。
     *
     * <p><b>用哪个模型压</b>：Auto 在文本任务下挑的那个（= 已配置的模型里最便宜的）。
     * 大纲要的是"用户在填 key 的地方自己选一个压缩模型"，那个设置页还没做；
     * 先用 Auto 的口径，理由是压缩这件事**批量、不挑文采**（几十轮压成一段），
     * 用最便宜的合理。设置页落地后只改这一处（读用户选的那对 provider/model）。
     *
     * <p><b>压不动就不压，也不假装压过</b>：返回 false 之后，发送那一步会照常渲染
     * 完整上下文——上游如果因此报"超长"，用户看到的是一句真实的错误，
     * 而不是"摘要成功了"＋悄悄少了十几轮（那是这个项目最不能留的那种失败）。
     */
    private boolean compressIfNeeded(ContextEngine engine, String taskId) {
        if (!engine.needsCompression()) {
            return false;
        }
        List<CanonicalMessage> segment = engine.compressionSegment();
        if (segment.isEmpty()) {
            return false;
        }
        // 压缩用哪个模型：**用户在设置里选的那个**；没选过才用 Auto 挑的最便宜的。
        // 这条选择是用户明确要求的（"我们以后压上下文就都拿那个去压"）——
        // 压出来的是接下来每一轮都要带上的摘要，让它落到最便宜的模型上是用户的决定，不是我们的。
        String[] chosen = ProviderKeys.compressionModel(context);
        String compressorProvider;
        String compressorModel;
        if (chosen != null) {
            compressorProvider = chosen[0];
            compressorModel = chosen[1];
        } else {
            AutoRouter.Decision decision =
                    new AutoRouter(registry).choose(enabledProviders(), TaskKind.TEXT);
            if (!decision.available()) {
                return false;
            }
            compressorProvider = decision.providerId;
            compressorModel = decision.modelId;
        }
        ProviderSpec provider = registry.provider(compressorProvider);
        ModelSpec model = registry.model(compressorProvider, compressorModel);
        String apiKey = ProviderKeys.apiKey(context, compressorProvider);
        if (provider == null || model == null || apiKey == null || apiKey.trim().isEmpty()) {
            return false;
        }
        // **压缩是一次真实的模型调用**（要花好几秒、还要花钱），所以界面必须说出来。
        // 不说的话，用户看到的是"按了发送之后长时间没反应"，而那正是他抱怨的那件事；
        // 说了之后同一段等待就变成"它在把更早的内容收进记忆"——他反而知道钱花在哪。
        stage(SendState.Stage.COMPRESSING);
        long at = System.currentTimeMillis();
        ProviderClient.Completion done;
        try {
            done = Summarizer.summarize(provider, model, apiKey, segment);
        } catch (ProviderClient.ProviderException failed) {
            // 压缩失败不该让这次发送也失败：照常发（可能因超长被上游拒，那时报的就是真原因）。
            return false;
        }
        String summary = done.text;
        if (summary.isEmpty()) {
            return false;
        }
        // **压缩这一笔也要记账**，而且和回答共用 taskId。
        // 2026-10-04 真机上跑通压缩时才发现的：假上游收到 2 次压缩 + 2 次回答，
        // 而账本只有 2 行——压缩花的钱在账本里根本不存在。
        // 上游没报用量时 `done.tokens` 是 null，`record` 会**不写这一行**
        // （少一行是可见的缺失，写一行 0 是把"不知道"说成"没花钱"）。
        // kind = COMPRESS：Insights 要能把"摘要的钱"和"回答的钱"分开显示，
        // 否则用户会以为回答花了那么多。
        io.execute(() -> ledger.record(compressorProvider, compressorModel,
                UsageRecorder.Route.AUTO, chatId, taskId, UsageRecorder.Kind.COMPRESS,
                done.tokens, at));
        Memory memory = engine.applyCompression(segment.get(0).id,
                segment.get(segment.size() - 1).id, summary, compressorProvider,
                compressorModel, 0, 0, at);
        MemoryEntity entity = new MemoryEntity();
        entity.id = memory.id;
        entity.chatId = memory.chatId;
        entity.fromMessageId = memory.fromMessageId;
        entity.toMessageId = memory.toMessageId;
        entity.summary = memory.summary;
        entity.madeByProvider = memory.madeByProvider;
        entity.madeByModel = memory.madeByModel;
        entity.createdAtEpochMillis = memory.createdAt;
        dao.upsertMemory(entity);
        return true;
    }

    private List<String> enabledProviders() {
        return ProviderKeys.configuredProviders(context);
    }

    private static CanonicalMessage toCanonical(MessageEntity message) {
        // 附件从库里还原（历史消息也带附件——不然"接着问这张图"就断了）。
        return new CanonicalMessage(message.id, role(message.role), message.text,
                AttachmentCodec.fromJson(message.attachmentsJson), null,
                message.toolCallId, message.createdAtEpochMillis);
    }

    private static CanonicalMessage.Role role(String name) {
        try {
            return CanonicalMessage.Role.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            // 角色在库里存的是字符串（枚举顺序变了也不能读错行），所以这里必须兜得住
            // 认不出的值：一条读不懂的旧消息不该让整条对话打不开。
            return CanonicalMessage.Role.USER;
        }
    }

    // ---- 状态发布 ------------------------------------------------------

    /**
     * 发状态。
     *
     * <p><b>必须自己判断线程。</b>`LiveData.setValue` 只能在主线程调，而这条链路里
     * 有不少状态是在 {@link #io} 线程上产生的（发请求前那一拍、以及 onError 里那几处）。
     * 2026-10-04 真机上就是这么踩的：`setValue` 在后台线程抛
     * `IllegalStateException: Cannot invoke setValue on a background thread`，
     * 而它被 {@link #run} 的 catch 兜住，用户看到的是"发送失败"——
     * 一个 HTTP 都还没发的"发送失败"，排查时完全指不到真凶。
     */
    private void publish(SendState state) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            send.setValue(state);
        } else {
            send.postValue(state);
        }
    }

    /**
     * 只换阶段，别的字段一个都不动。
     *
     * <p>用在"压缩"这一拍上：那时候还没选回答用的模型（`providerId` 还是 null），
     * 所以**不能**借用 {@link #withCurrent}（它会顺手把压缩模型写成"这次是谁答的"，
     * 界面上就会出现一行 `Auto → deepseek-chat`，而真正回答的根本不是它）。
     */
    private void stage(SendState.Stage next) {
        main.post(() -> {
            SendState value = send.getValue();
            publish((value == null ? SendState.preparing() : value).at(next));
        });
    }

    /** 在"当前这一轮"的基础上改一处。保留 providerId / route / compressed，避免每处都重传。 */
    private void withCurrent(String providerId, String modelId, String reason,
                             UsageRecorder.Route route, boolean compressed,
                             java.util.function.UnaryOperator<SendState> change) {
        SendState value = send.getValue();
        SendState base = value == null || !value.routed()
                ? new SendState(SendState.Stage.STREAMING,
                        value == null ? "" : value.streaming, providerId, modelId,
                        reason, route, null, false, compressed,
                        value == null ? android.os.SystemClock.uptimeMillis()
                                : value.startedAtUptimeMillis)
                : value;
        publish(change.apply(base));
    }

    private void fail(int stringRes) {
        main.post(() -> {
            publish(new SendState(SendState.Stage.DONE, "", null, null, null, null,
                    context.getString(stringRes), false, false, 0L));
            busy.set(false);
        });
    }

    /**
     * 失败分类 → 一句给用户看的话。
     *
     * <p>**按 `kind` 分叉，不去解析 message**（message 是给日志看的，见 `ProviderException`），
     * 因为三类要用户做的事完全不同：改 key、等一会儿、检查网络。
     */
    private String message(Throwable failure) {
        if (!(failure instanceof ProviderClient.ProviderException)) {
            return context.getString(R.string.chat_error_send_failed);
        }
        ProviderClient.ProviderException provider = (ProviderClient.ProviderException) failure;
        switch (provider.kind) {
            case INVALID_KEY:
                return context.getString(R.string.chat_error_invalid_key);
            case RATE_LIMITED:
                return provider.retryAfterSeconds == null
                        ? context.getString(R.string.chat_error_rate_limited)
                        : context.getString(R.string.chat_error_rate_limited_after,
                                provider.retryAfterSeconds);
            case NETWORK:
                return context.getString(R.string.chat_error_network);
            default:
                return context.getString(R.string.chat_error_http, provider.statusCode);
        }
    }

    @Override
    protected void onCleared() {
        com.mobilegroup20.modelpilot.chat.StreamSnapshotBuffer display = activeDisplay.getAndSet(null);
        if (display != null) display.discard();
        Call call = inflight.getAndSet(null);
        if (call != null) {
            call.cancel();
        }
        io.shutdown();
    }

    /** 给 {@code ViewModelProvider} 用的工厂：对话 id 是参数，不能靠无参构造。 */
    public static final class Factory implements androidx.lifecycle.ViewModelProvider.Factory {
        private final String chatId;
        private final Context context;

        public Factory(String chatId, Context context) {
            this.chatId = chatId;
            this.context = context;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends ViewModel> T create(Class<T> type) {
            if (!type.isAssignableFrom(ChatConversationViewModel.class)) {
                throw new IllegalArgumentException("Unknown ViewModel: " + type);
            }
            return (T) new ChatConversationViewModel(chatId, context);
        }
    }
}
