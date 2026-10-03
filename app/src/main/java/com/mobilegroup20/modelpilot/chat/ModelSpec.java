package com.mobilegroup20.modelpilot.chat;

/**
 * 一家 provider 里一个模型的能力与上限。
 *
 * <p><b>为什么是数据而不是代码分支</b>：大纲要"至少两个已验证的模型"，而我们打算
 * 接六家。如果每接一家都改一次 {@code if (provider == ...)}，能力表会散落在
 * 渲染、路由、界面三处，加一家就得改三处、还容易漏。这里把"这家这个模型能吃多大
 * 的上下文、支不支持图片/PDF/工具、多少钱"全部收在一处。
 *
 * <p><b>没实测过的价格留 null，不许编。</b> {@code CONTRACTS.md} §4 那条底线在
 * 新账本里继续有效：算不出来就显示「价格未知」，而不是显示 0——
 * 「花了 0 元」和「不知道花了多少」在界面上必须是两句话。
 */
public final class ModelSpec {

    /** provider 的稳定标识（写进数据库和上报里，别用显示名当键）。 */
    public final String providerId;
    public final String modelId;
    /** 界面上给用户看的名字，例如 "DeepSeek V3.2"。 */
    public final String displayName;
    /** 上下文窗口（token）。**这个数是木桶效应里那块板**。 */
    public final int contextLimit;
    public final boolean text;
    public final boolean vision;
    public final boolean pdf;
    public final boolean tools;
    /** 每 1M token 的微美元价；算不出来就是 null（不是 0）。 */
    public final Long inputMicros;
    public final Long cacheReadMicros;
    public final Long cacheWriteMicros;
    public final Long outputMicros;
    /** 价格依据（官方定价页），留痕用；没实测过就是 null。 */
    public final String priceSource;

    public ModelSpec(String providerId, String modelId, String displayName, int contextLimit,
                     boolean text, boolean vision, boolean pdf, boolean tools,
                     Long inputMicros, Long cacheReadMicros, Long cacheWriteMicros,
                     Long outputMicros, String priceSource) {
        this.providerId = providerId;
        this.modelId = modelId;
        this.displayName = displayName;
        this.contextLimit = contextLimit;
        this.text = text;
        this.vision = vision;
        this.pdf = pdf;
        this.tools = tools;
        this.inputMicros = inputMicros;
        this.cacheReadMicros = cacheReadMicros;
        this.cacheWriteMicros = cacheWriteMicros;
        this.outputMicros = outputMicros;
        this.priceSource = priceSource;
    }

    /** 这个模型能不能干这件事。**能力表是 Auto 的唯一判据**，不是猜的。 */
    public boolean supports(TaskKind task) {
        switch (task) {
            case TEXT: return text;
            case IMAGE: return vision;
            case PDF: return pdf;
            case TOOLS: return tools;
            default: return false;
        }
    }

    /**
     * 参考价（每 1M token 的四桶之和的一个粗口径），用于 Auto 挑"最便宜的"。
     *
     * <p>**只用来排序，不用来算钱**：真正算钱在服务端，按生效日期的价目表和四桶
     * 分别乘。这里把四桶简单相加是因为排序只需要一个可比的量级（输入与输出价差
     * 十几倍，但同向），拿它当金额是会错的。算不出价（null）时返回 null，
     * 由调用方决定「未知价算不算便宜」。
     */
    public Long roughPriceMicrosPer1M() {
        if (inputMicros == null || outputMicros == null) {
            return null;
        }
        return inputMicros + outputMicros;
    }

    public boolean priced() {
        return inputMicros != null && outputMicros != null;
    }

    @Override public String toString() {
        return providerId + "/" + modelId;
    }
}
