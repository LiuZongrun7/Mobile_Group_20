package com.mobilegroup20.modelpilot.data.local;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.mobilegroup20.modelpilot.contract.model.DailyUsage;
import com.mobilegroup20.modelpilot.contract.model.PricingRate;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.TokenBundle;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.PricingSource;
import com.mobilegroup20.modelpilot.data.repository.UsageRepository;
import com.mobilegroup20.modelpilot.util.TimeUtils;

/**
 * {@link UsageRepository} 的本地实现：Room + 打包的价目表。
 *
 * <p><b>三层各司其职</b>，这样每一层都能单独测：
 * <ul>
 *   <li>{@link DailyRollup} —— 怎么分组、怎么算钱。纯计算，JUnit 直接跑；</li>
 *   <li>{@link DailyUsageDao} / {@link UsageCallDao} —— 只是把行搬进搬出；</li>
 *   <li>本类 —— 串起来，外加线程和 {@code LiveData} 的转换。这一层薄到几乎没有逻辑。</li>
 * </ul>
 *
 * <p><b>界面侧的查询一行 SQL 都不在这里写。</b>返回 {@code LiveData} 的方法全部直接
 * 委托给 DAO：Room 会在后台线程跑查询，并在表变化时自动重查再推给订阅者，
 * 所以导入了新数据之后界面自己就刷新了，不需要谁去通知谁。
 *
 * <p><b>写入路径走单线程执行器。</b>导入要读原始记录、算汇总、写汇总，
 * 是一个事务；Room 禁止在主线程碰数据库，而并发两批导入也没有任何好处。
 * 单线程既满足前一条，又顺手把后一条排掉了。
 */
public class RoomUsageRepository implements UsageRepository {

    private final AppDatabase db;
    private final PricingSource pricing;
    private final Executor io;

    /**
     * 真正的写入者（校验、去重、重滚汇总）。**不是线程安全的**，但只从 {@link #io}
     * 这一个单线程执行器上调用，所以不需要它自己加锁。
     */
    private final CallImporter importer;

    public RoomUsageRepository(AppDatabase db, PricingSource pricing) {
        this(db, pricing, Executors.newSingleThreadExecutor());
    }

    /** 测试可以换一个 {@code Runnable::run} 的执行器，让写入变成同步的。 */
    public RoomUsageRepository(AppDatabase db, PricingSource pricing, Executor io) {
        this.db = db;
        this.pricing = pricing;
        this.io = io;
        this.importer = new CallImporter(db, pricing);
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 导入一批原始记录，然后把受影响的天重滚一遍汇总。
     *
     * <p><b>真正的写入逻辑在 {@link CallImporter} 里</b>（同步、一个事务），这里只做两件事：
     * 排到写线程上、把结果 post 给 {@code LiveData}。2026-10-06 抽出去是因为它有了第二个
     * 调用方——导入导出文件那一路需要"写完立刻知道几条"，而 {@code LiveData} 给不了。
     *
     * <p>顺序不能反：{@link CallImporter} 读的是刚写进去的原始记录来重滚汇总，
     * 所以插入必须在它之前，而且必须在同一个线程上排队。
     */
    @Override
    public LiveData<ImportResult> importCalls(String uid, List<UsageCall> calls) {
        MutableLiveData<ImportResult> result = new MutableLiveData<>();
        List<UsageCall> incoming = calls == null ? Collections.emptyList() : calls;
        io.execute(() -> result.postValue(importer.importCalls(uid, incoming)));
        return result;
    }

    // ------------------------------------------------------------------ 读取

    @Override
    public LiveData<List<DailyUsage>> dailyUsageFor(String uid, String day) {
        return Transformations.map(db.dailyUsageDao().observeDay(uid, day),
                RoomUsageRepository::toModelList);
    }

    @Override
    public LiveData<List<DailyUsage>> dailyUsageIn(String uid, String from, String to) {
        return Transformations.map(db.dailyUsageDao().observeRange(uid, from, to),
                RoomUsageRepository::toModelList);
    }

    /** 本月已花 token。上界截到昨天：今天还没过完，算进去余额会在一天之内一直变。 */
    @Override
    public LiveData<TokenBundle> monthTokens(String uid, String month) {
        String from = TimeUtils.firstDayOfMonth(month);
        String to = TimeUtils.clampToYesterday(TimeUtils.lastDayOfMonth(month));
        if (to == null || to.compareTo(from) < 0) {
            MutableLiveData<TokenBundle> empty = new MutableLiveData<>();
            empty.postValue(new TokenBundle());
            return empty;
        }
        return Transformations.map(db.dailyUsageDao().observeTotals(uid, from, to),
                totals -> totals == null ? new TokenBundle() : totals.toTokenBundle());
    }

    /**
     * 查价。
     *
     * <p>价目表在打包的 {@link BundledPricingSource} 里，是个常量表，
     * 所以这里一次性取值就够，不需要订阅。返回 null 表示<b>没录这个模型的价</b>，
     * 调用方要把它显示成「价格未知」而不是 0。
     */
    @Override
    public LiveData<PricingRate> rateFor(Provider provider, String model, String day) {
        MutableLiveData<PricingRate> out = new MutableLiveData<>();
        out.postValue(pricing == null ? null : pricing.rateFor(provider, model, day));
        return out;
    }

    // ------------------------------------------------------------ 组装返回值

    private static List<DailyUsage> toModelList(List<DailyUsageEntity> rows) {
        List<DailyUsage> out = new ArrayList<>();
        if (rows != null) {
            for (DailyUsageEntity e : rows) {
                out.add(e.toModel());
            }
        }
        return out;
    }
}
