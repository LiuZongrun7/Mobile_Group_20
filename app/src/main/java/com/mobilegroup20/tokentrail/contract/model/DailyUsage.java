package com.mobilegroup20.tokentrail.contract.model;

/**
 * 按 {@code (uid, 天, 提供方, 模型)} 滚出来的用量汇总，由 {@link UsageCall} 派生。
 *
 * <p>这是<b>游戏和 agent 唯一读的东西</b>。为什么要多这一层：
 * <ul>
 *   <li>游戏要在打开应用的一瞬间算出余额，不能现场扫几千条原始记录；</li>
 *   <li>日结算是按天推进的，需要「这天结算过没有」这个可写标记；</li>
 *   <li>费率是按当时生效的那版算的，得把版本号记在汇总行上，否则以后改了费率
 *       就对不上历史数字。</li>
 * </ul>
 *
 * <p>维度切到 model 为止，是为了能查到单价；<b>游戏不需要这个维度</b>，
 * 把同一天的几行按 {@link TokenBundle#add} 加一起就是你要的「总输入 / 缓存 / 输出」。
 *
 * <p><b>只有 {@link UsageCall.Source#IMPORTED} 的记录会滚进来。</b>演示用的样例数据
 * 不进汇总，因此不会进预算、也不会换成游戏资源——大纲的 Alpha 验收项要求把真实日志
 * 和样例数据分开，这里就是那条分界线。
 */
public class DailyUsage {

    public String uid;

    /**
     * 归属的日历天，格式固定 {@code yyyy-MM-dd}，时区固定 {@code Asia/Shanghai}。
     *
     * <p>用字符串而不是日期对象，是为了让 Firestore 字段、JSON、数据库列三边长得
     * 一样，也避免因为两端时区不同而把同一天算成两天。
     */
    public String day;

    public Provider provider;

    public String model;

    /** 这天这个模型被调用了多少次。 */
    public long calls;

    /** 四类 token 平铺，理由见 {@link TokenBundle} 的类注释。 */
    public long input;
    public long cacheRead;
    public long cacheWrite;
    public long output;

    /**
     * 用 {@link #day} 当天生效的费率算出的成本，单位是<b>微美元</b>（1e-6 USD）。
     *
     * <p>用整数微单位而不是 double：钱不能有浮点误差，而且一分钱的零头累积起来
     * 在月度汇总上会看得见。
     */
    public long costMicros;

    /**
     * 算这笔成本用的价目表版本号。界面上的「估算」提示和它对应。
     *
     * <p>三个取值各有各的意思，界面必须分开显示，别都当成一个普通字符串：
     * <ul>
     *   <li><b>具体版本号</b>（如 {@code "2026-09"}）——我们按那版价目表算的，是估算；</li>
     *   <li>{@link #RATE_VERSION_FROM_SOURCE}——这笔钱是<b>来源账单直接给的</b>，
     *       不是我们算的，比估算准；</li>
     *   <li><b>null</b>——<b>没查到价，这个成本不可信</b>。此时 {@link #costMicros}
     *       是 0，但那是「不知道」而不是「没花钱」。界面上要显示成「价格未知」，
     *       直接显示 0 元等于把「没录价格」伪装成「免费」。</li>
     * </ul>
     */
    public String rateVersion;

    /**
     * {@link #rateVersion} 的哨兵值：成本来自来源账单，而不是我们按价目表算的。
     *
     * <p>放在契约里而不是实现里，是因为界面要能认出它并换一种说法
     * （「来自账单」而不是「按 2026-09 版价目估算」）。
     */
    public static final String RATE_VERSION_FROM_SOURCE = "source";

    /**
     * 这天是否已经换算成游戏资源。
     *
     * <p>结算必须幂等——同一天被算第二次不能发两次资源。这个标记加一个余额更新的
     * 事务就是幂等的落地点。已结算的天<b>不可变</b>：之后导入的纠正记录走调整项，
     * 不回头改这里。
     */
    public boolean settled;

    public DailyUsage() {
    }
}
