package com.mobilegroup20.modelpilot.data.local;

import android.content.Context;

import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.contract.model.PricingRate;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.data.PricingSource;
import com.mobilegroup20.modelpilot.data.ProviderKeys;

/**
 * 价目表的第一层：**用户自己填的价**（自定义端点），查不到再落到打包的官方价目。
 *
 * <p>为什么需要它：中转站/自建反代没有我们能核对的官方定价页，于是账本里每一行都是
 * "价格未知"。用户可以自己填（他知道自己付了多少钱一 token），填了我们就按它算钱。
 *
 * <p><b>三条口径</b>：
 * <ol>
 *   <li><b>没填就还是"未知"</b>——绝不拿别家的价顶替。那会得到一个看不出错的金额，
 *       正是 `CONTRACTS.md` §4 点名的、最难发现的一类错。</li>
 *   <li><b>输入与输出是必须的</b>（和 `ModelSpec.priced()` 一个判据）：只有一条也敢算钱，
 *       乘出来的数会离谱。缓存读/写没填按 0 计——很多家根本不计缓存写入。</li>
 *   <li><b>生效日期往前一律适用</b>（`1970-01-01`）：用户没被要求填生效日期，
 *       他填的就是"这家现在这个价"。费率版本写成 `user-entered` 留痕，
 *       改价就让他重新填一次——比我们替他猜一个历史版本诚实。</li>
 * </ol>
 *
 * <p>已知的边界：查价是按**模型 id** 查的（账本里自定义端点都记成 `Provider.CUSTOM`，
 * 原始 providerId 没进去）。两个自定义端点如果用了同一个模型名，会共用同一套价——
 * 真遇到这种情况，加一条注释说明或者换一个模型名更清楚。
 */
public final class UserPricingSource implements PricingSource {

    /** 用户填的价没有"什么时候开始生效"的概念，所以往前一律适用。 */
    private static final String ALWAYS = "1970-01-01";

    private final Context context;
    private final PricingSource fallback;

    public UserPricingSource(Context context, PricingSource fallback) {
        this.context = context.getApplicationContext();
        this.fallback = fallback;
    }

    @Override
    public PricingRate rateFor(Provider provider, String model, String day) {
        if (provider == Provider.CUSTOM && model != null) {
            for (ProviderSpec spec : ProviderKeys.customProviders(context)) {
                ModelSpec spec2 = spec.model(model);
                if (spec2 != null && spec2.inputMicros != null && spec2.outputMicros != null) {
                    return rate(spec2);
                }
            }
        }
        return fallback.rateFor(provider, model, day);
    }

    private static PricingRate rate(ModelSpec model) {
        PricingRate rate = new PricingRate();
        rate.provider = Provider.CUSTOM;
        rate.model = model.modelId;
        rate.effectiveFrom = ALWAYS;
        rate.rateVersion = "user-entered";
        rate.inputMicrosPer1M = model.inputMicros;
        rate.cacheReadMicrosPer1M = model.cacheReadMicros == null ? 0L : model.cacheReadMicros;
        rate.cacheWriteMicrosPer1M = model.cacheWriteMicros == null ? 0L : model.cacheWriteMicros;
        rate.outputMicrosPer1M = model.outputMicros;
        // 来源留痕：用户填定价页链接就点得回去，没填就写明是"用户填的"。
        rate.sourceUrl = model.priceSource;
        return rate;
    }
}
