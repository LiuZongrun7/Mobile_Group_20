package com.mobilegroup20.modelpilot.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto：**第一版只做"用户配了 key 的模型里，能力满足且最便宜的那个"**。
 *
 * <p>为什么先这么简单：大纲要的 Auto 是"按任务需求、模型能力、预算和偏好选"，
 * 但那需要先有可信的能力表与价格（正在补），也需要真实使用数据来验证选得对不对。
 * 先上一条**能被解释清楚**的规则，比先上一套说不清为什么的黑盒更有用——
 * 设计稿里那个 `Details` 要显示的就是这个 `reason`，它必须是人能读懂、能反驳的一句话。
 *
 * <p>两条不能违反的规矩（大纲 §6 "Explainable Auto"）：
 *
 * 1. **手动选的模型永远不会被悄悄替换**：手动模式下这里根本不参与。
 * 2. **能力不满足就不出现在候选里**，而不是"选它然后失败"——设计稿里那些置灰的
 *    "Unavailable for this task" 就是这条规矩的界面表现。
 */
public final class AutoRouter {

    /** 一次选型的结果：选了谁、为什么、以及被排除的都有谁。 */
    public static final class Decision {
        public final String providerId;
        public final String modelId;
        public final String reason;
        public final List<String> excluded;

        Decision(String providerId, String modelId, String reason, List<String> excluded) {
            this.providerId = providerId;
            this.modelId = modelId;
            this.reason = reason;
            this.excluded = excluded;
        }

        public boolean available() {
            return providerId != null;
        }
    }

    private final ProviderRegistry registry;

    public AutoRouter(ProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 选一个模型。
     *
     * @param enabledProviderIds 用户配了 key 的 provider
     * @param task               这次要干什么（决定能力要求）
     */
    public Decision choose(List<String> enabledProviderIds, TaskKind task) {
        List<String> excluded = new ArrayList<>();
        ModelSpec best = null;
        String bestProvider = null;
        boolean bestPriced = false;

        for (ProviderSpec provider : registry.providers()) {
            if (!enabledProviderIds.contains(provider.providerId)) {
                excluded.add(provider.displayName + "：没有配 key");
                continue;
            }
            for (ModelSpec model : provider.models) {
                if (!model.supports(task)) {
                    // **能力不够是硬条件**：不是"贵一点也能用"，是根本吃不下这个任务。
                    excluded.add(model.displayName + "：不支持" + describe(task));
                    continue;
                }
                if (isBetter(model, best)) {
                    best = model;
                    bestProvider = provider.providerId;
                    bestPriced = model.priced();
                }
            }
        }

        if (best == null) {
            return new Decision(null, null,
                    "没有可用的模型：先去「我的 → 模型与密钥」里填一家 provider 的 key", excluded);
        }
        String price = bestPriced
                ? String.format(java.util.Locale.US, "参考价约 %.2f 元/百万 token",
                        best.roughPriceMicrosPer1M() / 1_000_000.0 * 7.2)
                : "价格未知（官方定价页没查到，不编数字）";
        return new Decision(bestProvider, best.modelId,
                "Auto 选了 " + best.displayName + "：" + describe(task) + "、"
                        + (bestPriced ? "已配置的模型里最便宜" : "已配置的模型里唯一能满足能力要求的")
                        + "；" + price, excluded);
    }

    /**
     * 谁更该被选：**先比"知不知道价"**，再比价格。
     *
     * <p>为什么价格未知的排在后面：把它当"最便宜"会让 Auto 常态化地选一个我们
     * 连报价都没有的模型，用户看到的就是"钱不知道花到哪去了"。宁可先选知道价的，
     * 并在 `reason` 里写清楚"价格未知"。
     */
    private static boolean isBetter(ModelSpec candidate, ModelSpec current) {
        if (current == null) {
            return true;
        }
        boolean candidatePriced = candidate.priced();
        boolean currentPriced = current.priced();
        if (candidatePriced != currentPriced) {
            return candidatePriced;
        }
        if (!candidatePriced) {
            return candidate.contextLimit > current.contextLimit;
        }
        return candidate.roughPriceMicrosPer1M() < current.roughPriceMicrosPer1M();
    }

    private static String describe(TaskKind task) {
        switch (task) {
            case IMAGE: return "看图/图片任务";
            case PDF: return "PDF 任务";
            case TOOLS: return "工具调用";
            default: return "文本任务";
        }
    }
}
