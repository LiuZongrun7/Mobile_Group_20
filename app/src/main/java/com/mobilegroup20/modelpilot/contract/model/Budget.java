package com.mobilegroup20.modelpilot.contract.model;

/**
 * 一个月的预算上限。
 *
 * <p>只存「上限」和「从哪个比例开始警告」，<b>不存已花多少</b>——已花的数是从
 * {@link DailyUsage} 现算的。存一份花销出来就会出现两个数打架：改了费率、补导了
 * 日志之后，那个缓存值就错了，而现算永远是对的。
 */
public class Budget {

    public String uid;

    /** 预算归属的月份，格式 {@code yyyy-MM}。 */
    public String month;

    /** 上限，微美元。 */
    public long capMicros;

    /**
     * 花到上限的百分之多少开始警告，0~1 之间，一般填 0.8。
     * 用比例而不是绝对金额，这样改上限时不用同时改警告线。
     */
    public double warnAtRatio;

    public Budget() {
    }

    public Budget(String uid, String month, long capMicros, double warnAtRatio) {
        this.uid = uid;
        this.month = month;
        this.capMicros = capMicros;
        this.warnAtRatio = warnAtRatio;
    }
}
