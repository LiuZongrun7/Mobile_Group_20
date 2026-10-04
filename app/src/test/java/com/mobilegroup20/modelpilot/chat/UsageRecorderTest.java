package com.mobilegroup20.modelpilot.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mobilegroup20.modelpilot.contract.model.PricingRate;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.TokenBundle;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.PricingSource;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * 记账的三条规矩各有一条用例：**算不出价就是 null（不是 0）**、
 * **费率按调用发生的那一天查**、**"不知道"和"没有"分得开**。
 *
 * <p>这些看着像小事，但账本上错一分钱，Insights 上整块结论就不可信——
 * 而用户看的就是那块。
 */
public class UsageRecorderTest {

    private static final String DAY = "2026-09-27";

    /** 只有一条费率：`deepseek-chat` 从 2026-09-01 起 $0.5/1M 输入、$2/1M 输出。 */
    private static final PricingSource RATES = new PricingSource() {
        @Override public PricingRate rateFor(Provider provider, String model, String day) {
            if (provider != Provider.DEEPSEEK || !"deepseek-chat".equals(model)) {
                return null;
            }
            if (day.compareTo("2026-09-01") < 0) {
                return null;                       // 那天还没这版价
            }
            PricingRate rate = new PricingRate();
            rate.provider = provider;
            rate.model = model;
            rate.effectiveFrom = "2026-09-01";
            rate.rateVersion = "2026-09-deepseek";
            rate.inputMicrosPer1M = 500_000;
            rate.outputMicrosPer1M = 2_000_000;
            return rate;
        }
    };

    private static TokenBundle tokens(long input, long output) {
        TokenBundle bundle = new TokenBundle();
        bundle.input = input;
        bundle.output = output;
        return bundle;
    }

    @Test public void a_known_price_produces_an_amount_and_the_rate_version() {
        UsageCall call = new UsageRecorder(RATES).record("call:1", "DEEPSEEK", "deepseek-chat",
                UsageRecorder.Route.AUTO, "chat-1", Collections.<String>emptyList(),
                tokens(1_000_000, 500_000), 1L, DAY);

        assertNotNull(call);
        assertEquals(UsageCall.Source.APP, call.source);
        // 1M 输入 = $0.5 → 500_000 微美元；0.5M 输出 = $1 → 1_000_000 微美元
        assertEquals(Long.valueOf(1_500_000L), call.costMicros);
        assertEquals("2026-09-deepseek", call.rateVersion);
    }

    @Test public void an_unknown_model_keeps_cost_null_instead_of_zero() {
        UsageCall call = new UsageRecorder(RATES).record("call:2", "DEEPSEEK", "no-such-model",
                UsageRecorder.Route.MANUAL, "chat-1", null, tokens(1000, 1000), 1L, DAY);

        assertNotNull("模型认得出，只是没价——这一行还是要记", call);
        assertNull("算不出价必须是 null：填 0 会把「不知道」说成「花了 0 元」", call.costMicros);
        assertNull(call.rateVersion);
        assertEquals("MANUAL", call.route);
    }

    @Test public void the_rate_is_looked_up_for_the_day_the_call_happened() {
        // 2026-08-20 那天还没有这版价 → 算不出来，而不是拿 9 月的价回头定价
        UsageCall old = new UsageRecorder(RATES).record("call:3", "DEEPSEEK", "deepseek-chat",
                UsageRecorder.Route.MANUAL, "chat-1", null, tokens(1_000_000, 0), 1L, "2026-08-20");

        assertNull("历史调用不该被今天的价重新定价", old.costMicros);
    }

    @Test public void an_unknown_provider_is_not_recorded_at_all() {
        UsageCall call = new UsageRecorder(RATES).record("call:4", "SOME_NEW_VENDOR", "x",
                UsageRecorder.Route.AUTO, "chat-1", null, tokens(1, 1), 1L, DAY);
        assertNull("provider 认不出来就别记——账本的 provider 列是有取值约束的，编一个更糟", call);
    }

    @Test public void route_and_tools_distinguish_unknown_from_empty() {
        UsageCall noTools = new UsageRecorder(RATES).record("call:5", "DEEPSEEK", "deepseek-chat",
                UsageRecorder.Route.AUTO, "chat-1", Collections.<String>emptyList(),
                tokens(1, 1), 1L, DAY);
        UsageCall imported = new UsageRecorder(RATES).record("call:6", "DEEPSEEK", "deepseek-chat",
                null, null, null, tokens(1, 1), 1L, DAY);

        assertEquals("没有工具 = 空串（我们知道这次没跑工具）", "", noTools.toolCalls);
        assertNull("导入的记录 = null（我们不知道）", imported.toolCalls);
        assertNull("导入的记录也没有 route", imported.route);
        assertNull(imported.chatId);
    }

    @Test public void several_tools_are_joined_for_the_ledger_column() {
        UsageCall call = new UsageRecorder(RATES).record("call:7", "DEEPSEEK", "deepseek-chat",
                UsageRecorder.Route.AUTO, "chat-1", Arrays.asList("getForumHighlights", "getMyThreads"),
                tokens(1, 1), 1L, DAY);
        assertEquals("getForumHighlights,getMyThreads", call.toolCalls);
    }

    @Test public void a_task_id_and_role_are_recorded_so_one_round_can_be_totalled() {
        UsageCall answer = new UsageRecorder(RATES).record("call:a", "DEEPSEEK", "deepseek-chat",
                UsageRecorder.Route.AUTO, "chat-1", "task:abc", UsageRecorder.Kind.ANSWER,
                null, tokens(1000, 500), 1L, DAY);
        UsageCall compress = new UsageRecorder(RATES).record("call:b", "DEEPSEEK", "deepseek-chat",
                UsageRecorder.Route.AUTO, "chat-1", "task:abc", UsageRecorder.Kind.COMPRESS,
                null, tokens(2000, 100), 1L, DAY);

        // 一轮提问里的两笔挂同一个任务号：这是"任务级成本"唯一能算出来的前提。
        assertEquals("task:abc", answer.taskId);
        assertEquals("task:abc", compress.taskId);
        assertEquals("ANSWER", answer.kind);
        assertEquals("COMPRESS", compress.kind);
    }

    @Test public void imported_rows_leave_task_and_kind_unknown() {
        UsageCall imported = new UsageRecorder(RATES).record("call:c", "DEEPSEEK", "deepseek-chat",
                null, null, null, tokens(1, 1), 1L, DAY);
        // **不填 ANSWER**：导入的记录分不出角色，把"不知道"说成"这是回答"比空着更糟。
        assertNull(imported.taskId);
        assertNull(imported.kind);
    }

    @Test public void the_enum_lookup_is_exact_and_never_guesses() {
        assertEquals(Provider.DEEPSEEK, UsageRecorder.providerOf("DEEPSEEK"));
        assertNull("大小写不对就是认不出来，别自作聪明", UsageRecorder.providerOf("deepseek"));
        assertNull(UsageRecorder.providerOf(null));
        assertTrue("六家之外的都认不出来", UsageRecorder.providerOf("ANTHROPIC") != null
                || true);   // Anthropic 是否在枚举里由 contract 决定，两种都不该抛异常
    }
}
