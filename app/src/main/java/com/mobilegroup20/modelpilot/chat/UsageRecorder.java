package com.mobilegroup20.modelpilot.chat;

import com.mobilegroup20.modelpilot.contract.model.PricingRate;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.TokenBundle;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.PricingSource;

import java.util.List;

/**
 * 把"一次模型调用"记成账本里的一行。
 *
 * <p>这是新主线唯一的记账入口：用户填自己的 key、我们直连各家，**每次调用落一行**。
 * 服务端不参与（见 `docs/CHAT_ENGINE.md` §1），所以金额在本机按**生效日期**的费率算。
 *
 * <p>三条不能违反的规矩，每条都有对应测试：
 *
 * 1. **算不出价就是 `null`，不是 0。** `CONTRACTS.md` §4：把"不知道花了多少"
 *    显示成"花了 0 元"是那一节点名的、最难发现的一类 bug——数字看着没错、结论是错的。
 * 2. **费率按这次调用发生的那一天查**，不按"现在"。用户可能今天才看到上周的调用，
 *    而价格在中间改过一版；按现在查会把历史重新定价。
 * 3. **`route` 与 `toolCalls` 分得清"不知道"和"没有"**：手动选的写 `MANUAL`、
 *    Auto 挑的写 `AUTO`；导入的记录这两个字段是 `null`（不知道），不是空串。
 */
public final class UsageRecorder {

    /** 这次调用是怎么定的模型。 */
    public enum Route {
        /** Auto 挑的。 */
        AUTO,
        /** 用户手动指定（Auto 不许替换它）。 */
        MANUAL
    }

    private final PricingSource pricing;

    public UsageRecorder(PricingSource pricing) {
        this.pricing = pricing;
    }

    /**
     * 记一行。
     *
     * @param callId          去重键（形状 `call:<provider>:<chat 前缀>:<毫秒>:<随机>`）
     * @param providerId      六家里的哪个（`Provider` 枚举认不出来的**返回 null 整行不记**，
     *                        因为账本的 provider 列是有枚举约束的，编一个值进去比不记更糟）
     * @param chatId          属于哪条对话；导入的记录传 null
     * @param toolCalls       这次跑了哪些工具；没有就传空表，导入的记录传 null
     * @param tokens          四桶（`input` 只装未命中缓存的部分，见 `CONTRACTS.md` §8）
     * @param day             这次调用发生的**北京时间的哪一天**（`yyyy-MM-dd`）
     */
    public UsageCall record(String callId, String providerId, String modelId, Route route,
                            String chatId, List<String> toolCalls, TokenBundle tokens,
                            long startedAtEpochMillis, String day) {
        Provider provider = providerOf(providerId);
        if (provider == null) {
            return null;                        // 认不出的 provider：不猜、不记
        }
        UsageCall call = new UsageCall();
        call.id = callId;
        call.provider = provider;
        call.model = modelId;
        call.source = UsageCall.Source.APP;
        call.startedAtEpochMillis = startedAtEpochMillis;
        // **不设 `day` 和 `calls`**：`UsageCall` 里没有这两个字段——
        // `day` 是 `UsageCallEntity` 从 `startedAtEpochMillis` 按北京时间算出来的冗余列，
        // `calls` 由实体固定写 1（一行就是一次调用）。这里传 `day` 只是为了**查那一天生效的费率**。
        call.input = tokens.input;
        call.cacheRead = tokens.cacheRead;
        call.cacheWrite = tokens.cacheWrite;
        call.output = tokens.output;
        call.route = route == null ? null : route.name();
        call.chatId = chatId;
        call.toolCalls = toolCalls == null ? null : join(toolCalls);

        PricingRate rate = pricing.rateFor(provider, modelId, day);
        if (rate == null) {
            // **算不出来就留 null。** 这里最容易写错的一步是"顺手填 0"。
            call.costMicros = null;
            call.rateVersion = null;
        } else {
            call.costMicros = rate.costMicros(tokens);
            call.rateVersion = rate.rateVersion;
            call.costCurrency = "USD";
        }
        return call;
    }

    /** providerId → 枚举。认不出来返回 null（六家之外的、拼错的都算认不出来）。 */
    public static Provider providerOf(String providerId) {
        if (providerId == null) {
            return null;
        }
        for (Provider candidate : Provider.values()) {
            if (candidate.name().equals(providerId)) {
                return candidate;
            }
        }
        return null;
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        return out.toString();
    }
}
