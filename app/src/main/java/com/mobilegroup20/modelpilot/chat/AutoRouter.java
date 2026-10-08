package com.mobilegroup20.modelpilot.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explainable routing. Capabilities and context fit are hard constraints; preferences rank survivors.
 * Estimates are pre-call scenarios, never billed usage, and do not assume a cache hit.
 */
public final class AutoRouter {
    public static final String POLICY_VERSION = "task-context-cost-preference-v2";
    public enum Preference { LOWEST_COST, LARGE_CONTEXT, PREFERRED_PROVIDER }

    /** Separate output estimate (for ranking) from reserved output capacity (for context fit). */
    public static final class Request {
        public final TaskKind task;
        public final long inputTokens;
        public final long outputTokens;
        public final int reserveOutput;
        public final Preference preference;
        public final String preferredProviderId;

        public Request(TaskKind task, long inputTokens, long outputTokens, int reserveOutput,
                       Preference preference, String preferredProviderId) {
            if (task == null || preference == null || inputTokens < 0 || outputTokens < 0
                    || reserveOutput < 0) {
                throw new IllegalArgumentException("Invalid routing request");
            }
            this.task = task;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.reserveOutput = reserveOutput;
            this.preference = preference;
            this.preferredProviderId = preferredProviderId;
        }
    }

    /** The caller can supply the rendered token count and the engine's actual safety allowance. */
    public interface ContextFit {
        long inputTokens(ModelSpec model);
        long usableTokens(ModelSpec model);
    }

    public static final class Candidate {
        public final String providerId;
        public final String modelId;
        public final long inputTokens;
        public final Long estimatedCostMicros;
        private final ModelSpec model;

        private Candidate(ModelSpec model, long inputTokens, Long estimate) {
            this.model = model;
            this.providerId = model.providerId;
            this.modelId = model.modelId;
            this.inputTokens = inputTokens;
            this.estimatedCostMicros = estimate;
        }
    }

    public static final class Decision {
        public final String providerId;
        public final String modelId;
        public final String reason;
        public final List<String> excluded;
        /** Ranked, compatible candidates, useful for inspection and future UI expansion. */
        public final List<Candidate> candidates;
        public final Long estimatedCostMicros;

        private Decision(Candidate selected, String reason, List<String> excluded,
                         List<Candidate> candidates) {
            providerId = selected == null ? null : selected.providerId;
            modelId = selected == null ? null : selected.modelId;
            estimatedCostMicros = selected == null ? null : selected.estimatedCostMicros;
            this.reason = reason;
            this.excluded = Collections.unmodifiableList(new ArrayList<>(excluded));
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        }

        public boolean available() { return providerId != null; }
    }

    private final ProviderRegistry registry;
    public AutoRouter(ProviderRegistry registry) { this.registry = registry; }

    /** Backward-compatible default for compression: ordinary text with equal input/output estimates. */
    public Decision choose(List<String> enabled, TaskKind task) {
        return choose(enabled, new Request(task, 1024, 1024, ContextEngine.RESERVE_FOR_OUTPUT,
                Preference.LOWEST_COST, null));
    }

    public Decision choose(List<String> enabled, Request request) {
        return choose(enabled, request, new ContextFit() {
            public long inputTokens(ModelSpec model) { return request.inputTokens; }
            public long usableTokens(ModelSpec model) {
                return (long) (model.contextLimit * 0.8) - request.reserveOutput;
            }
        });
    }

    public Decision choose(List<String> enabled, Request request, ContextFit fit) {
        List<String> excluded = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (ProviderSpec provider : registry.providers()) {
            if (!enabled.contains(provider.providerId)) {
                excluded.add(provider.displayName + "：未配置密钥");
                continue;
            }
            for (ModelSpec model : provider.models) {
                if (!model.supports(request.task)) {
                    excluded.add(model.displayName + "：不支持" + describe(request.task));
                    continue;
                }
                long input = fit.inputTokens(model);
                long usable = fit.usableTokens(model);
                if (input < 0 || usable < 0 || input > usable) {
                    excluded.add(model.displayName + "：上下文容量不足（估算输入 " + input
                            + "，可用 " + usable + " token）");
                    continue;
                }
                candidates.add(new Candidate(model, input, estimateCost(model, input, request.outputTokens)));
            }
        }
        candidates.sort((a, b) -> compare(a, b, request));
        if (candidates.isEmpty()) {
            return new Decision(null, "没有满足任务与上下文容量的模型；请检查密钥、模型能力或缩短上下文。",
                    excluded, candidates);
        }
        Candidate best = candidates.get(0);
        String policy;
        switch (request.preference) {
            case LARGE_CONTEXT:
                policy = "优先较大的上下文容量，容量相同时比较估算成本";
                break;
            case PREFERRED_PROVIDER:
                policy = best.providerId.equals(request.preferredProviderId)
                        ? "优先使用你偏好的平台，再比较估算成本"
                        : "偏好平台未配置或不能满足当前任务，回退到其他可用平台";
                break;
            default:
                policy = "按本次输入与预计回答的估算成本排序，未知价格排在已知价格之后";
        }
        String reason = "Auto 选了 " + best.model.displayName + "：" + describe(request.task)
                + "；" + policy + "。估算输入 " + best.inputTokens + " token，预计回答 "
                + request.outputTokens + " token；不假设缓存命中，实际用量以平台返回为准。";
        if (request.task == TaskKind.IMAGE) {
            reason += " 图片按粗略预留量估算，分辨率和平台会影响实际 token 与费用。";
        }
        if (best.estimatedCostMicros == null) {
            reason += " 价格未知，不代表免费。";
        }
        return new Decision(best, reason, excluded, candidates);
    }

    private static int compare(Candidate a, Candidate b, Request request) {
        if (request.preference == Preference.PREFERRED_PROVIDER) {
            boolean ap = a.providerId.equals(request.preferredProviderId);
            boolean bp = b.providerId.equals(request.preferredProviderId);
            if (ap != bp) return ap ? -1 : 1;
        }
        if (request.preference == Preference.LARGE_CONTEXT) {
            int context = Integer.compare(b.model.contextLimit, a.model.contextLimit);
            if (context != 0) return context;
        }
        if ((a.estimatedCostMicros == null) != (b.estimatedCostMicros == null)) {
            return a.estimatedCostMicros == null ? 1 : -1;
        }
        if (a.estimatedCostMicros != null) {
            int cost = Long.compare(a.estimatedCostMicros, b.estimatedCostMicros);
            if (cost != 0) return cost;
        }
        int context = Integer.compare(b.model.contextLimit, a.model.contextLimit);
        if (context != 0) return context;
        return (a.providerId + "/" + a.modelId).compareTo(b.providerId + "/" + b.modelId);
    }

    /** Ceiling rounding avoids treating a small positive estimated charge as free. Overflow = unknown. */
    public static Long estimateCost(ModelSpec model, long input, long output) {
        if (!model.priced() || input < 0 || output < 0 || model.inputMicros < 0 || model.outputMicros < 0) {
            return null;
        }
        try {
            return BigDecimal.valueOf(model.inputMicros).multiply(BigDecimal.valueOf(input))
                    .add(BigDecimal.valueOf(model.outputMicros).multiply(BigDecimal.valueOf(output)))
                    .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private static String describe(TaskKind task) {
        switch (task) {
            case IMAGE: return "图片任务";
            case PDF: return "原生 PDF 任务";
            case TOOLS: return "工具调用";
            default: return "文本任务（包括已提取正文的 PDF）";
        }
    }
}
