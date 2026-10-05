package com.mobilegroup20.modelpilot.data.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.mobilegroup20.modelpilot.contract.model.PricingRate;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.data.PricingSource;

/**
 * 打包在应用里的价目表。
 *
 * <p><b>表本身是空的，这是有意的。</b>价格必须从各家官网的定价页抄下来，连
 * {@link PricingRate#sourceUrl} 一起填——那是「所有数字都是估算，但你能查回去」
 * 这条承诺的落点。编一个「差不多」的单价放进来，界面上会显示成一个看不出错的
 * 金额，然后整个面板的数字都不可信，而且没有人会发现。所以要加价目，
 * 请照下面那段的格式一条条补，别猜。
 *
 * <p>表空着的时候 {@link #rateFor} 一律返回 null，滚汇总那边会把
 * {@code rateVersion} 留成 null，界面据此显示「这个模型还没录价格」而不是「0 元」。
 *
 * <p><b>加一条价目</b>（照抄官网数字，别换算）：
 * <pre>
 *   add(rate(Provider.DEEPSEEK, "deepseek-reasoner", "2026-09-05", "2026-09",
 *           4_000_000L,   // 输入     $4 / 1M
 *           400_000L,     // 缓存读   $0.4 / 1M
 *           0L,           // 缓存写   DeepSeek 不计缓存写入，填 0
 *           12_000_000L,  // 输出     $12 / 1M
 *           "https://api-docs.deepseek.com/quick_start/pricing"));
 * </pre>
 *
 * <p>长期这份表应该从服务端的 {@code pricing/rates/...} 拉（见
 * {@code CONTRACTS.md} §5），这里的用途是首启和离线时的兜底。
 * 换实现只动 {@code RepositoryProvider} 里接哪一个，滚汇总的代码不用改。
 */
public class BundledPricingSource implements PricingSource {

    /** 按 (提供方, 模型) 分组前先排序，查的时候才能用「最后一版」这个说法。 */
    private final List<PricingRate> rates = new ArrayList<>();

    /**
     * 出厂价目表：**目前只录了 DeepSeek 的两个模型**，数字与链接都来自官方定价页。
     *
     * <p>{@code ProviderRegistry} 里那四个参考价（给 Auto 排序、给界面显示用）和这里是
     * **同一份数字、两个用途**：那边只比大小，这边真的拿去乘 token 算钱。
     * 一处改了另一处也要改——所以两边的 {@code priceSource} 都留了同一个链接。
     *
     * <p><b>为什么现在才填</b>：以前这张表空着，于是账本里每一行的 {@code costMicros}
     * 都是 null（"价格未知"），Insights 也就没有任何金额可算。空表本身没错
     * （不知道就是不知道），但一直空着等于"花了多少钱"这个问题永远答不了。
     *
     * <p>其余五家**故意留空**：没实测过官方定价页就不能写（写了就是一个看不出错的
     * 金额，然后整个面板都不可信）。它们的界面会照实显示"价格未知"。
     */
    public BundledPricingSource() {
        // DeepSeek 官方定价页（人民币标价，这里按官网给出的美元口径记微美元/1M）：
        // 输入（缓存未命中）$0.140845/1M、缓存命中 $0.002816/1M、输出 $0.563380/1M，
        // 缓存写不计费（0）。生效日期取核价那天，改了价就再加一条更晚的。
        add(rate(Provider.DEEPSEEK, "deepseek-chat", DEEPSEEK_EFFECTIVE, DEEPSEEK_VERSION,
                140_845L, 2_816L, 0L, 563_380L, DEEPSEEK_PRICE_URL));
        add(rate(Provider.DEEPSEEK, "deepseek-reasoner", DEEPSEEK_EFFECTIVE, DEEPSEEK_VERSION,
                140_845L, 2_816L, 0L, 563_380L, DEEPSEEK_PRICE_URL));
    }

    /** 这一版 DeepSeek 价目的生效日（含当天）与版本名，两处只写一遍。 */
    private static final String DEEPSEEK_EFFECTIVE = "2026-09-05";
    private static final String DEEPSEEK_VERSION = "2026-09-deepseek";
    private static final String DEEPSEEK_PRICE_URL =
            "https://api-docs.deepseek.com/quick_start/pricing/";

    /** 造一条价目，字段顺序就是 {@link PricingRate} 的顺序。 */
    private static PricingRate rate(Provider provider, String model, String effectiveFrom,
                                    String rateVersion, long input, long cacheRead,
                                    long cacheWrite, long output, String sourceUrl) {
        PricingRate rate = new PricingRate();
        rate.provider = provider;
        rate.model = model;
        rate.effectiveFrom = effectiveFrom;
        rate.rateVersion = rateVersion;
        rate.inputMicrosPer1M = input;
        rate.cacheReadMicrosPer1M = cacheRead;
        rate.cacheWriteMicrosPer1M = cacheWrite;
        rate.outputMicrosPer1M = output;
        rate.sourceUrl = sourceUrl;
        return rate;
    }

    public BundledPricingSource(List<PricingRate> initial) {
        if (initial != null) {
            for (PricingRate r : initial) {
                add(r);
            }
        }
    }

    /** 加一条价目。同一个 (提供方, 模型, effectiveFrom) 重复加会覆盖。 */
    public final void add(PricingRate rate) {
        if (rate == null || rate.provider == null || rate.model == null
                || rate.effectiveFrom == null) {
            throw new IllegalArgumentException(
                    "价目必须写明 provider / model / effectiveFrom，否则查不到它");
        }
        rates.removeIf(existing -> sameKey(existing, rate));
        rates.add(rate);
        rates.sort((a, b) -> {
            int byProvider = a.provider.compareTo(b.provider);
            if (byProvider != 0) {
                return byProvider;
            }
            int byModel = a.model.compareTo(b.model);
            return byModel != 0 ? byModel : a.effectiveFrom.compareTo(b.effectiveFrom);
        });
    }

    /** 现在表里有几条。给测试和排查用。 */
    public int size() {
        return rates.size();
    }

    /** 只读视图，给测试断言用。 */
    public List<PricingRate> all() {
        return Collections.unmodifiableList(rates);
    }

    /**
     * 取 {@code day} 当天生效的那一版：同一个 (提供方, 模型) 里，
     * {@code effectiveFrom <= day} 中最晚的一条。
     *
     * <p>这就是「价格版本边界」那条验收标准的实现：涨价那天之前导入的记录，
     * 重新算出来仍然是旧价。日期是 {@code yyyy-MM-dd} 字符串，可以直接比大小——
     * 这个格式按字典序排就是按时间排，这是选它当存储格式的原因之一。
     */
    @Override
    public PricingRate rateFor(Provider provider, String model, String day) {
        if (provider == null || model == null || day == null) {
            return null;
        }
        PricingRate found = null;
        for (PricingRate r : rates) {
            if (r.provider != provider || !model.equals(r.model)) {
                continue;
            }
            if (r.effectiveFrom.compareTo(day) > 0) {
                continue;
            }
            // 列表已按 effectiveFrom 升序，所以后面遇到的总是更晚的一版。
            found = r;
        }
        return found;
    }

    private static boolean sameKey(PricingRate a, PricingRate b) {
        return a.provider == b.provider
                && a.model.equals(b.model)
                && a.effectiveFrom.equals(b.effectiveFrom);
    }
}
