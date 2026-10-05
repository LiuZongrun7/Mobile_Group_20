package com.mobilegroup20.modelpilot.data.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * {@code usage_call} 表的读写。
 *
 * <p><b>去重的全部实现就是这里的 {@link #insertAll}</b>：主键冲突时
 * {@link OnConflictStrategy#IGNORE} 让那一条不写进去，并在返回的 rowid 列表里给一个
 * -1。调用方数一下有几个 -1 就是「跳过几条重复」，不需要另写查询。
 *
 * <p>这个 DAO 没有返回 {@code LiveData} 的查询：原始记录是只写的，
 * 界面从来不直接看它（那是 {@code daily_usage} 的活）。要读它只有一个场景——
 * 滚汇总时把某天的原始记录捞出来，那个调用发生在后台线程的事务里，用同步返回。
 */
@Dao
public interface UsageCallDao {

    /**
     * 批量插入，冲突忽略。
     *
     * @return 和入参一一对应的 rowid；-1 表示这条因为主键重复被跳过。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    List<Long> insertAll(List<UsageCallEntity> calls);

    /**
     * 取某人某天的全部原始记录，滚 {@code daily_usage} 用。
     *
     * <p>不过滤 {@code source}：哪些该进汇总由 {@link DailyRollup} 一处决定，
     * 过滤条件散在两处（SQL 一处、Java 一处）迟早会不一致。
     */
    @Query("SELECT * FROM usage_call WHERE uid = :uid AND day = :day")
    List<UsageCallEntity> callsOnDay(String uid, String day);

    /** 某人一共有多少条原始记录。给「导入到底有没有生效」这类排查用。 */
    @Query("SELECT COUNT(*) FROM usage_call WHERE uid = :uid")
    int countFor(String uid);

    /**
     * 本机账本在某个月里的明细（Insights 用）。
     *
     * <p>为什么不走 `daily_usage`：那张表的滚动只收 `IMPORTED`（导入的旧记录），
     * 而 App 自己发出去的调用是 `source = APP`。Insights 要回答的是"我花了多少"，
     * 那就得直接读原始记录——而且原始记录里才有 `task_id` / `kind` / `route`，
     * 汇总表里那些维度都被磨平了。
     *
     * <p>`uid` 固定 `local`（见 `CallLedger` 的类注释）：花的是这台手机上的 key，
     * 和登录了没有无关。
     */
    @Query("SELECT * FROM usage_call WHERE uid = :uid AND source = 'APP'"
            + " AND day BETWEEN :from AND :to ORDER BY startedAtEpochMillis DESC")
    List<UsageCallEntity> localCallsInRange(String uid, String from, String to);

    /** 清空。只有测试和「重置本地数据」会用，正常流程没有删除路径。 */
    @Query("DELETE FROM usage_call")
    void deleteAll();

    /**
     * 导本机账本（`data/export`）。
     *
     * <p><b>这里不过滤 {@code source}，也不过滤天以外的东西。</b>导出要的是「这台手机上
     * 记下来的全部」，导入进来的旧记录（{@code IMPORTED}）和 App 自己发出去的
     * （{@code APP}）都在里面——用户对账时看的是他自己花了多少，而不是我们内部按来源
     * 分了几个桶（那是 Insights 的事，它只读 {@code APP}）。来源如实写进每一行，
     * 拿到文件的人自己按 {@code source} 列筛。
     *
     * <p>起止日可空 = 那一头不限。写成 `(:from IS NULL OR ...)` 而不是给两个方法：
     * 「只给上界」「只给下界」「两头都给」「都不给」是四种调用，四个方法名比一个条件难维护。
     * 这个条件用不上索引的极端情况（两头都不限时退化成全表扫）恰好也是「全都要」，
     * 那时候本来就该扫全表。
     */
    @Query("SELECT * FROM usage_call WHERE uid = :uid"
            + " AND (:from IS NULL OR day >= :from) AND (:to IS NULL OR day <= :to)"
            + " ORDER BY startedAtEpochMillis ASC")
    List<UsageCallEntity> callsForExport(String uid, String from, String to);
}
