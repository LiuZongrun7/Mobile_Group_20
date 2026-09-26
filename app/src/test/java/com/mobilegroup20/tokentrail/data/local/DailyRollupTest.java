package com.mobilegroup20.tokentrail.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.mobilegroup20.tokentrail.contract.model.DailyUsage;
import com.mobilegroup20.tokentrail.contract.model.PricingRate;
import com.mobilegroup20.tokentrail.contract.model.Provider;
import com.mobilegroup20.tokentrail.contract.model.UsageCall;
import com.mobilegroup20.tokentrail.data.Money;
import com.mobilegroup20.tokentrail.data.PricingSource;

/**
 * 滚汇总的规则测试。纯计算，不用模拟器。
 *
 * <p>盯住的是几件最容易写错、错了又不报错的事：样例数据混进真实用量、时区算错天、
 * 分组键少一个维度、把来源给的金额丢掉自己重算一遍、以及查不到价时把成本伪装成 0。
 */
public class DailyRollupTest {

    private static final String UID = "uid-1";

    /** 北京时间 2026-09-26 10:00 的一次调用。 */
    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    private static UsageCall call(String id, Provider provider, String model, String iso,
                                  long input, long cacheRead, long cacheWrite, long output) {
        UsageCall c = new UsageCall();
        c.id = id;
        c.uid = UID;
        c.provider = provider;
        c.model = model;
        c.startedAtEpochMillis = at(iso);
        c.input = input;
        c.cacheRead = cacheRead;
        c.cacheWrite = cacheWrite;
        c.output = output;
        c.source = UsageCall.Source.IMPORTED;
        return c;
    }

    /** 固定单价的价目表：$3 / $0.3 / $3.75 / $15 每 1M。 */
    private static PricingSource flatRate() {
        return (provider, model, day) -> {
            PricingRate r = new PricingRate();
            r.provider = provider;
            r.model = model;
            r.effectiveFrom = "2026-01-01";
            r.rateVersion = "2026-01";
            r.inputMicrosPer1M = 3_000_000L;
            r.cacheReadMicrosPer1M = 300_000L;
            r.cacheWriteMicrosPer1M = 3_750_000L;
            r.outputMicrosPer1M = 15_000_000L;
            return r;
        };
    }

    @Test
    public void emptyInputGivesEmptyList() {
        assertTrue(DailyRollup.rollup(UID, null, flatRate()).isEmpty());
        assertTrue(DailyRollup.rollup(UID, Collections.emptyList(), flatRate()).isEmpty());
    }

    @Test
    public void sampleDataNeverReachesTheRollup() {
        UsageCall sample = call("s1", Provider.OPENAI, "gpt-5",
                "2026-09-26T02:00:00Z", 1_000, 0, 0, 1_000);
        sample.source = UsageCall.Source.SAMPLE;

        assertTrue("样例数据不该滚进日汇总——它不进预算也不换资源",
                DailyRollup.rollup(UID, Collections.singletonList(sample), flatRate()).isEmpty());
    }

    @Test
    public void groupsByDayProviderAndModel() {
        List<UsageCall> calls = Arrays.asList(
                call("a", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 1, 0, 0, 1),
                call("b", Provider.OPENAI, "gpt-5", "2026-09-26T03:00:00Z", 1, 0, 0, 1),
                // 同一家、不同模型 → 另一行
                call("c", Provider.OPENAI, "gpt-5-mini", "2026-09-26T03:00:00Z", 1, 0, 0, 1),
                // 同一模型、不同天 → 另一行
                call("d", Provider.OPENAI, "gpt-5", "2026-09-27T03:00:00Z", 1, 0, 0, 1),
                // 另一家 → 另一行
                call("e", Provider.DEEPSEEK, "deepseek-reasoner", "2026-09-26T03:00:00Z", 1, 0, 0, 1));

        List<DailyUsage> rows = DailyRollup.rollup(UID, calls, flatRate());

        assertEquals(4, rows.size());
        assertEquals(2L, countOf(rows, "2026-09-26", Provider.OPENAI, "gpt-5"));
        assertEquals(1L, countOf(rows, "2026-09-26", Provider.OPENAI, "gpt-5-mini"));
        assertEquals(1L, countOf(rows, "2026-09-27", Provider.OPENAI, "gpt-5"));
        assertEquals(1L, countOf(rows, "2026-09-26", Provider.DEEPSEEK, "deepseek-reasoner"));
    }

    @Test
    public void sumsAllFourTokenBuckets() {
        List<UsageCall> calls = Arrays.asList(
                call("a", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 100, 10, 1, 50),
                call("b", Provider.OPENAI, "gpt-5", "2026-09-26T04:00:00Z", 200, 20, 2, 60));

        DailyUsage row = DailyRollup.rollup(UID, calls, flatRate()).get(0);

        assertEquals(300L, row.input);
        assertEquals(30L, row.cacheRead);
        assertEquals(3L, row.cacheWrite);
        assertEquals(110L, row.output);
        assertEquals(2L, row.calls);
    }

    /**
     * 天是从记录的时刻现算的，不是调用方说了算。
     *
     * <p>2026-09-25T20:00Z 在 UTC 是 25 号，在北京时间已经是 26 号凌晨 4 点。
     * 按 UTC 切天会把它算到 25 号——那正是各家 provider 账单和我们差几个小时的那个
     * 已知误差，但我们自己内部必须统一按 {@code TimeUtils.ZONE} 走。
     */
    @Test
    public void dayIsComputedInShanghaiNotUtc() {
        UsageCall c = call("a", Provider.OPENAI, "gpt-5", "2026-09-25T20:00:00Z", 1, 0, 0, 1);

        List<DailyUsage> rows = DailyRollup.rollup(UID, Collections.singletonList(c), flatRate());

        assertEquals(1, rows.size());
        assertEquals("2026-09-26", rows.get(0).day);
    }

    @Test
    public void costIsComputedFromTheRateOfThatDay() {
        // 涨价：26 号用老价，27 号用新价。补导旧记录必须按旧价算。
        PricingSource pricing = (provider, model, day) -> {
            PricingRate r = new PricingRate();
            r.provider = provider;
            r.model = model;
            if ("2026-09-27".equals(day)) {
                r.effectiveFrom = "2026-09-27";
                r.rateVersion = "2026-09-B";
                r.inputMicrosPer1M = 6_000_000L;
            } else {
                r.effectiveFrom = "2026-01-01";
                r.rateVersion = "2026-09-A";
                r.inputMicrosPer1M = 3_000_000L;
            }
            return r;
        };

        List<UsageCall> calls = Arrays.asList(
                call("a", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 1_000_000, 0, 0, 0),
                call("b", Provider.OPENAI, "gpt-5", "2026-09-27T02:00:00Z", 1_000_000, 0, 0, 0));

        List<DailyUsage> rows = DailyRollup.rollup(UID, calls, pricing);

        assertEquals(2, rows.size());
        assertEquals("2026-09-26", rows.get(0).day);
        assertEquals("老价：1M 输入 × $3", 3_000_000L, rows.get(0).costMicros);
        assertEquals("2026-09-A", rows.get(0).rateVersion);
        assertEquals("2026-09-27", rows.get(1).day);
        assertEquals("新价：1M 输入 × $6", 6_000_000L, rows.get(1).costMicros);
        assertEquals("2026-09-B", rows.get(1).rateVersion);
    }

    /**
     * 查不到价时必须留下痕迹。
     *
     * <p>{@code rateVersion == null} 就是那个痕迹。没有它的话「这个模型还没录价格」
     * 会显示成「花了 0 元」——数字没错，结论是错的，而且没人会发现。
     */
    @Test
    public void unknownModelLeavesCostUncomputableRatherThanFree() {
        UsageCall c = call("a", Provider.MIMO, "mimo-v2.6-pro", "2026-09-26T02:00:00Z", 1_000_000, 0, 0, 0);

        List<DailyUsage> rows = DailyRollup.rollup(UID, Collections.singletonList(c), null);

        assertEquals(1, rows.size());
        assertEquals(0L, rows.get(0).costMicros);
        assertNull("rateVersion 为 null 才是「成本不可信」的信号", rows.get(0).rateVersion);
    }

    @Test
    public void missingRateForOneModelDoesNotAffectTheOther() {
        PricingSource onlyOpenAi = (provider, model, day) -> {
            if (provider != Provider.OPENAI) {
                return null;
            }
            PricingRate r = new PricingRate();
            r.provider = provider;
            r.model = model;
            r.rateVersion = "has-price";
            r.inputMicrosPer1M = 1_000_000L;
            return r;
        };
        List<UsageCall> calls = Arrays.asList(
                call("a", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 1_000_000, 0, 0, 0),
                call("b", Provider.MIMO, "mimo-v2.6-pro", "2026-09-26T02:00:00Z", 1_000_000, 0, 0, 0));

        List<DailyUsage> rows = DailyRollup.rollup(UID, calls, onlyOpenAi);

        assertNotNull(rowOf(rows, Provider.OPENAI).rateVersion);
        assertNull(rowOf(rows, Provider.MIMO).rateVersion);
    }

    /** 上限是「一定不会崩、不会漏算一半」，脏记录不该让整批滚汇总失败。 */
    @Test
    public void junkRowsAreSkippedInsteadOfCrashing() {
        UsageCall noModel = call("a", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 1, 0, 0, 1);
        noModel.model = "";
        UsageCall noTime = call("b", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 1, 0, 0, 1);
        noTime.startedAtEpochMillis = 0L;
        UsageCall noProvider = call("c", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 1, 0, 0, 1);
        noProvider.provider = null;
        UsageCall good = call("d", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 7, 0, 0, 1);

        List<UsageCall> calls = new ArrayList<>(Arrays.asList(null, noModel, noTime, noProvider, good));
        List<DailyUsage> rows = DailyRollup.rollup(UID, calls, flatRate());

        assertEquals(1, rows.size());
        assertEquals("只有那条好的算进去了", 7L, rows.get(0).input);
        assertEquals(1L, rows.get(0).calls);
    }

    /** 顺序固定，界面才不会每次刷新都换一个排法。 */
    @Test
    public void orderIsDayThenProviderThenModel() {
        List<UsageCall> calls = Arrays.asList(
                call("a", Provider.MIMO, "mimo-v2.6-pro", "2026-09-27T02:00:00Z", 1, 0, 0, 1),
                call("b", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 1, 0, 0, 1),
                call("c", Provider.OPENAI, "gpt-5-mini", "2026-09-26T02:00:00Z", 1, 0, 0, 1),
                call("d", Provider.MIMO, "mimo-v2.6-pro", "2026-09-26T02:00:00Z", 1, 0, 0, 1));

        List<DailyUsage> rows = DailyRollup.rollup(UID, calls, flatRate());

        assertEquals("2026-09-26", rows.get(0).day);
        assertEquals(Provider.OPENAI, rows.get(0).provider);
        assertEquals("gpt-5", rows.get(0).model);
        assertEquals("gpt-5-mini", rows.get(1).model);
        assertEquals(Provider.MIMO, rows.get(2).provider);
        assertEquals("2026-09-27", rows.get(3).day);
    }

    @Test
    public void newRowsAreNotSettled() {
        UsageCall c = call("a", Provider.OPENAI, "gpt-5", "2026-09-26T02:00:00Z", 1, 0, 0, 1);
        assertFalse(DailyRollup.rollup(UID, Collections.singletonList(c), flatRate())
                .get(0).settled);
    }

    /** 给一条记录带上「来源直接给的金额」，模拟从账单导进来的记录。 */
    private static UsageCall withSourceCost(UsageCall c, long nativeMicros, String currency) {
        c.nativeCostMicros = nativeMicros;
        c.costCurrency = currency;
        // 导入时已经折成微美元了，滚汇总这一步拿到的就是微美元。
        c.costMicros = Money.toUsdMicros(nativeMicros, currency);
        return c;
    }

    /**
     * 来源自带金额时用它，不要自己乘一遍价目表。
     *
     * <p>这个金额比我们算的准：峰谷价、阶梯价、折扣都在里面，而价目表里没有。
     * 拿价目表的结果覆盖它，等于把最准的那个数换成最粗的那个。
     */
    @Test
    public void sourceProvidedCostBeatsTheRateTable() {
        UsageCall c = withSourceCost(
                call("a", Provider.DEEPSEEK, "deepseek-reasoner", "2026-09-26T02:00:00Z",
                        1_000_000, 0, 0, 0),
                14_080_000L, "CNY");

        DailyUsage row = DailyRollup.rollup(UID, Collections.singletonList(c), flatRate()).get(0);

        // flatRate() 按 $3/1M 会算出 3_000_000，那是错的答案。
        assertEquals(Money.toUsdMicros(14_080_000L, "CNY"), row.costMicros);
        assertFalse("不能是价目表算出来的那个数", row.costMicros == 3_000_000L);
        assertEquals(DailyUsage.RATE_VERSION_FROM_SOURCE, row.rateVersion);
    }

    /**
     * 一组里有多条，金额要加起来，不是取最后一条。
     *
     * <p>加的是<b>已经折过的微美元</b>（每条在导入时各折一次），不是先加人民币再折一次。
     * 两种加法的差最多是每条 1 微美元——即 1e-6 美元，74 条记录全加起来还不到一分钱，
     * 而「所有金额都是估算」这句话本来就盖住了它。选前者是因为
     * {@code usage_call} 表里存的就是折过的值，滚汇总照着同一口径加，屏幕上和库里才对得上。
     */
    @Test
    public void sourceCostIsSummedAcrossTheGroup() {
        List<UsageCall> calls = Arrays.asList(
                withSourceCost(call("a", Provider.DEEPSEEK, "deepseek-chat",
                        "2026-09-26T02:00:00Z", 1_000_000, 0, 0, 0), 1_000_000L, "CNY"),
                withSourceCost(call("b", Provider.DEEPSEEK, "deepseek-chat",
                        "2026-09-26T05:00:00Z", 2_000_000, 0, 0, 0), 2_500_000L, "CNY"));

        DailyUsage row = DailyRollup.rollup(UID, calls, flatRate()).get(0);

        assertEquals("1.00 元 + 2.50 元，各折一次再相加",
                Money.toUsdMicros(1_000_000L, "CNY") + Money.toUsdMicros(2_500_000L, "CNY"),
                row.costMicros);
        assertEquals(DailyUsage.RATE_VERSION_FROM_SOURCE, row.rateVersion);
    }

    /**
     * 价目表空着也能出金额——这正是 DeepSeek 导入后的默认状态。
     *
     * <p>{@code BundledPricingSource} 现在一条价都没有，要是滚汇总只认价目表，
     * 那导完一份带金额的真实账单，屏幕上还是「价格未知」。来源给了就别去查表。
     */
    @Test
    public void sourceCostWorksEvenWithNoRateTableAtAll() {
        UsageCall c = withSourceCost(
                call("a", Provider.DEEPSEEK, "deepseek-reasoner", "2026-09-26T02:00:00Z",
                        1_000_000, 0, 0, 0),
                14_080_000L, "CNY");

        DailyUsage row = DailyRollup.rollup(UID, Collections.singletonList(c), null).get(0);

        assertNotNull("来源给了金额，就不该留成「不可计算」", row.rateVersion);
        assertEquals(DailyUsage.RATE_VERSION_FROM_SOURCE, row.rateVersion);
        assertEquals(Money.toUsdMicros(14_080_000L, "CNY"), row.costMicros);
    }

    /**
     * 一半有一半没有时，整组回落到价目表。
     *
     * <p>不把两种口径加进同一个数：那样得到的金额既不是账单也不是估算，对账时两边
     * 都对不上，而且没有任何字段能说明它是怎么来的。实际上不该出现这种混合——
     * 一份导出要么带金额列要么不带。
     */
    @Test
    public void partiallyCostedGroupFallsBackToTheRateTable() {
        List<UsageCall> calls = Arrays.asList(
                withSourceCost(call("a", Provider.DEEPSEEK, "deepseek-chat",
                        "2026-09-26T02:00:00Z", 1_000_000, 0, 0, 0), 5_000_000L, "CNY"),
                // 这条没有金额
                call("b", Provider.DEEPSEEK, "deepseek-chat",
                        "2026-09-26T05:00:00Z", 1_000_000, 0, 0, 0));

        DailyUsage row = DailyRollup.rollup(UID, calls, flatRate()).get(0);

        assertEquals("整组按价目表算：2M 输入 × $3", 6_000_000L, row.costMicros);
        assertEquals("2026-01", row.rateVersion);
    }

    /** 混合、且价目表也查不到 → 仍然是「不可计算」，不能拿那一半冒充整组。 */
    @Test
    public void partiallyCostedGroupWithNoRateStaysUncomputable() {
        List<UsageCall> calls = Arrays.asList(
                withSourceCost(call("a", Provider.DEEPSEEK, "deepseek-chat",
                        "2026-09-26T02:00:00Z", 1_000_000, 0, 0, 0), 5_000_000L, "CNY"),
                call("b", Provider.DEEPSEEK, "deepseek-chat",
                        "2026-09-26T05:00:00Z", 1_000_000, 0, 0, 0));

        DailyUsage row = DailyRollup.rollup(UID, calls, null).get(0);

        assertNull(row.rateVersion);
        assertEquals(0L, row.costMicros);
    }

    /**
     * 金额真的是 0 和「没有金额」是两回事。
     *
     * <p>0 是一笔金额（比如免费额度用掉的量），它带 {@code "source"} 标记；
     * 「没有金额」是 null，带 {@code null} 标记。两者在界面上长得一样，
     * 但一个能信、一个不能信——所以 {@code costMicros} 是 {@code Long} 不是 {@code long}。
     */
    @Test
    public void aRealZeroCostIsStillMarkedAsFromSource() {
        UsageCall c = withSourceCost(
                call("a", Provider.DEEPSEEK, "deepseek-chat", "2026-09-26T02:00:00Z", 1_000, 0, 0, 0),
                0L, "CNY");

        DailyUsage row = DailyRollup.rollup(UID, Collections.singletonList(c), flatRate()).get(0);

        assertEquals(0L, row.costMicros);
        assertEquals("0 是账单说的 0，不是「查不到价」", DailyUsage.RATE_VERSION_FROM_SOURCE,
                row.rateVersion);
    }

    private static long countOf(List<DailyUsage> rows, String day, Provider provider, String model) {
        for (DailyUsage d : rows) {
            if (d.day.equals(day) && d.provider == provider && d.model.equals(model)) {
                return d.calls;
            }
        }
        throw new AssertionError("没有这一行: " + day + " " + provider + " " + model);
    }

    private static DailyUsage rowOf(List<DailyUsage> rows, Provider provider) {
        for (DailyUsage d : rows) {
            if (d.provider == provider) {
                return d;
            }
        }
        throw new AssertionError("没有这一家: " + provider);
    }
}
