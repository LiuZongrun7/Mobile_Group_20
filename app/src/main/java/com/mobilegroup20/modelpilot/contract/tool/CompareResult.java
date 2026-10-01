package com.mobilegroup20.modelpilot.contract.tool;

import java.util.ArrayList;
import java.util.List;

import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.TokenBundle;

/**
 * 区间对比接口（`GET /usage/compare`）的返回值：同一区间内各家的横向对比。
 *
 * <p><b>只比成本和用量，不评价回答质量。</b>这不是能力不够，是刻意的边界：
 * 要评价「哪个模型答得好」就得让用户给每个回答打分，那会把主观评分混进一份
 * 号称客观的数据里。项目里所有指标都必须能从记录里直接算出来（大纲 §3、§5）。
 */
public class CompareResult {

    public final List<Row> rows = new ArrayList<>();

    /** 对比区间，格式 {@code yyyy-MM-dd}。 */
    public String from;
    public String to;

    /** 这次是按哪个指标排的序，回答里要说明，否则用户会以为是按别的排的。 */
    public Metric metric;

    /** 区间内的数据完整度，必填，见 {@link Coverage}。 */
    public Coverage coverage;

    /** 用到的价目表版本。 */
    public final List<String> rateVersions = new ArrayList<>();

    public CompareResult() {
    }

    /** 排序指标。 */
    public enum Metric {
        /** 总成本。 */
        COST,
        /** 总 token 数。注意这个数和钱不成正比，缓存命中会把它拉高但成本很低。 */
        TOKENS,
        /** 每百万 token 的平均成本，最接近「单价」的概念。 */
        COST_PER_1M
    }

    /** 一个提供方 + 模型在区间内的表现。 */
    public static class Row {

        public Provider provider;
        public String model;

        public TokenBundle tokens = new TokenBundle();
        public long calls;
        public long costMicros;

        public Row() {
        }

        /**
         * 每 100 万 token 花多少钱，微美元。<b>跨家比价主要看这个数</b>：
         * 总成本会被用量规模带偏，这个数才是单价。
         */
        public long costPer1MTokensMicros() {
            long total = tokens.total();
            return total == 0 ? 0L : costMicros * 1_000_000L / total;
        }
    }
}
