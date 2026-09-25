package com.mobilegroup20.tokentrail.data.local;

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

    /** 清空。只有测试和「重置本地数据」会用，正常流程没有删除路径。 */
    @Query("DELETE FROM usage_call")
    void deleteAll();
}
