package com.mobilegroup20.modelpilot.chat;

import com.mobilegroup20.modelpilot.contract.model.TokenBundle;

/**
 * 把各家 usage 里的字段归一成四个桶——**账本要的是四个桶，不是两个数**。
 *
 * <p>`input` **只装未命中缓存的那部分**（`CONTRACTS.md` §8 把这条字面量口径写死了，
 * 因为两种读法都讲得通而差别很大）：OpenAI / DeepSeek 的 `prompt_tokens` 是"输入总量"，
 * 要把缓存命中与写入两段减掉；Anthropic 的 `input_tokens` 本身就已经是未命中部分，
 * 再减一次会出负数——**而负数不会报错，只会让账对不上**。所以两种形状都要认。
 *
 * <p>认不出来就返回 {@code null}，**不是全 0**：`CONTRACTS.md` §4 那条底线——
 * "这次没有 usage"和"这次花了 0 token"是两件事。
 *
 * <p>这份逻辑和服务端 `backend/modelpilot_forum/usage.py` 里那份是同一套口径，
 * 但**不能共用**（一边 Java 一边 Python）。两边都改了要一起改，注释里互相指一下。
 */
public final class UsageSplitter {

    private UsageSplitter() {
    }

    /**
     * @param usage 上游返回的 usage 对象（Gson 的 {@code JsonObject} 或任何 Map 形状）
     * @return 四个桶；认不出来返回 null
     */
    public static TokenBundle split(UsageFields usage) {
        if (usage == null) {
            return null;
        }
        Long input = usage.number("prompt_tokens");
        Long output = usage.number("completion_tokens");
        if (input == null && output == null) {
            // Anthropic 那套字段名：input_tokens 本身就是"未命中缓存"的部分。
            input = usage.number("input_tokens");
            output = usage.number("output_tokens");
            if (input == null && output == null) {
                return null;
            }
        }
        Long cacheRead = usage.number("prompt_cache_hit_tokens");
        if (cacheRead == null) {
            cacheRead = usage.nested("prompt_tokens_details", "cached_tokens");
        }
        if (cacheRead == null) {
            cacheRead = usage.nested("prompt_cache_details", "cached_tokens");
        }
        Long cacheWrite = usage.number("cache_creation_input_tokens");
        if (cacheWrite == null) {
            cacheWrite = usage.nested("prompt_cache_details", "cache_write_tokens");
        }

        if (input == null) {
            // **只给了输出、没给输入**：四桶里缺了整整一块，没法忠实拆开。
            // 这里返回 null（当未知）而不是把输入当 0——"不知道输入用了多少"和
            // "输入是 0"在账本上是两件事，后者会让这一行的成本偏低而看不出来。
            return null;
        }
        long uncached;
        Long explicitMiss = usage.number("prompt_cache_miss_tokens");
        if (explicitMiss != null) {
            uncached = explicitMiss;
        } else if (usage.number("input_tokens") != null && usage.number("prompt_tokens") == null) {
            uncached = input;                    // Anthropic：不减
        } else {
            uncached = input - orZero(cacheRead) - orZero(cacheWrite);
            if (uncached < 0) {
                // 减出负数说明这家给的口径和我们以为的不一样。**不许把负数记进账本**
                // （那会让汇总比真实值还小，而且看不出错），退回"不减"并在下游按未知处理。
                uncached = input;
            }
        }
        TokenBundle bundle = new TokenBundle();
        bundle.input = uncached;
        bundle.cacheRead = orZero(cacheRead);
        bundle.cacheWrite = orZero(cacheWrite);
        bundle.output = orZero(output);
        return bundle;
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    /** 字段读取的抽象：真实调用点包 Gson 的 {@code JsonObject}，单测可以直接给 Map。 */
    public interface UsageFields {
        Long number(String field);
        Long nested(String object, String field);
    }
}
