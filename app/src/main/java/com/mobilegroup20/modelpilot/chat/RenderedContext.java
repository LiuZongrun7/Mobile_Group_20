package com.mobilegroup20.modelpilot.chat;

import java.util.List;

/**
 * 某个 provider / 模型的"可直接发送"形态 + 它的缓存凭据。
 *
 * <p><b>缓存的判据是"渲染到哪一条为止 + 有几条记忆"</b>，不是时间戳：消息或记忆一变，
 * 凭据就对不上，下次自动重渲染。用时间戳做判据的话，"改了内容但没动条数"
 * （比如用户编辑了一条记忆的摘要）会命中旧缓存——那种错看起来就是"改了没生效"。
 */
public final class RenderedContext {

    public final String providerId;
    public final String modelId;
    public final ContextRenderer.RenderedPayload payload;
    /** 渲染后的估算 token（发之前判断用）。 */
    public final int estimatedTokens;
    /** 这个模型的上下文上限。 */
    public final int limitTokens;
    /** 渲染到哪一条消息为止。 */
    public final String renderedThroughMessageId;
    /** 非 null = 这条之前的内容**不在**这次上下文里（压缩失败后的降级截断）。 */
    public final String truncatedFromId;
    /** 渲染时带上了几条记忆（记忆一变，这个数就对不上）。 */
    private final int memoryCount;
    private final int messageCount;

    RenderedContext(String providerId, String modelId, ContextRenderer.RenderedPayload payload,
                    int estimatedTokens, int limitTokens, String renderedThroughMessageId,
                    String truncatedFromId, int memoryCount) {
        this(providerId, modelId, payload, estimatedTokens, limitTokens, renderedThroughMessageId,
                truncatedFromId, memoryCount, -1);
    }

    RenderedContext(String providerId, String modelId, ContextRenderer.RenderedPayload payload,
                    int estimatedTokens, int limitTokens, String renderedThroughMessageId,
                    String truncatedFromId, int memoryCount, int messageCount) {
        this.providerId = providerId;
        this.modelId = modelId;
        this.payload = payload;
        this.estimatedTokens = estimatedTokens;
        this.limitTokens = limitTokens;
        this.renderedThroughMessageId = renderedThroughMessageId;
        this.truncatedFromId = truncatedFromId;
        this.memoryCount = memoryCount;
        this.messageCount = messageCount;
    }

    boolean isValidFor(List<CanonicalMessage> messages, List<Memory> memories) {
        if (memories.size() != memoryCount) {
            return false;
        }
        if (messages.isEmpty()) {
            return renderedThroughMessageId == null;
        }
        return messages.get(messages.size() - 1).id.equals(renderedThroughMessageId);
    }

    /** 装得下吗（还要给回答留位置）。 */
    public boolean fits() {
        return estimatedTokens <= new ContextEngine(ProviderRegistry.defaults(), "",
                java.util.Collections.<String>emptyList()).usableTokens(limitTokens);
    }
}
