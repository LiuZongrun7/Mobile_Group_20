package com.mobilegroup20.tokentrail.contract.model;

/**
 * 当前赛季的状态：结算推进到哪里、手里有多少资源。
 *
 * <p>赛季按自然月走，和 AI 订阅的账单周期对齐（大纲 §5）。
 *
 * <p>这个结构是<b>结算幂等性的落点</b>，四条规则必须一起成立，少一条就会多发资源：
 * <ol>
 *   <li>{@link #lastSettledDay} 之前（含）的天都已结算，之后的天没有；</li>
 *   <li><b>今天永远不结算</b>——当天还没结束，结了就是漏账；</li>
 *   <li>结算要<b>补齐</b>：三天没开应用，下次打开一次补三天，不能指望 WorkManager 天天准点跑；</li>
 *   <li>已结算的天不再改动。之后导入纠正记录只能生成调整项，不改历史。</li>
 * </ol>
 *
 * <p>这份状态服务端持有权威副本，本地那份只用于显示，见 {@link ResourceBalance}。
 */
public class SeasonState {

    public String uid;

    /** 赛季标识，格式固定 {@code yyyy-MM}，和账单周期同月。 */
    public String seasonId;

    /**
     * 已经结算到哪一天，格式 {@code yyyy-MM-dd}，含当天。
     * 从没结算过时是 null，表示从头开始补。
     */
    public String lastSettledDay;

    /** 本赛季累计的资源余额。 */
    public ResourceBalance balance;

    /** 最后一次结算的时间，UTC 毫秒。用来排查「怎么没结算」这类问题。 */
    public long updatedAtEpochMillis;

    public SeasonState() {
    }

    /** 这一天是否已经结算过。幂等判断就看这里。 */
    public boolean isSettled(String day) {
        return lastSettledDay != null && lastSettledDay.compareTo(day) >= 0;
    }
}
