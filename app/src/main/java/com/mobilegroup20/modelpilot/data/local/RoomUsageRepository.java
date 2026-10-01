package com.mobilegroup20.modelpilot.data.local;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.mobilegroup20.modelpilot.contract.model.DailyUsage;
import com.mobilegroup20.modelpilot.contract.model.PricingRate;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.TokenBundle;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.contract.tool.CompareResult;
import com.mobilegroup20.modelpilot.contract.tool.Coverage;
import com.mobilegroup20.modelpilot.contract.tool.UsageSummary;
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

    public RoomUsageRepository(AppDatabase db, PricingSource pricing) {
        this(db, pricing, Executors.newSingleThreadExecutor());
    }

    /** 测试可以换一个 {@code Runnable::run} 的执行器，让写入变成同步的。 */
    public RoomUsageRepository(AppDatabase db, PricingSource pricing, Executor io) {
        this.db = db;
        this.pricing = pricing;
        this.io = io;
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 导入一批原始记录，然后把受影响的天重滚一遍汇总。
     *
     * <p>顺序不能反：{@link #rollupDays} 读的是刚写进去的原始记录，
     * 所以插入必须在它之前，而且必须在同一个线程上排队。
     *
     * <p>三类计数怎么来的：
     * <ul>
     *   <li>{@code rejected} —— 转换时就退回的（缺 id / 缺模型 / 时间戳非法），
     *       它们根本没进数据库；</li>
     *   <li>{@code skippedDuplicates} —— 进库了但主键撞车，Room 返回 -1；</li>
     *   <li>{@code inserted} —— 剩下的。只有这些的天需要重滚。</li>
     * </ul>
     */
    @Override
    public LiveData<ImportResult> importCalls(String uid, List<UsageCall> calls) {
        MutableLiveData<ImportResult> result = new MutableLiveData<>();
        List<UsageCall> incoming = calls == null ? Collections.emptyList() : calls;

        io.execute(() -> {
            List<UsageCallEntity> rows = new ArrayList<>(incoming.size());
            int rejected = 0;
            for (UsageCall call : incoming) {
                // uid 传参数的那个，不是 call.uid——日志是外部输入，
                // 信它等于允许一份日志把自己写成别人的用量。
                UsageCallEntity row = UsageCallEntity.fromModel(call, uid);
                if (row == null) {
                    rejected++;
                } else {
                    rows.add(row);
                }
            }

            if (rows.isEmpty()) {
                result.postValue(new ImportResult(0, 0, rejected));
                return;
            }

            // runInTransaction 的入参是 Runnable，没有返回值，所以计数用数组带出来。
            // [0] = inserted，[1] = skippedDuplicates。
            int[] counts = new int[2];

            // 插进去和重滚汇总必须在同一个事务里：中途失败会留下一批原始记录
            // 对应着过期的日汇总，而日汇总没有「待重算」标记，没人会知道要修。
            db.runInTransaction(() -> {
                List<Long> rowIds = db.usageCallDao().insertAll(rows);

                Set<String> affectedDays = new LinkedHashSet<>();
                for (int i = 0; i < rowIds.size(); i++) {
                    Long rowId = rowIds.get(i);
                    // -1 == 主键冲突被跳过，见 UsageCallDao.insertAll。
                    if (rowId != null && rowId != -1L) {
                        counts[0]++;
                        affectedDays.add(rows.get(i).day);
                    } else {
                        counts[1]++;
                    }
                }

                // 只重滚真正新增了记录的天：撞车的那条早就在库里，
                // 它那天的汇总当时就算过了。
                rollupDays(uid, affectedDays);
            });

            result.postValue(new ImportResult(counts[0], counts[1], rejected));
        });
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

    @Override
    public LiveData<UsageSummary> summary(String uid, String from, String to,
                                          UsageSummary.GroupBy groupBy) {
        return Transformations.map(db.dailyUsageDao().observeRange(uid, from, to),
                rows -> buildSummary(from, to, groupBy, rows));
    }

    @Override
    public LiveData<CompareResult> compare(String uid, String from, String to,
                                           CompareResult.Metric metric) {
        return Transformations.map(db.dailyUsageDao().observeRange(uid, from, to),
                rows -> buildCompare(from, to, metric, rows));
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

    // ------------------------------------------------------------ 滚汇总（内部）

    /**
     * 重滚指定几天的日汇总。
     *
     * <p><b>已结算的行跳过不写。</b>{@code settled = true} 意味着那天的 token 已经换成
     * 游戏资源发下去了，改数字不会把资源收回来，只会让账和余额对不上。
     * 之后再补导的纠正记录应该走独立的调整项，不要回头改已经滚过汇总的那一天。
     *
     * <p>调用方必须已经在事务里（{@link #importCalls} 就是这么调的）：
     * 这里先读后写，中间隔着一次计算，不在事务里的话并发导入会互相覆盖。
     */
    private void rollupDays(String uid, Set<String> days) {
        if (days == null || days.isEmpty()) {
            return;
        }
        for (String day : days) {
            // 同一天里哪些 (提供方, 模型) 已经结算过，这些不能碰。
            Set<String> frozen = new HashSet<>();
            for (DailyUsageEntity existing : db.dailyUsageDao().rowsOnDay(uid, day)) {
                if (existing.settled) {
                    frozen.add(keyOf(existing.provider, existing.model));
                }
            }

            List<UsageCall> dayCalls = new ArrayList<>();
            for (UsageCallEntity e : db.usageCallDao().callsOnDay(uid, day)) {
                dayCalls.add(e.toModel());
            }

            List<DailyUsageEntity> write = new ArrayList<>();
            for (DailyUsage merged : DailyRollup.rollup(uid, dayCalls, pricing)) {
                if (frozen.contains(keyOf(merged.provider, merged.model))) {
                    continue;
                }
                write.add(DailyUsageEntity.fromModel(merged));
            }
            if (!write.isEmpty()) {
                db.dailyUsageDao().upsertAll(write);
            }
        }
    }

    // ------------------------------------------------------------ 组装返回值

    /**
     * 把 {@code daily_usage} 的行拼成 {@link UsageSummary}。
     *
     * <p>完整度不额外查一次数据库：区间里<b>有行就是那天有记录，没行就是没记录</b>，
     * 这正是 {@link Coverage#daysMissing} 的定义（「一条记录都没有」，
     * 而不是「用量为 0」——用量为 0 也会有一行，因为那天有调用）。
     */
    private static UsageSummary buildSummary(String from, String to,
                                             UsageSummary.GroupBy groupBy,
                                             List<DailyUsageEntity> rows) {
        UsageSummary out = new UsageSummary();
        out.groupBy = groupBy;

        Map<String, UsageSummary.Row> grouped = new LinkedHashMap<>();
        for (DailyUsageEntity e : rows) {
            String key = summaryKeyOf(e, groupBy);
            UsageSummary.Row row = grouped.get(key);
            if (row == null) {
                row = new UsageSummary.Row();
                row.key = summaryLabelOf(e, groupBy);
                row.provider = groupBy == UsageSummary.GroupBy.DAY ? null : e.provider;
                row.model = groupBy == UsageSummary.GroupBy.MODEL ? e.model : null;
                grouped.put(key, row);
            }
            row.tokens.add(TokenBundle.from(e.toModel()));
            row.calls += e.calls;
            row.costMicros += e.costMicros;
            // 行是按天升序来的，所以最后赋值的那一版就是最新的那版。
            // 跨了涨价日的分组只能留一个版本号，逐日的准确版本在 rateVersions 里。
            if (e.rateVersion != null) {
                row.rateVersion = e.rateVersion;
            }
        }

        out.rows.addAll(grouped.values());
        for (UsageSummary.Row row : out.rows) {
            out.totals.add(row.tokens);
        }
        out.coverage = coverageOf(from, to, rows);
        out.rateVersions.addAll(rateVersionsOf(rows));
        return out;
    }

    /**
     * 把 {@code daily_usage} 的行拼成 {@link CompareResult}，按指标从大到小排。
     *
     * <p>排在最前面的是花得最多的那个——这个方向是刻意的：问「比一比」的人
     * 通常想知道「谁最贵」，而不是「谁最便宜」。
     */
    private static CompareResult buildCompare(String from, String to,
                                              CompareResult.Metric metric,
                                              List<DailyUsageEntity> rows) {
        CompareResult out = new CompareResult();
        out.from = from;
        out.to = to;
        out.metric = metric;
        out.coverage = coverageOf(from, to, rows);
        out.rateVersions.addAll(rateVersionsOf(rows));

        Map<String, CompareResult.Row> grouped = new LinkedHashMap<>();
        for (DailyUsageEntity e : rows) {
            String key = keyOf(e.provider, e.model);
            CompareResult.Row row = grouped.get(key);
            if (row == null) {
                row = new CompareResult.Row();
                row.provider = e.provider;
                row.model = e.model;
                grouped.put(key, row);
            }
            row.tokens.add(TokenBundle.from(e.toModel()));
            row.calls += e.calls;
            row.costMicros += e.costMicros;
        }

        out.rows.addAll(grouped.values());
        out.rows.sort((a, b) -> Long.compare(metricValue(b, metric), metricValue(a, metric)));
        return out;
    }

    private static long metricValue(CompareResult.Row row, CompareResult.Metric metric) {
        switch (metric) {
            case TOKENS:
                return row.tokens.total();
            case COST_PER_1M:
                return row.costPer1MTokensMicros();
            case COST:
            default:
                return row.costMicros;
        }
    }

    private static String summaryKeyOf(DailyUsageEntity e, UsageSummary.GroupBy groupBy) {
        switch (groupBy) {
            case MODEL:
                return keyOf(e.provider, e.model);
            case PROVIDER:
                return e.provider.name();
            case DAY:
            default:
                return e.day;
        }
    }

    private static String summaryLabelOf(DailyUsageEntity e, UsageSummary.GroupBy groupBy) {
        switch (groupBy) {
            case MODEL:
                return e.model;
            case PROVIDER:
                return e.provider.displayName;
            case DAY:
            default:
                return e.day;
        }
    }

    private static Coverage coverageOf(String from, String to, List<DailyUsageEntity> rows) {
        Coverage c = new Coverage();
        c.from = from;
        c.to = to;
        Set<String> present = new HashSet<>();
        for (DailyUsageEntity e : rows) {
            present.add(e.day);
        }
        c.daysWithData = present.size();
        for (String day : TimeUtils.daysBetween(from, to)) {
            if (!present.contains(day)) {
                c.daysMissing.add(day);
            }
        }
        return c;
    }

    /**
     * 这个区间里用到了哪几版价目表，去重后按版本号排序。
     *
     * <p>跳过 null：null 的意思是「这天没录到价」，不是「一版价目表」。
     * 哪些天没价要靠 {@code DailyUsage.rateVersion} 逐行看，或者看
     * {@link Coverage}——这里只回答「用到过哪几版」。
     */
    private static List<String> rateVersionsOf(List<DailyUsageEntity> rows) {
        Set<String> versions = new LinkedHashSet<>();
        for (DailyUsageEntity e : rows) {
            if (e.rateVersion != null) {
                versions.add(e.rateVersion);
            }
        }
        List<String> sorted = new ArrayList<>(versions);
        Collections.sort(sorted);
        return sorted;
    }

    private static List<DailyUsage> toModelList(List<DailyUsageEntity> rows) {
        List<DailyUsage> out = new ArrayList<>();
        if (rows != null) {
            for (DailyUsageEntity e : rows) {
                out.add(e.toModel());
            }
        }
        return out;
    }

    private static String keyOf(Provider provider, String model) {
        return provider.name() + '\u0000' + model;
    }
}
