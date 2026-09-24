package com.mobilegroup20.tokentrail.contract.tool;

/**
 * {@link AgentTool#GET_BUDGET_STATUS} 的返回值。
 *
 * <p>和 {@link com.mobilegroup20.tokentrail.contract.model.Budget} 的区别：Budget 是
 * 「用户设的上限」，这个是「上限 + 现算出来的花销」的合体。花销永远现算，
 * 不存第二份，理由见 Budget 的类注释。
 */
public class BudgetStatus {

    /** 查询月份，格式 {@code yyyy-MM}。 */
    public String month;

    /** 上限，微美元。没设预算时为 0，此时 {@link #configured} 是 false。 */
    public long capMicros;

    /** 本月已花，微美元。由 {@link com.mobilegroup20.tokentrail.contract.model.DailyUsage} 现算。 */
    public long spentMicros;

    /** 从哪个比例开始警告，0~1。 */
    public double warnAtRatio;

    /**
     * 用户到底设没设预算。
     *
     * <p>单独一个字段而不看 {@code capMicros > 0}：没设预算和设了 0 元预算
     * 是两件事，前者应该说「你没有设预算」，后者应该说「你已经超了」。
     * agent 回答时要用词准确。
     */
    public boolean configured;

    /** 本月数据完整度。必填——缺了 3 天的花销和「本月只花了这么多」不是一回事。 */
    public Coverage coverage;

    public BudgetStatus() {
    }

    /** 还剩多少。超支时为负——不要在这里夹到 0，超了多少本身就是要告诉用户的信息。 */
    public long remainingMicros() {
        return capMicros - spentMicros;
    }

    /** 是不是已经越过警告线。没设预算时永远是 false。 */
    public boolean warnThresholdCrossed() {
        return configured && capMicros > 0 && spentMicros >= capMicros * warnAtRatio;
    }

    /** 已花占上限的比例，0~1 之外也会如实返回。没设预算时返回 0。 */
    public double spentRatio() {
        return capMicros <= 0 ? 0.0 : (double) spentMicros / capMicros;
    }
}
