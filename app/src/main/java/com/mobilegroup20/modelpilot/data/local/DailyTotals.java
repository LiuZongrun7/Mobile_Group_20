package com.mobilegroup20.modelpilot.data.local;

import com.mobilegroup20.modelpilot.contract.model.TokenBundle;

/**
 * {@code daily_usage} 的聚合投影，只装 SUM 出来的几个数。
 *
 * <p>为什么不让 {@code monthTokens} 先取回整月的行再在 Java 里加：那个数每次打开游戏
 * 都要读，而它是一条 SQL 就能算完的。让 SQLite 去加，回来的就只有一行。
 *
 * <p>字段名必须和 {@link DailyUsageDao#observeTotals} 里的别名逐字对上，
 * Room 是按名字映射的，对不上是编译期报错（不是运行时），改的时候一起改。
 */
public class DailyTotals {

    public long calls;
    public long input;
    public long cacheRead;
    public long cacheWrite;
    public long output;

    /** 微美元。 */
    public long costMicros;

    /** Room 需要一个无参构造。 */
    public DailyTotals() {
    }

    /** 四类 token 打成契约里的 {@link TokenBundle}。 */
    public TokenBundle toTokenBundle() {
        return new TokenBundle(input, cacheRead, cacheWrite, output);
    }
}
