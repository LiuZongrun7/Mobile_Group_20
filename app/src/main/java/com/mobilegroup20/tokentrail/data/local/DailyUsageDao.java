package com.mobilegroup20.tokentrail.data.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * {@code daily_usage} 表的读写。这是游戏和 dashboard 真正读的那张表。
 *
 * <p><b>界面侧的查询返回 {@link LiveData}</b>，因为 Room 会在后台线程跑查询、
 * 并在表变化时自动重新查一遍再推给订阅者。这正是当初选 Room 的理由之一——
 * 五个 Repository 接口的返回类型本来就是 {@code LiveData}，两边不用转换。
 *
 * <p><b>滚汇总用的查询是同步的</b>（返回 {@code List}）：它跑在
 * {@code AppDatabase.runInTransaction} 里面，事务里不能有异步回调。
 */
@Dao
public interface DailyUsageDao {

    /** 某人某天的按模型汇总。游戏结算读它。 */
    @Query("SELECT * FROM daily_usage WHERE uid = :uid AND day = :day ORDER BY provider, model")
    LiveData<List<DailyUsageEntity>> observeDay(String uid, String day);

    /** 某人一个区间内的按天明细，含两端。趋势图和热力图读它。 */
    @Query("SELECT * FROM daily_usage WHERE uid = :uid AND day BETWEEN :from AND :to "
            + "ORDER BY day, provider, model")
    LiveData<List<DailyUsageEntity>> observeRange(String uid, String from, String to);

    /**
     * 某人一个区间的合计，在 SQL 里算完。
     *
     * <p>没有 GROUP BY 的聚合查询在 SQLite 里<b>永远返回恰好一行</b>，区间里一条记录
     * 都没有时那一行是各列 NULL，读进 {@code long} 就是 0。
     * 也就是说<b>这里分不出「没有数据」和「花了 0 元」</b>——那正是
     * {@code Coverage} 存在的理由。完整度不额外查一次，直接从
     * {@link #observeRange} 返回的行里算：有行 = 那天有记录，没行 = 那天没记录。
     * 别拿这个方法的 0 去回答「这个月花了多少」，要配上完整度一起说。
     */
    @Query("SELECT SUM(calls) AS calls, SUM(input) AS input, SUM(cacheRead) AS cacheRead, "
            + "SUM(cacheWrite) AS cacheWrite, SUM(output) AS output, SUM(costMicros) AS costMicros "
            + "FROM daily_usage WHERE uid = :uid AND day BETWEEN :from AND :to")
    LiveData<DailyTotals> observeTotals(String uid, String from, String to);

    /**
     * 取指定几天的现有汇总行，滚汇总时判断哪些能改。
     *
     * <p>之所以要先把它们读出来：{@link #upsertAll} 是覆盖写，
     * 而 {@code settled = true} 的行<b>不可变</b>。不先读一遍就写，
     * 会悄悄改掉已经发过资源的历史数字。调用方必须在同一个事务里读-改-写。
     */
    @Query("SELECT * FROM daily_usage WHERE uid = :uid AND day IN (:days)")
    List<DailyUsageEntity> rowsOnDays(String uid, List<String> days);

    /** 某天的现有汇总行。同上，在事务里用。 */
    @Query("SELECT * FROM daily_usage WHERE uid = :uid AND day = :day")
    List<DailyUsageEntity> rowsOnDay(String uid, String day);

    /**
     * 写入或覆盖汇总行。
     *
     * <p>用 REPLACE 而不是 IGNORE：重算某一天得到的新值本来就该盖掉旧值
     * （补导了记录、或者费率改了要重算）。<b>调用方负责把 {@code settled} 的行排除掉</b>，
     * 见 {@link #rowsOnDays}。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<DailyUsageEntity> rows);

    /** 清空。只有测试和「重置本地数据」会用。 */
    @Query("DELETE FROM daily_usage")
    void deleteAll();
}
