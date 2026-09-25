package com.mobilegroup20.tokentrail.data.stub;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

import com.mobilegroup20.tokentrail.contract.model.DailyUsage;
import com.mobilegroup20.tokentrail.contract.model.PricingRate;
import com.mobilegroup20.tokentrail.contract.model.Provider;
import com.mobilegroup20.tokentrail.contract.model.TokenBundle;
import com.mobilegroup20.tokentrail.contract.model.UsageCall;
import com.mobilegroup20.tokentrail.contract.tool.CompareResult;
import com.mobilegroup20.tokentrail.contract.tool.Coverage;
import com.mobilegroup20.tokentrail.contract.tool.UsageSummary;
import com.mobilegroup20.tokentrail.data.repository.UsageRepository;
import com.mobilegroup20.tokentrail.util.TimeUtils;

/**
 * {@link UsageRepository} 的桩实现：返回编出来的样例数据。
 *
 * <p><b>它的作用不是「占个位」，而是让游戏和 agent 在数据侧完工之前就能开发。</b>
 * 有了它，塔防的资源换算、赔付曲线、图表渲染今天就能调，不用等张莉把日志导入做完。
 *
 * <p>几个约定：
 * <ul>
 *   <li>数字是<b>按日期算出来的固定值</b>，同一天每次跑都一样。用随机数的话，
 *       界面每次刷新都在跳，没法判断是不是自己写错了。</li>
 *   <li>所有汇总行的 {@code rateVersion} 都是 {@link #RATE_VERSION}，
 *       界面上能一眼认出这是假数据。</li>
 *   <li>真实现接好之后，把 {@code RepositoryProvider} 里的分支切过去，
 *       这个类就可以删了——它不该出现在提交版本里。</li>
 * </ul>
 */
public class StubUsageRepository implements UsageRepository {

    /** 桩数据的版本标记。看到界面上出现这个字符串，就说明还在用桩。 */
    public static final String RATE_VERSION = "STUB";

    @Override
    public LiveData<ImportResult> importCalls(String uid, List<UsageCall> calls) {
        // 桩不落库。如实报成「全部被拒」，而不是假装成功——假装成功会让人以为导入通了。
        int n = calls == null ? 0 : calls.size();
        return live(new ImportResult(0, 0, n));
    }

    @Override
    public LiveData<List<DailyUsage>> dailyUsageFor(String uid, String day) {
        return live(sampleDay(uid, day));
    }

    @Override
    public LiveData<List<DailyUsage>> dailyUsageIn(String uid, String from, String to) {
        List<DailyUsage> all = new ArrayList<>();
        for (String day : TimeUtils.daysBetween(from, to)) {
            all.addAll(sampleDay(uid, day));
        }
        return live(all);
    }

    @Override
    public LiveData<TokenBundle> monthTokens(String uid, String month) {
        TokenBundle sum = new TokenBundle();
        String from = TimeUtils.firstDayOfMonth(month);
        // 上界是「月底」和「昨天」里更早的那个。原来直接写 yesterday()，
        // 查当月时凑巧对，查过去的月份就会把后面几个月的数据一起算进来。
        String to = TimeUtils.clampToYesterday(TimeUtils.lastDayOfMonth(month));
        if (to == null) {
            return live(sum);
        }
        for (String day : TimeUtils.daysBetween(from, to)) {
            for (DailyUsage d : sampleDay(uid, day)) {
                sum.add(TokenBundle.from(d));
            }
        }
        return live(sum);
    }

    @Override
    public LiveData<UsageSummary> summary(String uid, String from, String to,
                                          UsageSummary.GroupBy groupBy) {
        UsageSummary out = new UsageSummary();
        out.groupBy = groupBy;
        out.coverage = coverageOf(from, to);
        out.rateVersions.add(RATE_VERSION);

        // 桩实现只做「按天」这一种分组，另外两种留给真实现——反正调用方看到的
        // 是同一个 UsageSummary，换实现不影响上层。
        for (String day : TimeUtils.daysBetween(from, to)) {
            UsageSummary.Row row = new UsageSummary.Row();
            row.key = day;
            for (DailyUsage d : sampleDay(uid, day)) {
                row.tokens.add(TokenBundle.from(d));
                row.calls += d.calls;
                row.costMicros += d.costMicros;
            }
            row.rateVersion = RATE_VERSION;
            out.totals.add(row.tokens);
            out.rows.add(row);
        }
        return live(out);
    }

    @Override
    public LiveData<CompareResult> compare(String uid, String from, String to,
                                           CompareResult.Metric metric) {
        CompareResult out = new CompareResult();
        out.from = from;
        out.to = to;
        out.metric = metric;
        out.coverage = coverageOf(from, to);
        out.rateVersions.add(RATE_VERSION);

        for (Provider provider : Provider.values()) {
            CompareResult.Row row = new CompareResult.Row();
            row.provider = provider;
            row.model = modelOf(provider);
            for (String day : TimeUtils.daysBetween(from, to)) {
                for (DailyUsage d : sampleDay(uid, day)) {
                    if (d.provider != provider) {
                        continue;
                    }
                    row.tokens.add(TokenBundle.from(d));
                    row.calls += d.calls;
                    row.costMicros += d.costMicros;
                }
            }
            out.rows.add(row);
        }
        return live(out);
    }

    @Override
    public LiveData<PricingRate> rateFor(Provider provider, String model, String day) {
        PricingRate rate = new PricingRate();
        rate.provider = provider;
        rate.model = model;
        rate.effectiveFrom = "2026-01-01";
        rate.rateVersion = RATE_VERSION;
        rate.sourceUrl = "https://example.invalid/stub";
        // 编出来的单价：输入 $3/1M、缓存读 $0.3/1M、缓存写 $3.75/1M、输出 $15/1M
        rate.inputMicrosPer1M = 3_000_000L;
        rate.cacheReadMicrosPer1M = 300_000L;
        rate.cacheWriteMicrosPer1M = 3_750_000L;
        rate.outputMicrosPer1M = 15_000_000L;
        return live(rate);
    }

    // ---------------------------------------------------------------- 造数据

    /** 固定算法，保证同一天的数字稳定可复现。别改成随机数。 */
    private static long mix(String day, int salt) {
        int h = (day + '#' + salt).hashCode();
        return Math.abs(h % 1_000L);
    }

    /** 一天编出三家各一行，token 量在真实量级附近（十 k 到百 k）。 */
    static List<DailyUsage> sampleDay(String uid, String day) {
        List<DailyUsage> rows = new ArrayList<>();
        Provider[] providers = Provider.values();
        for (int i = 0; i < providers.length; i++) {
            Provider provider = providers[i];
            DailyUsage d = new DailyUsage();
            d.uid = uid;
            d.day = day;
            d.provider = provider;
            d.model = modelOf(provider);
            d.calls = 8 + mix(day, i * 7 + 1) % 40;
            d.input = 40_000 + mix(day, i * 7 + 2) * 60;
            d.cacheRead = 20_000 + mix(day, i * 7 + 3) * 45;
            d.cacheWrite = 1_000 + mix(day, i * 7 + 4) * 6;
            d.output = 5_000 + mix(day, i * 7 + 5) * 15;
            d.costMicros = 200_000 + mix(day, i * 7 + 6) * 900;
            d.rateVersion = RATE_VERSION;
            d.settled = false;
            rows.add(d);
        }
        return rows;
    }

    private static String modelOf(Provider provider) {
        switch (provider) {
            case OPENAI:
                return "gpt-5";
            case GLM:
                return "glm-4.6";
            case DEEPSEEK:
                return "deepseek-reasoner";
            default:
                throw new IllegalArgumentException("未知提供方: " + provider);
        }
    }

    /** 桩的数据总是「每天都有」，所以缺失列表是空的。真实现要如实填。 */
    private static Coverage coverageOf(String from, String to) {
        Coverage c = new Coverage();
        c.from = from;
        c.to = to;
        List<String> days = TimeUtils.daysBetween(from, to);
        c.daysWithData = days.size();
        return c;
    }

    private static <T> LiveData<T> live(T value) {
        MutableLiveData<T> data = new MutableLiveData<>();
        data.postValue(value);
        return data;
    }
}
