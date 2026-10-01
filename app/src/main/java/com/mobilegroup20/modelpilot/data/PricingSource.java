package com.mobilegroup20.modelpilot.data;

import com.mobilegroup20.modelpilot.contract.model.PricingRate;
import com.mobilegroup20.modelpilot.contract.model.Provider;

/**
 * 同步的费率查询口。
 *
 * <p><b>为什么不直接用 {@link com.mobilegroup20.modelpilot.data.repository.UsageRepository#rateFor}：</b>
 * 那个方法返回 {@code LiveData}，是给界面订阅用的。而滚 {@code daily_usage} 的时候
 * 是在一个数据库事务里（读原始记录 → 算 → 写汇总），事务里不能有异步回调，
 * 也没必要为一次内部查价搭一套 LiveData。所以内部这条路径要一个同步的口。
 *
 * <p>谁来提供实现：短期的价目表是手抄的常量（{@code data/local/BundledPricingSource}），
 * 长期是服务端那张 {@code pricing/rates/...}。换的时候只动
 * {@code RepositoryProvider} 里接哪一个是，滚汇总的代码一行不用改。
 */
public interface PricingSource {

    /**
     * 某个模型在某天生效的费率。
     *
     * <p><b>查不到返回 null，不要返回一个全 0 的费率。</b>「没查到这个模型的价格」和
     * 「这个模型免费」是两件事，而返回全 0 会把前者伪装成后者——账面上什么都对，
     * 只是少算了一笔钱，没有任何提示。
     */
    PricingRate rateFor(Provider provider, String model, String day);
}
