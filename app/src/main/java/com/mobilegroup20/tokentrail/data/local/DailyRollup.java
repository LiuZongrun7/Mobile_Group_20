package com.mobilegroup20.tokentrail.data.local;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mobilegroup20.tokentrail.contract.model.DailyUsage;
import com.mobilegroup20.tokentrail.contract.model.PricingRate;
import com.mobilegroup20.tokentrail.contract.model.TokenBundle;
import com.mobilegroup20.tokentrail.contract.model.UsageCall;
import com.mobilegroup20.tokentrail.data.PricingSource;
import com.mobilegroup20.tokentrail.util.TimeUtils;

/**
 * 把原始调用记录滚成 {@code (uid, 天, 提供方, 模型)} 的日汇总。
 *
 * <p><b>这是纯计算，不碰数据库、不碰 Android。</b>所以它能用 JUnit 直接跑
 * （{@code DailyRollupTest}），不用模拟器——滚汇总里最容易写错的是分桶和边界，
 * 而那些恰好是不需要真数据库就能测的。DAO 只负责把行搬进搬出。
 *
 * <p>三条规则，都在这个文件里、只有这一处：
 * <ol>
 *   <li><b>只有 {@link UsageCall.Source#IMPORTED} 进汇总。</b>演示用的样例数据
 *       （{@code SAMPLE}）不进预算、也不换游戏资源，这是大纲 Alpha 验收项要求的那条分界线；</li>
 *   <li>分组键是「天 + 提供方 + 模型」。<b>天是从 {@code startedAtEpochMillis}
 *       现算的</b>，不是调用方传进来的——那样两个人传不同的天就会得到不同的结果；</li>
 *   <li>成本优先用<b>来源自带的金额</b>，来源没给才按那一组<b>所在那天</b>生效的费率算。
 *       所以补导一条去年的记录，算出来的仍然是去年的价。两条路的取舍见
 *       {@link #applyCost}。</li>
 * </ol>
 */
public final class DailyRollup {

    private DailyRollup() {
    }

    /**
     * @param uid     账号，写进每一行
     * @param calls   原始记录，可以是任意时间跨度的混合
     * @param pricing 查价的口；传 null 等价于「哪儿都查不到价」，用于不关心成本的测试
     * @return 按天、提供方、模型分组后的汇总行；顺序固定（天 → 提供方 → 模型），
     *         方便断言和让界面稳定
     */
    public static List<DailyUsage> rollup(String uid, List<UsageCall> calls, PricingSource pricing) {
        List<DailyUsage> out = new ArrayList<>();
        if (calls == null || calls.isEmpty()) {
            return out;
        }

        // LinkedHashMap 只是为了先保持插入顺序，最后的顺序由下面的 sort 决定。
        Map<String, Group> grouped = new LinkedHashMap<>();
        for (UsageCall call : calls) {
            if (!countsTowardUsage(call)) {
                continue;
            }
            String day = TimeUtils.dayOf(call.startedAtEpochMillis);
            String key = day + '\u0000' + call.provider.name() + '\u0000' + call.model;

            Group group = grouped.get(key);
            if (group == null) {
                DailyUsage row = new DailyUsage();
                row.uid = uid;
                row.day = day;
                row.provider = call.provider;
                row.model = call.model;
                row.settled = false;
                group = new Group(row);
                grouped.put(key, group);
            }

            DailyUsage row = group.row;
            row.calls++;
            row.input += call.input;
            row.cacheRead += call.cacheRead;
            row.cacheWrite += call.cacheWrite;
            row.output += call.output;

            group.rows++;
            if (call.costMicros != null) {
                group.rowsWithCost++;
                group.costMicros += call.costMicros;
                if (group.currency == null) {
                    group.currency = call.costCurrency;
                }
            }
        }

        for (Group group : grouped.values()) {
            applyCost(group, pricing);
            out.add(group.row);
        }
        out.sort(Comparator
                .comparing((DailyUsage d) -> d.day)
                .thenComparingInt(d -> d.provider == null ? 0 : d.provider.ordinal())
                .thenComparing(d -> d.model));
        return out;
    }

    /** 一个 (天, 提供方, 模型) 分组的累加过程，滚完就丢掉。 */
    private static final class Group {

        final DailyUsage row;

        /** 落进这一组的记录条数，以及其中自带金额的有几条。 */
        int rows;
        int rowsWithCost;

        /** 自带金额的和，单位微美元——导入时已经折过了。 */
        long costMicros;

        /** 这一组账单的原始币种，只为留痕。 */
        String currency;

        Group(DailyUsage row) {
            this.row = row;
        }
    }

    /**
     * 这条记录该不该计入用量。
     *
     * <p>被排除的记录<b>不是错误</b>，所以这里不抛异常也不计数：{@code SAMPLE} 是
     * 有意造出来的演示数据，缺字段的脏记录在它进库之前就该被
     * {@link UsageCallEntity#fromModel} 挡掉并计进 {@code ImportResult.rejected}。
     * 走到这里还缺字段的说明是绕过入库直接调过来的，那种情况宁可漏算也不要崩。
     */
    private static boolean countsTowardUsage(UsageCall call) {
        if (call == null || call.provider == null || call.model == null || call.model.isEmpty()) {
            return false;
        }
        if (call.startedAtEpochMillis <= 0L) {
            return false;
        }
        return call.source == UsageCall.Source.IMPORTED;
    }

    /**
     * 算这一组的成本：<b>来源自带金额优先，查价表兜底。</b>
     *
     * <p>为什么要分两条路。DeepSeek 的用量导出自带金额，OpenAI 也有
     * {@code /v1/organization/costs} 直接给钱数。这个金额比自己乘一遍价目表准：
     * 它把峰谷价、阶梯价、折扣这些我们根本没录的东西都算进去了。所以只要来源给了，
     * 就用来源的，并打上 {@link DailyUsage#RATE_VERSION_FROM_SOURCE}——那个标记
     * 的作用是让后面能分辨「这个数是账单给的」还是「这个数是我们估的」。
     *
     * <p><b>只有整组都自带金额时才走来源。</b>一半有一半没有的时候整组回落到价目表，
     * 不把两种口径加进同一个数：那样得到的金额既不是账单也不是估算，对账时对不上
     * 任何一边，而且没有任何字段能说明它是怎么来的。实际上不会出现这种混合——
     * 一份导出要么带金额列要么不带。
     *
     * <p><b>两条路都拿不到时，把 {@code rateVersion} 留成 null，这就是「这行的成本
     * 不可信」的信号。</b>不去编一个默认单价，也不把成本伪装成 0：界面和 agent
     * 必须先看 {@code rateVersion} 再决定要不要显示金额，否则「这个模型还没录价格」
     * 会直接显示成「花了 0 元」——数字看着没错，结论是错的。
     */
    private static void applyCost(Group group, PricingSource pricing) {
        DailyUsage row = group.row;

        if (group.rows > 0 && group.rowsWithCost == group.rows) {
            row.costMicros = group.costMicros;
            row.rateVersion = DailyUsage.RATE_VERSION_FROM_SOURCE;
            return;
        }

        PricingRate rate = pricing == null
                ? null
                : pricing.rateFor(row.provider, row.model, row.day);
        if (rate == null) {
            row.costMicros = 0L;
            row.rateVersion = null;
            return;
        }
        row.costMicros = rate.costMicros(TokenBundle.from(row));
        row.rateVersion = rate.rateVersion;
    }
}
