package com.mobilegroup20.modelpilot.ui.insights;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import org.junit.Test;

import java.util.Arrays;

/**
 * 账本滚动的那点算术。这几条都是大纲 §9 点名的"正确性"项：
 * 缓存不许重复计、缺的用量不许变成 0、压缩与回答要分得开。
 */
public class InsightsTest {

    @Test public void tokens_add_all_four_buckets() {
        // input 只装未命中缓存的那段（CONTRACTS §8），只加它会少算缓存那两块。
        Insights.Summary summary = Insights.summarize(Arrays.asList(
                call(Provider.DEEPSEEK, "deepseek-chat", 100, 20, 5, 30, 500L, "ANSWER", "AUTO")));
        assertEquals(155, summary.tokens);
        assertEquals(100, summary.input);
        assertEquals(20, summary.cacheRead);
        assertEquals(5, summary.cacheWrite);
        assertEquals(30, summary.output);
    }

    @Test public void a_missing_price_is_counted_as_unknown_not_zero() {
        Insights.Summary summary = Insights.summarize(Arrays.asList(
                call(Provider.DEEPSEEK, "deepseek-chat", 1, 0, 0, 1, 500L, "ANSWER", "AUTO"),
                call(Provider.DEEPSEEK, "deepseek-chat", 1, 0, 0, 1, null, "ANSWER", "AUTO")));

        assertEquals("算得出价的那次才算进金额", 500L, summary.costMicros);
        assertEquals("另一次要单独计数——否则这个金额看起来像全部", 1L, summary.unknownCostCalls);
    }

    @Test public void auto_share_counts_answers_only() {
        Insights.Summary summary = Insights.summarize(Arrays.asList(
                call(Provider.DEEPSEEK, "a", 1, 0, 0, 1, null, "ANSWER", "AUTO"),
                call(Provider.DEEPSEEK, "a", 1, 0, 0, 1, null, "ANSWER", "MANUAL"),
                // 压缩是系统行为，不该稀释"用户有多少次交给 Auto 决定"。
                call(Provider.DEEPSEEK, "a", 1, 0, 0, 1, null, "COMPRESS", "AUTO")));

        assertEquals(2, summary.answerCalls);
        assertEquals(1, summary.compressCalls);
        assertEquals(Integer.valueOf(50), summary.autoPercent());
    }

    @Test public void no_answers_means_unknown_share_not_zero_percent() {
        // "一次都没问过"和"问了但一次都没用 Auto"是两件事。
        assertNull(Insights.summarize(Arrays.asList(
                call(Provider.DEEPSEEK, "a", 1, 0, 0, 1, null, "COMPRESS", "AUTO"))).autoPercent());
        assertNull(Insights.summarize(java.util.Collections.<UsageCallEntity>emptyList())
                .autoPercent());
    }

    @Test public void buckets_group_by_provider_and_by_model() {
        Insights.Summary summary = Insights.summarize(Arrays.asList(
                call(Provider.DEEPSEEK, "deepseek-chat", 1, 0, 0, 1, 100L, "ANSWER", "AUTO"),
                call(Provider.DEEPSEEK, "deepseek-reasoner", 1, 0, 0, 1, 300L, "ANSWER", "AUTO"),
                call(Provider.CUSTOM, "my-model", 1, 0, 0, 1, null, "ANSWER", "MANUAL")));

        assertEquals(2, summary.providers.size());
        assertEquals("花的钱多的排前面", "DeepSeek", summary.providers.get(0).label);
        assertEquals("Custom endpoint", summary.providers.get(1).label);
        assertEquals(2, summary.providers.get(0).calls);
        assertEquals(3, summary.models.size());
    }

    private static UsageCallEntity call(Provider provider, String model, long input, long cacheRead,
                                        long cacheWrite, long output, Long costMicros,
                                        String kind, String route) {
        UsageCallEntity entity = new UsageCallEntity();
        entity.id = "call:" + provider + ":" + model + ":" + kind + ":" + costMicros;
        entity.uid = "local";
        entity.provider = provider;
        entity.model = model;
        entity.input = input;
        entity.cacheRead = cacheRead;
        entity.cacheWrite = cacheWrite;
        entity.output = output;
        entity.costMicros = costMicros;
        entity.kind = kind;
        entity.route = route;
        entity.source = com.mobilegroup20.modelpilot.contract.model.UsageCall.Source.APP;
        return entity;
    }
}
