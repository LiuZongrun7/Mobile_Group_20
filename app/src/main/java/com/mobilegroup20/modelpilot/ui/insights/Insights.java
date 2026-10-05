package com.mobilegroup20.modelpilot.ui.insights;

import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一个月的本机账本滚成几块数字（**纯函数，不碰 Android**，所以能被单测钉住）。
 *
 * <p>大纲 §9 对账本的要求是"正确性与隐私"：日期要对、缓存不许重复计、
 * 缺的用量不许变成 0、任务要能汇总。这些全是算术，放在这里一律可测。
 *
 * <p>三条贯穿始终的口径：
 *
 * <ol>
 *   <li><b>算不出价就是"未知"，不是 0。</b>一行算不出价，它的金额进不了总和，
 *       但要单独计数并在界面上说明"其中 N 次没有价格"——否则用户会以为
 *       "总共就花了这么多"，而实际是"有一部分我们不知道"。</li>
 *   <li><b>token 按四桶加</b>（输入 + 缓存读 + 缓存写 + 输出）。账本里 `input`
 *       只装未命中缓存的那段（`CONTRACTS.md` §8），只加它会少算缓存那部分。</li>
 *   <li><b>Auto 占比按"回答"算</b>，不把压缩算进去：压缩是系统行为，
 *       拿它稀释"用户有多少次交给 Auto 决定"会让这个比例失去意义。</li>
 * </ol>
 */
public final class Insights {

    /** 一个 provider（或一个 model）的汇总。 */
    public static final class Bucket {
        public final String label;
        public long calls;
        public long tokens;
        /** 算得出价的那几次的金额之和（微美元）。 */
        public long costMicros;
        /** 算不出价的次数——**它必须显示出来**，否则总金额看起来像"全部"。 */
        public long unknownCostCalls;

        Bucket(String label) {
            this.label = label;
        }

        /** 这一桶里有没有至少一次算得出价（决定界面显示金额还是"价格未知"）。 */
        public boolean costKnown() {
            return calls > unknownCostCalls;
        }
    }

    /** 整月的样子。 */
    public static final class Summary {
        public long calls;
        public long tokens;
        public long input;
        public long cacheRead;
        public long cacheWrite;
        public long output;
        public long costMicros;
        public long unknownCostCalls;
        /** 回答次数里有多少次是 Auto 挑的（压缩不计入，见类注释）。 */
        public long answerCalls;
        public long autoAnswerCalls;
        public long compressCalls;
        public final List<Bucket> providers = new ArrayList<>();
        public final List<Bucket> models = new ArrayList<>();

        /** Auto 占比（0–100）；一次回答都没有时返回 null = 不知道，**不是 0%**。 */
        public Integer autoPercent() {
            if (answerCalls == 0) {
                return null;
            }
            return (int) Math.round(autoAnswerCalls * 100.0 / answerCalls);
        }
    }

    private Insights() {
    }

    public static Summary summarize(List<UsageCallEntity> calls) {
        Summary summary = new Summary();
        Map<String, Bucket> byProvider = new LinkedHashMap<>();
        Map<String, Bucket> byModel = new LinkedHashMap<>();
        for (UsageCallEntity call : calls) {
            long tokens = call.input + call.cacheRead + call.cacheWrite + call.output;
            summary.calls++;
            summary.tokens += tokens;
            summary.input += call.input;
            summary.cacheRead += call.cacheRead;
            summary.cacheWrite += call.cacheWrite;
            summary.output += call.output;
            if (call.costMicros == null) {
                summary.unknownCostCalls++;
            } else {
                summary.costMicros += call.costMicros;
            }
            boolean answer = !"COMPRESS".equals(call.kind);
            if (answer) {
                summary.answerCalls++;
                if ("AUTO".equals(call.route)) {
                    summary.autoAnswerCalls++;
                }
            } else {
                summary.compressCalls++;
            }
            Bucket provider = bucket(byProvider, nameOf(call.provider));
            provider.calls++;
            provider.tokens += tokens;
            Bucket model = bucket(byModel, call.model);
            model.calls++;
            model.tokens += tokens;
            for (Bucket target : new Bucket[] {provider, model}) {
                if (call.costMicros == null) {
                    target.unknownCostCalls++;
                } else {
                    target.costMicros += call.costMicros;
                }
            }
        }
        summary.providers.addAll(sorted(byProvider));
        summary.models.addAll(sorted(byModel));
        return summary;
    }

    /** 花的钱多的排前面；金额都一样（比如全都未知）时按 token 排，最后按名字稳定排序。 */
    private static List<Bucket> sorted(Map<String, Bucket> buckets) {
        List<Bucket> list = new ArrayList<>(buckets.values());
        Collections.sort(list, new Comparator<Bucket>() {
            @Override public int compare(Bucket a, Bucket b) {
                if (a.costMicros != b.costMicros) {
                    return Long.compare(b.costMicros, a.costMicros);
                }
                if (a.tokens != b.tokens) {
                    return Long.compare(b.tokens, a.tokens);
                }
                return a.label.compareTo(b.label);
            }
        });
        return list;
    }

    private static Bucket bucket(Map<String, Bucket> map, String label) {
        Bucket existing = map.get(label);
        if (existing == null) {
            existing = new Bucket(label);
            map.put(label, existing);
        }
        return existing;
    }

    private static String nameOf(Provider provider) {
        return provider == null ? "Unknown" : provider.displayName;
    }
}
