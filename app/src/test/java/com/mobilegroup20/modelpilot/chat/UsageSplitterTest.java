package com.mobilegroup20.modelpilot.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.mobilegroup20.modelpilot.contract.model.TokenBundle;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 四桶归一化的测试。**这一条和服务端 `usage.py` 里那份是同一套口径**，
 * 两边分开实现（一边 Java 一边 Python），所以两边都要有测试盯着同一个口径。
 *
 * <p>最容易错的地方是 `input` 的定义：它只装**未命中缓存**的那一段。
 * 两种字段形状（OpenAI 的 `prompt_tokens` 是总量、Anthropic 的 `input_tokens`
 * 已经是未命中部分）混起来，缓存那部分就会被算两遍钱——而账面上看不出错。
 */
public class UsageSplitterTest {

    /** 用 Map 当 usage：真实调用点包的是 Gson 的 JsonObject，口径一样。 */
    private static UsageSplitter.UsageFields fields(final Map<String, Object> values) {
        return new UsageSplitter.UsageFields() {
            @Override public Long number(String field) {
                Object value = values.get(field);
                return value instanceof Number ? ((Number) value).longValue() : null;
            }
            @Override public Long nested(String object, String field) {
                Object inner = values.get(object);
                if (!(inner instanceof Map)) {
                    return null;
                }
                Object value = ((Map<?, ?>) inner).get(field);
                return value instanceof Number ? ((Number) value).longValue() : null;
            }
        };
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test public void openai_style_total_input_gets_the_cached_parts_subtracted() {
        TokenBundle tokens = UsageSplitter.split(fields(map(
                "prompt_tokens", 100, "completion_tokens", 10,
                "prompt_tokens_details", map("cached_tokens", 30))));

        assertEquals("未命中缓存的那一段", 70, tokens.input);
        assertEquals(30, tokens.cacheRead);
        assertEquals(0, tokens.cacheWrite);
        assertEquals(10, tokens.output);
    }

    @Test public void deepseek_style_explicit_miss_wins() {
        TokenBundle tokens = UsageSplitter.split(fields(map(
                "prompt_tokens", 100, "completion_tokens", 10,
                "prompt_cache_hit_tokens", 40, "prompt_cache_miss_tokens", 60)));

        assertEquals(60, tokens.input);
        assertEquals(40, tokens.cacheRead);
    }

    @Test public void anthropic_style_input_is_already_the_uncached_part() {
        TokenBundle tokens = UsageSplitter.split(fields(map(
                "input_tokens", 25, "output_tokens", 5, "cache_creation_input_tokens", 100)));

        assertEquals("Anthropic 的 input_tokens 不能再减，减了会变负数", 25, tokens.input);
        assertEquals(100, tokens.cacheWrite);
        assertEquals(5, tokens.output);
    }

    @Test public void a_negative_result_falls_back_instead_of_going_into_the_ledger() {
        // 某家给了互相矛盾的口径（命中比总量还多）：不许把负数记进账本
        TokenBundle tokens = UsageSplitter.split(fields(map(
                "prompt_tokens", 10, "completion_tokens", 1,
                "prompt_cache_hit_tokens", 99)));

        assertEquals("退回不减，而不是记一个负数", 10, tokens.input);
    }

    @Test public void no_usage_at_all_is_null_not_zero() {
        assertNull("「没有 usage」和「花了 0 token」是两件事", UsageSplitter.split(null));
        assertNull(UsageSplitter.split(fields(map("something_else", 1))));
    }

    @Test public void a_usage_missing_the_input_side_is_unknown_not_zero() {
        // 只给了输出：四桶里缺一整块，**当未知**。若把输入当 0，这一行的成本会偏低
        // 而屏幕上完全看不出来——正是 `CONTRACTS.md` §4 要防的那类错。
        assertNull(UsageSplitter.split(fields(map("completion_tokens", 7))));
    }
}
