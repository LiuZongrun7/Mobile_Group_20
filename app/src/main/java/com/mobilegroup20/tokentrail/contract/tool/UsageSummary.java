package com.mobilegroup20.tokentrail.contract.tool;

import java.util.ArrayList;
import java.util.List;

import com.mobilegroup20.tokentrail.contract.model.PricingRate;
import com.mobilegroup20.tokentrail.contract.model.Provider;
import com.mobilegroup20.tokentrail.contract.model.TokenBundle;

/**
 * {@link AgentTool#GET_USAGE_SUMMARY} 的返回值。
 *
 * <p>时间范围在请求里由服务端按用户的问题折算成具体日期再传进来，
 * 不在客户端算——否则两个人问「上周」可能落在两个区间上。
 */
public class UsageSummary {

    /** 整个区间的合计。模型要解释「一共多少」时直接引用这个，不要自己加行。 */
    public TokenBundle totals = new TokenBundle();

    /** 分组明细，按 {@link #groupBy} 决定每行代表什么。 */
    public final List<Row> rows = new ArrayList<>();

    /** 区间内实际有数据的天数、缺哪天。必填，见 {@link Coverage}。 */
    public Coverage coverage;

    /**
     * 算这些钱用到的价目表版本号。同一区间里跨了涨价日的，会有多个版本。
     *
     * <p>回答里要带上它：用户看到的数字都是估算，得能说清是按哪版价格估的。
     */
    public final List<String> rateVersions = new ArrayList<>();

    /** 这次是按什么维度分的组。 */
    public GroupBy groupBy;

    public UsageSummary() {
    }

    /** 分组维度。 */
    public enum GroupBy {
        /** 每行一天。问「最近怎么样」用这个。 */
        DAY,
        /** 每行一个模型。问「哪个模型贵」用这个。 */
        MODEL,
        /** 每行一个提供方。问「GPT 和 GLM 比呢」用这个。 */
        PROVIDER
    }

    /** 一行汇总。哪个字段有值取决于 {@link #groupBy}。 */
    public static class Row {

        /** 分组键的可读写法：日期、模型名或提供方名。 */
        public String key;

        /** 只有按模型/提供方分组时才有值，按天分组时为 null。 */
        public Provider provider;

        /** 只有按模型分组时才有值。 */
        public String model;

        public TokenBundle tokens = new TokenBundle();

        /** 调用次数。算「单次均价」要用。 */
        public long calls;

        /** 成本，微美元。 */
        public long costMicros;

        /** 这一行是按哪版价目表算的。 */
        public String rateVersion;

        public Row() {
        }

        /**
         * 单次调用的平均成本，微美元。用户问「是不是这个模型每次都很贵」时用得上。
         * 调用次数为 0 时返回 0，不要抛异常——空区间是正常情况。
         */
        public long costPerCallMicros() {
            return calls == 0 ? 0L : costMicros / calls;
        }
    }

    /** 便于实现方构造：直接拿一版费率算一行。 */
    public static long costOf(PricingRate rate, TokenBundle tokens) {
        return rate == null ? 0L : rate.costMicros(tokens);
    }
}
