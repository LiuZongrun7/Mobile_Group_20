package com.mobilegroup20.tokentrail.data.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/**
 * {@code season_state} 表的读写。
 *
 * <p><b>这是显示缓存，不是权威数据。</b>写它只有一个来源——从服务端拉到权威值之后
 * 存一份下来，好让下次冷启动时界面不用等网络。所以这里没有「结算」相关的任何逻辑：
 * 结算读写的必须是服务端那份，写在这里等于给了重装应用刷资源的漏洞。
 *
 * <p>主键是 {@code uid}，一个用户一行，所以写入固定是 REPLACE。
 */
@Dao
public interface SeasonStateDao {

    /** 观察某人的赛季状态，界面订阅它。没有记录时发 null，界面据此显示「还没开始」。 */
    @Query("SELECT * FROM season_state WHERE uid = :uid")
    LiveData<SeasonStateEntity> observe(String uid);

    /** 取一次，不订阅。给需要立刻判断的逻辑用（同步，别在界面线程调）。 */
    @Query("SELECT * FROM season_state WHERE uid = :uid")
    SeasonStateEntity get(String uid);

    /** 覆盖写。权威值从服务端拿到之后调它。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(SeasonStateEntity state);

    /** 清空。只有测试和「重置本地数据」会用。 */
    @Query("DELETE FROM season_state")
    void deleteAll();
}
