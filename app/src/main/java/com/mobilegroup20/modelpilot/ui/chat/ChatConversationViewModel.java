package com.mobilegroup20.modelpilot.ui.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.mobilegroup20.modelpilot.R;
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

    /** 一轮发送的进行状态。界面照着它画"正在输入"、路由那一行和错误提示。 */
    public static final class SendState {
        public final boolean sending;
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

        SendState(boolean sending, String streaming, String providerId, String modelId,
                  String routeReason, UsageRecorder.Route route, String error,
                  boolean incomplete, boolean compressed) {
            this.sending = sending;
            this.streaming = streaming;
            this.providerId = providerId;
            this.modelId = modelId;
            this.routeReason = routeReason;
            this.route = route;
            this.error = error;
            this.incomplete = incomplete;
            this.compressed = compressed;
        }

        static SendState idle() {
            return new SendState(false, "", null, null, null, null, null, false, false);
        }

        /** 界面上能不能画那一行 `Auto → <模型>`。 */
        public boolean routed() {
            return providerId != null && modelId != null;
        }

        SendState streaming(String text) {
            return new SendState(true, text, providerId, modelId, routeReason, route, error,
                    incomplete, compressed);
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
            return new SendState(false, "", providerId, modelId, routeReason, route, error,
                    incomplete, compressed);
        }

        SendState failed(String message) {
            return new SendState(false, streaming, providerId, modelId, routeReason, route, message,
                    !streaming.isEmpty(), compressed);
        }
    }

    private final String chatId;
    private final Context context;
    private final ChatDao dao;
    private final ProviderRegistry registry;
    private final CallLedger ledger;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 正在跑的那次流式调用；用户离开页面时取消它（取消之后不再有回调，也不花钱）。 */
    private final AtomicReference<Call> inflight = new AtomicReference<>();
    private final AtomicBoolean busy = new AtomicBoolean();

    private final MutableLiveData<SendState> send = new MutableLiveData<>(SendState.idle());
    /** 散聊用：一条值恒为 null 的 LiveData（`switchMap` 不能返回 null）。 */
    private final MutableLiveData<String> noProject = new MutableLiveData<>(null);
    private final LiveData<ChatEntity> chat;
    private final LiveData<List<MessageEntity>> messages;

    public ChatConversationViewModel(String chatId, Context context) {
        this.chatId = chatId;
        this.context = context.getApplicationContext();
        this.dao = RepositoryProvider.chats();
        this.registry = RepositoryProvider.providers();
        this.ledger = RepositoryProvider.ledger();
        this.chat = dao.chatLive(chatId);
        this.messages = dao.messagesLive(chatId);
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

    public String chatId() {
        return chatId;
    }

    /** 用户点了发送。`manual*` 都为 null 表示 Auto。 */
    public void send(String text, @Nullable String manualProviderId, @Nullable String manualModelId) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            return;                     // 空消息各家都会 400，在本地就拦住（不用花钱）
        }
        if (!busy.compareAndSet(false, true)) {
            return;                     // 已经在发：不排队（排队会变成两条并发的回答）
        }
        send.setValue(SendState.idle().streaming(""));
        io.execute(() -> run(body, manualProviderId, manualModelId));
    }

    /**
     * 用户按了停止 / 离开了这一页。
     *
     * <p>取消之后 OkHttp 一个回调都不会再来（`ProviderClient` 的类注释），
     * 所以**已经收到的字不会落库**——半句回答存进去，下次打开会以为模型就答了这么多。
     */
    public void cancel() {
        Call call = inflight.getAndSet(null);
        if (call != null) {
            call.cancel();
        }
        busy.set(false);
        main.post(() -> send.setValue(SendState.idle()));
    }

    // ---- 发送链路 ------------------------------------------------------

    private void run(String body, @Nullable String manualProviderId, @Nullable String manualModelId) {
        long startedAt = System.currentTimeMillis();
        try {
            // 1) 先落库。
            MessageEntity userMessage = new MessageEntity();
            userMessage.id = UUID.randomUUID().toString();
            userMessage.chatId = chatId;
            userMessage.role = CanonicalMessage.Role.USER.name();
            userMessage.text = body;
            userMessage.createdAtEpochMillis = startedAt;
            dao.upsertMessage(userMessage);

            // 2) 整条对话进引擎。
            ContextEngine engine = loadEngine();

            // 3) 木桶效应。
            boolean compressed = compressIfNeeded(engine);

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
                        new AutoRouter(registry).choose(enabledProviders(), TaskKind.TEXT);
                if (!decision.available()) {
                    fail(R.string.chat_error_no_key);
                    return;
                }
                providerId = decision.providerId;
                modelId = decision.modelId;
                route = UsageRecorder.Route.AUTO;
                reason = decision.reason;
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
            } catch (RuntimeException unusable) {
                fail(R.string.chat_error_context);
                return;
            }
            publish(new SendState(true, "", providerId, modelId, reason, route, null, false,
                    compressed));

            String assistantId = UUID.randomUUID().toString();
            StringBuilder answer = new StringBuilder();
            Call call = ProviderClient.stream(provider, model, apiKey, rendered,
                    new ProviderClient.Listener() {
                        @Override public void onDelta(String text) {
                            answer.append(text);
                            main.post(() -> withCurrent(providerId, modelId, reason, route,
                                    compressed, state -> state.streaming(answer.toString())));
                        }

                        @Override public void onUsage(
                                com.mobilegroup20.modelpilot.contract.model.TokenBundle tokens) {
                            // 记账要写库，所以在 io 线程上做。金额算不出来时那一行留 null
                            // （界面显示"价格未知"），**不填 0**。
                            io.execute(() -> ledger.record(providerId, modelId, route, chatId,
                                    tokens, startedAt));
                        }

                        @Override public void onDone() {
                            io.execute(() -> saveAnswer(assistantId, answer.toString(), providerId,
                                    modelId, route));
                            main.post(() -> {
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
                            io.execute(() -> saveAnswer(assistantId, answer.toString(), providerId,
                                    modelId, route));
                            main.post(() -> {
                                String message = message(failure);
                                withCurrent(providerId, modelId, reason, route, compressed,
                                        state -> state.failed(message));
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
                            UsageRecorder.Route route) {
        if (!text.isEmpty()) {
            MessageEntity answer = new MessageEntity();
            answer.id = assistantId;
            answer.chatId = chatId;
            answer.role = CanonicalMessage.Role.ASSISTANT.name();
            answer.text = text;
            answer.providerId = providerId;
            answer.modelId = modelId;
            answer.route = route.name();
            answer.createdAtEpochMillis = System.currentTimeMillis();
            dao.upsertMessage(answer);
        }
        // 首页那枚 `Last · <模型>` 徽章读的就是这两个字段：**到这一刻才有资格写**
        // （真的答过了），而不是用户选模型的时候。
        dao.touchChat(chatId, providerId, modelId, System.currentTimeMillis());
    }

    /** 读整条对话（记忆 + 消息）并重建引擎。 */
    private ContextEngine loadEngine() {
        // 两个旋钮来自 EngineTuning：release 包恒为默认值，debug 包里可以在
        // 「我的」页临时调小，好让压缩在真机上真的发生一次（见那个类的注释）。
        ContextEngine engine = new ContextEngine(registry, chatId, enabledProviders(),
                EngineTuning.reserveForOutput(context), EngineTuning.compressHeadroom(context));
        for (MemoryEntity memory : dao.memories(chatId)) {
            engine.applyCompression(memory.fromMessageId, memory.toMessageId, memory.summary,
                    memory.madeByProvider, memory.madeByModel, memory.tokensIn, memory.tokensOut,
                    memory.createdAtEpochMillis);
        }
        for (MessageEntity message : dao.messages(chatId)) {
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
    private boolean compressIfNeeded(ContextEngine engine) {
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
        long at = System.currentTimeMillis();
        String summary;
        try {
            summary = Summarizer.summarize(provider, model, apiKey, segment);
        } catch (ProviderClient.ProviderException failed) {
            // 压缩失败不该让这次发送也失败：照常发（可能因超长被上游拒，那时报的就是真原因）。
            return false;
        }
        if (summary.isEmpty()) {
            return false;
        }
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
        return new CanonicalMessage(message.id, role(message.role), message.text, null, null,
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

    /** 在"当前这一轮"的基础上改一处。保留 providerId / route / compressed，避免每处都重传。 */
    private void withCurrent(String providerId, String modelId, String reason,
                             UsageRecorder.Route route, boolean compressed,
                             java.util.function.UnaryOperator<SendState> change) {
        SendState value = send.getValue();
        SendState base = value == null || !value.routed()
                ? new SendState(true, value == null ? "" : value.streaming, providerId, modelId,
                        reason, route, null, false, compressed)
                : value;
        publish(change.apply(base));
    }

    private void fail(int stringRes) {
        main.post(() -> {
            publish(new SendState(false, "", null, null, null, null, context.getString(stringRes),
                    false, false));
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
