package com.mobilegroup20.tokentrail.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.SeasonState;

/**
 * {@code season_state} 表：赛季与余额的<b>显示缓存</b>。
 *
 * <p><b>权威副本在服务端，这张表不是。</b>原因是结算要按天推进：
 * 余额如果只存在本地，重装应用或者换台设备就能把已经结算过的天再领一遍资源。
 * 所以这里的数字是拿来秒开界面的，任何结算决策都必须以服务端那份为准。
 *
 * <p>{@link ResourceBalance} 那三个字段在这里平铺成三列，不用嵌套对象——
 * 嵌套要额外写一层 {@code @Embedded}，而查询和更新从来都是整体读写，
 * 拆开没有任何好处。
 */
@Entity(tableName = "season_state")
public class SeasonStateEntity {

    @PrimaryKey
    @NonNull
    public String uid = "";

    /** {@code yyyy-MM}，和账单周期同月。 */
    public String seasonId;

    /** 已结算到哪一天（含）。从没结算过是 null。 */
    public String lastSettledDay;

    public long balanceInput;
    public long balanceCache;
    public long balanceOutput;

    public long updatedAtEpochMillis;

    public SeasonStateEntity() {
    }

    public static SeasonStateEntity fromModel(SeasonState s) {
        SeasonStateEntity e = new SeasonStateEntity();
        e.uid = s.uid;
        e.seasonId = s.seasonId;
        e.lastSettledDay = s.lastSettledDay;
        ResourceBalance b = s.balance == null ? new ResourceBalance() : s.balance;
        e.balanceInput = b.input;
        e.balanceCache = b.cache;
        e.balanceOutput = b.output;
        e.updatedAtEpochMillis = s.updatedAtEpochMillis;
        return e;
    }

    public SeasonState toModel() {
        SeasonState s = new SeasonState();
        s.uid = uid;
        s.seasonId = seasonId;
        s.lastSettledDay = lastSettledDay;
        s.balance = new ResourceBalance(balanceInput, balanceCache, balanceOutput);
        s.updatedAtEpochMillis = updatedAtEpochMillis;
        return s;
    }
}
