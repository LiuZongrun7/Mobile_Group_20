package com.mobilegroup20.modelpilot.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * **本地月度上限**：用户自己填一个数，超了在 Insights 上提醒他。
 *
 * <p><b>它和"套餐 / 充值 / 统一支付"是两件事</b>（用户明确说过"大家还没体验上就收费了，
 * 不做套餐"）：这里不涉及任何支付通道，也不代表我们的额度——用户的 key 是他自己的，
 * 钱是直接付给各家的。这个数字只是一个"我自己给自己定的线"，用来回答
 * "这个月是不是花超了"。
 *
 * <p>存的是**微美元**（和账本同口径，见 `Money`），避免来回换算产生分位误差。
 * 0 = 没设（**不是"上限为零"**：界面上要显示成"未设置"，而不是"已经超了"）。
 */
public final class Budget {

    private static final String FILE = "app_budget";
    private static final String KEY_LIMIT = "monthly_limit_micros";

    private Budget() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 月度上限（微美元）；0 = 没设。 */
    public static long limitMicros(Context context) {
        return prefs(context).getLong(KEY_LIMIT, 0L);
    }

    /**
     * 设置上限。传进来的是一段用户输入的文字（元/美元），解析不出来就当成"没设"。
     * 负数与 0 一律存 0——"上限 -5 元"没有意义，存进去只会让进度条算出负数。
     */
    public static void setLimitUsd(Context context, String raw) {
        long micros = 0L;
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                double value = Double.parseDouble(raw.trim());
                if (value > 0) {
                    micros = Math.round(value * 1_000_000L);
                }
            } catch (NumberFormatException notANumber) {
                micros = 0L;
            }
        }
        prefs(context).edit().putLong(KEY_LIMIT, micros).apply();
    }
}
