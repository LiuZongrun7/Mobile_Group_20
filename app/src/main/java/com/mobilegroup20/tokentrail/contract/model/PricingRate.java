package com.mobilegroup20.tokentrail.contract.model;

/**
 * 一版价目表：某个模型从某天起、四类 token 各自的单价。
 *
 * <p>费率是<b>版本化</b>的，不是一张会变的表。查价永远按「生效日 &lt;= 账单日」取
 * 最晚的一版，这样去年 12 月的记录即使在今年涨价之后重新导入，算出来的还是当时的价。
 * 大纲的验收项里专门有一条「价格版本边界」，就是测这个。
 *
 * <p>单价单位是<b>每 100 万 token 多少微美元</b>，因为各家官网都是按 1M token 报价的，
 * 直接照抄不会抄错小数点。$3 / 1M 在这里就是 3_000_000。
 */
public class PricingRate {

    public Provider provider;

    public String model;

    /** 这版价格开始生效的日期，格式 {@code yyyy-MM-dd}，含当天。 */
    public String effectiveFrom;

    /** 给人和界面看的版本标识，如 "2026-09" 或官网页面的日期。会写进汇总行。 */
    public String rateVersion;

    /** 每 100 万普通输入 token 的价格，微美元。 */
    public long inputMicrosPer1M;

    /** 每 100 万缓存读 token 的价格，微美元。 */
    public long cacheReadMicrosPer1M;

    /** 每 100 万缓存写 token 的价格，微美元。不支持缓存的模型填 0。 */
    public long cacheWriteMicrosPer1M;

    /** 每 100 万输出 token 的价格，微美元（含推理 token）。 */
    public long outputMicrosPer1M;

    /** 数据来源页面，界面上要能点开——所有数字都标着「估算」，得让人能查回去。 */
    public String sourceUrl;

    public PricingRate() {
    }

    /**
     * 按这个费率算一组 token 的成本，单位微美元。
     *
     * <p>先乘后除，全程整数：token 数最多到 1e8 量级、单价到 1e8 量级，
     * 乘积在 long 范围内还很宽裕，不会溢出。
     */
    public long costMicros(TokenBundle tokens) {
        long total = tokens.input * inputMicrosPer1M
                + tokens.cacheRead * cacheReadMicrosPer1M
                + tokens.cacheWrite * cacheWriteMicrosPer1M
                + tokens.output * outputMicrosPer1M;
        return total / 1_000_000L;
    }
}
