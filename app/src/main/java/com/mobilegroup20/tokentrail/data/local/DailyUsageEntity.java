package com.mobilegroup20.tokentrail.data.local;

import androidx.annotation.NonNull;
import androidx.room.Entity;

import com.mobilegroup20.tokentrail.contract.model.DailyUsage;
import com.mobilegroup20.tokentrail.contract.model.Provider;

/**
 * {@code daily_usage} 表：按 {@code (uid, 天, 提供方, 模型)} 的派生汇总。
 *
 * <p><b>主键就是那四个字段</b>，所以「重算某一天某个模型」天然是覆盖写，
 * 不需要先删后插。这个主键的索引前缀也正好服务「取某人某天/某区间」的查询，
 * 因此不再额外建 {@code (uid, day)} 索引——那是重复的。
 *
 * <p>这张表<b>删了能重算</b>（拿 {@code usage_call} 重滚一遍），
 * 它存在的理由是游戏开屏要一瞬间算出余额，不能现场扫几千条原始记录。
 */
@Entity(tableName = "daily_usage", primaryKeys = {"uid", "day", "provider", "model"})
public class DailyUsageEntity {

    @NonNull
    public String uid = "";

    /** {@code yyyy-MM-dd}，Asia/Shanghai。 */
    @NonNull
    public String day = "";

    @NonNull
    public Provider provider = Provider.OPENAI;

    @NonNull
    public String model = "";

    public long calls;

    public long input;
    public long cacheRead;
    public long cacheWrite;
    public long output;

    /** 微美元。算它用的是 {@link #day} 当天生效的那版费率。 */
    public long costMicros;

    /** 算这笔成本用的价目表版本号。界面上的「估算」提示和它对应。 */
    public String rateVersion;

    /**
     * 这天这个模型是否已经换算成游戏资源。
     *
     * <p><b>已结算的行不可变</b>——重滚汇总时必须跳过它，否则补导一条旧记录
     * 就会把已经发过资源的数字改掉，而资源不会跟着退回去。
     * 之后导入的纠正记录走调整项，见 {@code SeasonState} 的类注释。
     */
    public boolean settled;

    public DailyUsageEntity() {
    }

    public static DailyUsageEntity fromModel(DailyUsage d) {
        DailyUsageEntity e = new DailyUsageEntity();
        e.uid = d.uid;
        e.day = d.day;
        e.provider = d.provider;
        e.model = d.model;
        e.calls = d.calls;
        e.input = d.input;
        e.cacheRead = d.cacheRead;
        e.cacheWrite = d.cacheWrite;
        e.output = d.output;
        e.costMicros = d.costMicros;
        e.rateVersion = d.rateVersion;
        e.settled = d.settled;
        return e;
    }

    public DailyUsage toModel() {
        DailyUsage d = new DailyUsage();
        d.uid = uid;
        d.day = day;
        d.provider = provider;
        d.model = model;
        d.calls = calls;
        d.input = input;
        d.cacheRead = cacheRead;
        d.cacheWrite = cacheWrite;
        d.output = output;
        d.costMicros = costMicros;
        d.rateVersion = rateVersion;
        d.settled = settled;
        return d;
    }
}
