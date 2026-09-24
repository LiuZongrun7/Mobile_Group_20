package com.mobilegroup20.tokentrail.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.mobilegroup20.tokentrail.contract.model.PricingRate;
import com.mobilegroup20.tokentrail.contract.model.Provider;
import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import com.mobilegroup20.tokentrail.contract.model.SeasonState;
import com.mobilegroup20.tokentrail.contract.model.TokenBundle;
import com.mobilegroup20.tokentrail.contract.tool.AgentTool;
import com.mobilegroup20.tokentrail.contract.tool.BudgetStatus;
import com.mobilegroup20.tokentrail.contract.tool.CompareResult;
import com.mobilegroup20.tokentrail.contract.tool.Coverage;
import com.mobilegroup20.tokentrail.contract.tool.UsageSummary;

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

    /** 除零保护：空区间的平均值为 0，而不是崩掉。 */
    @Test
    public void averagesGuardAgainstDivisionByZero() {
        UsageSummary.Row row = new UsageSummary.Row();
        assertEquals(0L, row.costPerCallMicros());

        CompareResult.Row compare = new CompareResult.Row();
        assertEquals(0L, compare.costPer1MTokensMicros());
    }

    /** 单次均价和每百万 token 均价是答「哪个模型贵」的两个不同口径。 */
    @Test
    public void averagesAreComputedCorrectly() {
        UsageSummary.Row row = new UsageSummary.Row();
        row.calls = 4;
        row.costMicros = 1_000_000L;
        assertEquals(250_000L, row.costPerCallMicros());

        CompareResult.Row compare = new CompareResult.Row();
        compare.tokens = new TokenBundle(1_000_000L, 0, 0, 0);
        compare.costMicros = 3_000_000L;
        assertEquals(3_000_000L, compare.costPer1MTokensMicros());
    }

    /** 覆盖度：缺了哪天要说得出来，"没记录" 和 "用量为 0" 不能混为一谈。 */
    @Test
    public void coverageReportsMissingDays() {
        Coverage coverage = new Coverage();
        coverage.from = "2026-09-01";
        coverage.to = "2026-09-03";
        coverage.daysWithData = 2;
        coverage.daysMissing.add("2026-09-02");

        assertFalse(coverage.complete());

        coverage.daysMissing.clear();
        assertTrue(coverage.complete());
    }

    /** 结算幂等：同一天判断两次结果必须一样，而且只认「已结算到哪天」。 */
    @Test
    public void settledDayIsNeverSettledTwice() {
        SeasonState state = new SeasonState();
        assertFalse("从没结算过时任何一天都不算已结算", state.isSettled("2026-09-01"));

        state.lastSettledDay = "2026-09-20";
        assertTrue(state.isSettled("2026-09-20"));
        assertTrue(state.isSettled("2026-09-19"));
        assertFalse("还没结算到 21 号", state.isSettled("2026-09-21"));
    }

    /** 三种资源互不通兑：加进去的和取出来的必须对得上，且不会串到别的类型。 */
    @Test
    public void resourceTypesDoNotLeakIntoEachOther() {
        ResourceBalance balance = new ResourceBalance();
        balance.add(ResourceType.INPUT, 100);
        balance.add(ResourceType.CACHE, 7);
        balance.add(ResourceType.OUTPUT, 3);

        assertEquals(100, balance.get(ResourceType.INPUT));
        assertEquals(7, balance.get(ResourceType.CACHE));
        assertEquals(3, balance.get(ResourceType.OUTPUT));
    }

    /** 预算：没设预算和设了 0 元是两回事，措辞和判断都要分得开。 */
    @Test
    public void budgetDistinguishesUnsetFromZeroCap() {
        BudgetStatus unset = new BudgetStatus();
        unset.capMicros = 0;
        unset.configured = false;
        assertFalse(unset.warnThresholdCrossed());
        assertEquals(0.0, unset.spentRatio(), 0.0001);

        BudgetStatus zeroCap = new BudgetStatus();
        zeroCap.capMicros = 0;
        zeroCap.spentMicros = 0;
        zeroCap.configured = true;
        assertFalse(zeroCap.warnThresholdCrossed());

        BudgetStatus over = new BudgetStatus();
        over.capMicros = 20_000_000L;
        over.spentMicros = 17_000_000L;
        over.warnAtRatio = 0.8;
        over.configured = true;
        assertTrue("17/20 已经过了 80% 的线", over.warnThresholdCrossed());
        assertEquals(3_000_000L, over.remainingMicros());

        // 超支时剩余是负数，不要夹到 0——超了多少本身就是要告诉用户的信息
        over.spentMicros = 25_000_000L;
        assertEquals(-5_000_000L, over.remainingMicros());
    }

    /** 服务端按函数名分发工具调用，名字对不上就等于工具不存在。 */
    @Test
    public void agentToolNamesMapBackToEnums() {
        assertEquals(5, AgentTool.values().length);
        for (AgentTool tool : AgentTool.values()) {
            assertEquals(tool, AgentTool.fromFunctionName(tool.functionName));
        }
        assertEquals(null, AgentTool.fromFunctionName("deleteAllData"));
        assertEquals(null, AgentTool.fromFunctionName("GETUSAGESUMMARY"));
    }
}
