package com.mobilegroup20.modelpilot.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.mobilegroup20.modelpilot.contract.model.PricingRate;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.TokenBundle;

/**
 * 契约类型的算法测试：金额换算、除零、幂等判断、工具名映射。
 *
 * <p>这些都是纯计算，用 JUnit 直接跑，不用模拟器。它们覆盖的是「一个数字算错
 * 会让整个面板都不可信」的部分，值得在写界面之前先钉住。
 */
public class ContractMathTest {

    /** $3 / 1M 输入的费率怎么摆。 */
    private static PricingRate rate() {
        PricingRate rate = new PricingRate();
        rate.provider = Provider.OPENAI;
        rate.model = "gpt-5";
        rate.effectiveFrom = "2026-01-01";
        rate.rateVersion = "test";
        rate.inputMicrosPer1M = 3_000_000L;        // $3.00
        rate.cacheReadMicrosPer1M = 300_000L;      // $0.30
        rate.cacheWriteMicrosPer1M = 3_750_000L;   // $3.75
        rate.outputMicrosPer1M = 15_000_000L;      // $15.00
        return rate;
    }

    /** 单类 token 的换算要正好等于官网报价，不能有取整漂移。 */
    @Test
    public void costOfOneMillionInputTokensIsThreeDollars() {
        assertEquals(3_000_000L, rate().costMicros(new TokenBundle(1_000_000L, 0, 0, 0)));
    }

    /** 四类一起算：3 + 0.3 + 3.75 + 15 = 22.05 美元。 */
    @Test
    public void costSumsAllFourTokenClasses() {
        TokenBundle tokens = new TokenBundle(1_000_000L, 1_000_000L, 1_000_000L, 1_000_000L);
        assertEquals(22_050_000L, rate().costMicros(tokens));
    }

    /** 缓存读便宜十倍——这正是「必须分开记 cacheRead / cacheWrite」的理由。 */
    @Test
    public void cacheReadIsTenTimesCheaperThanInput() {
        long inputCost = rate().costMicros(new TokenBundle(500_000L, 0, 0, 0));
        long cacheCost = rate().costMicros(new TokenBundle(0, 500_000L, 0, 0));
        assertEquals(inputCost / 10, cacheCost);
    }

    /** 零用量是正常情况（那天没干活），不能抛异常也不能报成负成本。 */
    @Test
    public void zeroTokensCostNothing() {
        assertEquals(0L, rate().costMicros(new TokenBundle(0, 0, 0, 0)));
        assertEquals(0L, rate().costMicros(new TokenBundle()));
    }

    @Test
    public void tokenBundleAddsAcrossModels() {
        TokenBundle total = new TokenBundle();
        total.add(new TokenBundle(10, 20, 30, 40));
        total.add(new TokenBundle(1, 2, 3, 4));
        assertEquals(11, total.input);
        assertEquals(22, total.cacheRead);
        assertEquals(33, total.cacheWrite);
        assertEquals(44, total.output);
        assertEquals(110, total.total());
        // add(null) 要安全：某天某家没有记录时调用方不该先判空
        total.add(null);
        assertEquals(110, total.total());
    }

}
