package com.mobilegroup20.modelpilot.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文引擎：**每个 provider 一份"已经能直接发出去"的上下文**，谁快到上限就压一次。
 *
 * <p>这是新方向的核心（见 `docs/CHAT_ENGINE.md` §2）。四条规矩：
 *
 * 1. **一份事实来源，多份渲染缓存。** 事实是 {@link CanonicalMessage} 列表；
 *    每家一份 {@link RenderedContext}。新消息进来 → 所有缓存失效。
 * 2. **发送时零转换**：直接把缓存里的 payload 发出去，缓存有效就不重渲染。
 * 3. **木桶效应**：阈值取"用户配了 key 的那些模型里最小上限"的 80% 再减预留。
 *    最短那块板快到顶 → 压一次（用一个模型）→ **所有**上下文改写成
 *    "记忆 + 压缩点之后的消息"。压缩只花一次钱。
 * 4. **绝不静默截断**：压不成（比如压缩模型不可用）就退化，但必须留下
 *    {@code truncatedFromId}，界面和回答里都要说明"这之前的已不在上下文里"。
 *
 * <p><b>token 数是估算</b>（`chars / 3.5`，中英混排的粗口径），真实值以各家返回的
 * usage 为准并回填到消息上。用估算是因为**阈值必须在发之前判断**——发完才知道超了
 * 就已经失败了。估算偏差用"预留 10K"这条安全垫吸收。
 */
public final class ContextEngine {

    /** 每 1 个 token 大约几个字符。中英混排的经验值，**只用于发之前的阈值判断**。 */
    private static final double CHARS_PER_TOKEN = 3.5;
    /** 上下文上限只用 80%：留出估算误差与各家算 token 方式的差异。 */
    private static final double SAFETY = 0.8;
    /** 还要给模型的回答留位置——它也是这次请求的 token。 */
    public static final int RESERVE_FOR_OUTPUT = 4_096;
    /** 触发压缩的提前量（大纲口径：还差 10K 就开始压）。 */
    public static final int COMPRESS_HEADROOM = 10_000;
    /** 压缩至少要留下最后这么多轮原文（一问一答算一轮），否则刚压完又超。 */
    private static final int KEEP_RECENT_ROUNDS = 4;

    private final ProviderRegistry registry;
    private final String chatId;
    private final List<CanonicalMessage> messages = new ArrayList<>();
    private final List<Memory> memories = new ArrayList<>();
    /** providerId → 渲染缓存。 */
    private final Map<String, RenderedContext> cache = new LinkedHashMap<>();
    /** 用户配了 key 的 provider。木桶只看这些。 */
    private final List<String> enabledProviders = new ArrayList<>();

    public ContextEngine(ProviderRegistry registry, String chatId, List<String> enabledProviders) {
        this.registry = registry;
        this.chatId = chatId;
        this.enabledProviders.addAll(enabledProviders);
    }

    public String chatId() {
        return chatId;
    }

    public List<CanonicalMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public List<Memory> memories() {
        return Collections.unmodifiableList(memories);
    }

    /** 用户的 key 变了（新增一家、删掉一家）：木桶的板变了，缓存也可能要重算。 */
    public void setEnabledProviders(List<String> providerIds) {
        enabledProviders.clear();
        enabledProviders.addAll(providerIds);
        invalidate();
    }

    /** 追加一条消息。**所有 provider 的渲染缓存一起失效**——它们在下一轮都要重渲染。 */
    public void append(CanonicalMessage message) {
        messages.add(message);
        invalidate();
    }

    /** 回填真实用量（各家返回的 usage）。不回填也不影响渲染，只是阈值判断会更粗。 */
    public void recordUsage(String messageId, long tokensIn, long tokensOut) {
        for (CanonicalMessage message : messages) {
            if (message.id.equals(messageId)) {
                message.tokensIn = tokensIn;
                message.tokensOut = tokensOut;
                invalidate();
                return;
            }
        }
    }

    private void invalidate() {
        cache.clear();
    }

    /**
     * 拿某个 provider 的渲染结果（优先用缓存）。
     *
     * <p>`modelId` 也参与缓存键：同一个 provider 的两个模型上限不同，
     * 渲染内容虽然一样，但"能不能装下"的结论不一样。
     */
    public RenderedContext render(String providerId, String modelId) {
        String key = providerId + "/" + modelId;
        RenderedContext cached = cache.get(key);
        if (cached != null && cached.isValidFor(messages, memories)) {
            return cached;
        }
        ModelSpec model = registry.model(providerId, modelId);
        if (model == null) {
            throw new IllegalArgumentException("unknown model: " + key);
        }
        RenderedContext rendered = renderAll(providerId, modelId, model.contextLimit, false);
        cache.put(key, rendered);
        return rendered;
    }

    /**
     * 按"这个模型装得下"来渲染：装不下就把最老的丢掉（并留下 {@code truncatedFromId}）。
     *
     * <p>**只有压缩失败或用户要求"就这样发"时才走到截断这条支路**——
     * 正常情况下 {@link #needsCompression()} 会先要求压缩。
     */
    public RenderedContext renderFitting(String providerId, String modelId, boolean allowTruncate) {
        ModelSpec model = registry.model(providerId, modelId);
        if (model == null) {
            throw new IllegalArgumentException("unknown model: " + providerId + "/" + modelId);
        }
        int usable = usableTokens(model.contextLimit);
        RenderedContext full = renderAll(providerId, modelId, model.contextLimit, false);
        if (full.estimatedTokens <= usable || !allowTruncate) {
            return full;
        }
        RenderedContext fitted = renderAll(providerId, modelId, model.contextLimit, true);
        cache.put(providerId + "/" + modelId, fitted);
        return fitted;
    }

    private RenderedContext renderAll(String providerId, String modelId, int limit, boolean truncate) {
        ProviderSpec spec = registry.provider(providerId);
        ContextRenderer renderer = spec != null && spec.adapter == ProviderSpec.Adapter.ANTHROPIC
                ? new AnthropicRenderer() : new OpenAiCompatibleRenderer();

        List<CanonicalMessage> usable = activeMessages();
        String truncatedFrom = null;
        ContextRenderer.RenderedPayload payload = renderer.render(usable, memories);
        int tokens = estimate(payload);
        int usableTokens = usableTokens(limit);
        while (truncate && tokens > usableTokens && usable.size() > 1) {
            // 只从**最老的整轮**开始丢：切开一问一答比丢掉整段更容易让模型答错。
            int cut = nextTurnBoundary(usable, 0);
            truncatedFrom = usable.get(0).id;
            usable = new ArrayList<>(usable.subList(cut, usable.size()));
            payload = renderer.render(usable, memories);
            tokens = estimate(payload);
        }
        return new RenderedContext(providerId, modelId, payload, tokens, limit,
                lastId(usable), truncatedFrom, memories.size());
    }

    /**
     * 真正进上下文的消息：**被记忆覆盖的那段要排掉**。
     *
     * <p>这一条踩过：第一版把 memories 和全部 messages 一起渲染，于是压缩之后上下文里
     * 既有摘要、又有原始的那几十轮——token 一点没省，而且模型会看到同一件事说两遍。
     * 压缩的整个意义就是"原消息不再进上下文"（它们仍在规范化日志里，用户翻得到）。
     */
    private List<CanonicalMessage> activeMessages() {
        if (memories.isEmpty()) {
            return messages;
        }
        boolean[] covered = new boolean[messages.size()];
        for (Memory memory : memories) {
            int from = indexOf(memory.fromMessageId);
            int to = indexOf(memory.toMessageId);
            if (from < 0 || to < 0) {
                continue;                       // 记忆指向不存在的消息：忽略，不当成覆盖
            }
            for (int i = from; i <= to && i < covered.length; i++) {
                covered[i] = true;
            }
        }
        List<CanonicalMessage> active = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!covered[i]) {
                active.add(messages.get(i));
            }
        }
        return active;
    }

    private int indexOf(String messageId) {
        if (messageId == null) {
            return -1;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id.equals(messageId)) {
                return i;
            }
        }
        return -1;
    }

    /** 找下一个"轮"的边界（user 消息处），从不含当前位置开始。 */
    private static int nextTurnBoundary(List<CanonicalMessage> list, int from) {
        for (int i = from + 1; i < list.size(); i++) {
            if (list.get(i).role == CanonicalMessage.Role.USER) {
                return i;
            }
        }
        return list.size();
    }

    private static String lastId(List<CanonicalMessage> list) {
        return list.isEmpty() ? null : list.get(list.size() - 1).id;
    }

    /** 渲染后 payload 的 token 估算：把文本长度加起来除以经验值。 */
    static int estimate(ContextRenderer.RenderedPayload payload) {
        int chars = 0;
        if (payload.system != null) {
            chars += payload.system.length();
        }
        chars += countChars(payload.messages);
        return (int) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    @SuppressWarnings("unchecked")
    private static int countChars(Object node) {
        if (node == null) {
            return 0;
        }
        if (node instanceof String) {
            return ((String) node).length();
        }
        if (node instanceof Map) {
            int total = 0;
            for (Object value : ((Map<String, Object>) node).values()) {
                total += countChars(value);
            }
            return total;
        }
        if (node instanceof List) {
            int total = 0;
            for (Object value : (List<Object>) node) {
                total += countChars(value);
            }
            return total;
        }
        return 0;
    }

    /** 一个模型真正能装多少：上限 × 安全系数 − 留给回答的位置。 */
    public int usableTokens(int contextLimit) {
        return (int) (contextLimit * SAFETY) - RESERVE_FOR_OUTPUT;
    }

    /**
     * 木桶效应：**所有已启用模型里最小的那个"能装多少"**，再减掉提前量。
     *
     * <p>为什么用最小值而不是"当前模型"：用户随时可能切到最小的那个家。
     * 按当前模型判断的话，切过去的那一刻就会超——而那正是我们承诺"切换无痛"的场景。
     */
    public int compressionThreshold() {
        int smallest = 0;
        for (String providerId : enabledProviders) {
            ProviderSpec spec = registry.provider(providerId);
            if (spec == null) {
                continue;
            }
            for (ModelSpec model : spec.models) {
                int usable = usableTokens(model.contextLimit);
                if (smallest == 0 || usable < smallest) {
                    smallest = usable;
                }
            }
        }
        return smallest == 0 ? 0 : Math.max(1_000, smallest - COMPRESS_HEADROOM);
    }

    /** 现在该不该压缩。没有启用任何 provider 时返回 false（还没到能发消息的地步）。 */
    public boolean needsCompression() {
        int threshold = compressionThreshold();
        if (threshold == 0) {
            return false;
        }
        return currentTokens() >= threshold;
    }

    /**
     * 当前上下文的估算 token（走 OpenAI 兼容渲染，和实际发送时同一套口径）。
     *
     * <p>**必须和 {@link #renderAll} 用同一份 {@link #activeMessages()}**：不然压缩完
     * 阈值判断还在按"没压之前"算，会一遍遍要求压，而每次压完 token 数都不变。
     */
    public int currentTokens() {
        ContextRenderer.RenderedPayload payload =
                new OpenAiCompatibleRenderer().render(activeMessages(), memories);
        return estimate(payload);
    }

    /**
     * 要压的是哪一段：**保留最后若干轮原文**，其余全压。
     *
     * <p>边界必须落在 user 消息之前——切开一问一答，模型会把问题和答案对错。
     * 返回空列表表示"没有可压的"（例如本来就只有两轮），此时调用方应该老实说
     * "这条对话已经装不下了，建议新开一条"，而不是压出一个空摘要。
     */
    public List<CanonicalMessage> compressionSegment() {
        int keepFrom = messages.size();
        int rounds = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role == CanonicalMessage.Role.USER) {
                rounds++;
                if (rounds > KEEP_RECENT_ROUNDS) {
                    keepFrom = i;
                    break;
                }
            }
        }
        if (keepFrom <= 0 || keepFrom >= messages.size()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(messages.subList(0, keepFrom)));
    }

    /**
     * 压缩完成：把这一段换成一条记忆。
     *
     * <p>原消息**不删**（它们还在规范化日志里，用户能翻回去看），只是不再进上下文——
     * "上下文"和"历史"是两件事，混起来就会出现"用户看到的和模型看到的不是一回事"。
     */
    public void applyCompression(String fromMessageId, String toMessageId, String summary,
                                 String madeByProvider, String madeByModel,
                                 long tokensIn, long tokensOut, long now) {
        Memory memory = new Memory("mem-" + chatId + "-" + memories.size(), chatId,
                fromMessageId, toMessageId, summary, madeByProvider, madeByModel, now,
                tokensIn, tokensOut);
        memories.add(memory);
        invalidate();
    }

    /** 用户改了某条记忆：缓存失效，下一轮按新的发。 */
    public void editMemory(String memoryId, String text) {
        for (Memory memory : memories) {
            if (memory.id.equals(memoryId)) {
                memory.edit(text);
                invalidate();
                return;
            }
        }
    }

    /** 用户删掉一条记忆：那段历史重新回到上下文里（下一轮渲染就会带上）。 */
    public void dropMemory(String memoryId) {
        for (int i = 0; i < memories.size(); i++) {
            if (memories.get(i).id.equals(memoryId)) {
                memories.remove(i);
                invalidate();
                return;
            }
        }
    }
}
