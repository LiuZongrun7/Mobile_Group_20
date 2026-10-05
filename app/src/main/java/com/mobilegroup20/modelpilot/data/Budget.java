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
 *
 * <p><b>（2026-10-05 改）用户按哪种币种输入，由 `Currency` 负责折算。</b>
 * 这个类只认微美元，不再自己解析输入的文字——原来那个
 * {@code setLimitUsd(context, raw)} 把"解析"和"这是个美元数"两件事混在一起，
 * 一旦界面上能选人民币，它就会把用户填的 ¥140 当成 $140 存进去，
 * 而屏幕上（按汇率折回来）显示的是 ¥1001——一个谁都没填过的数字。
 * 现在的分工是：界面拿到用户输入 → {@link Currency#enteredToUsdMicros} 折成微美元
 * （汇率没填时返回 null，那就**不许存**）→ 调这里的 {@link #setLimitMicros}。
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
     * 设置上限（微美元）。传 0 或负数 = 清掉上限（"上限 -5 元"没有意义，
     * 存进去只会让进度条算出负数）。
     */
    public static void setLimitMicros(Context context, long usdMicros) {
        prefs(context).edit().putLong(KEY_LIMIT, Math.max(0L, usdMicros)).apply();
    }
}
