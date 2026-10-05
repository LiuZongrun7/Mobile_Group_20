package com.mobilegroup20.modelpilot.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>显示币种</b>（美元 / 人民币）与<b>用户自填的汇率</b>，以及金额显示的唯一入口。
 *
 * <p>三件事必须一起看，少一件就会出错：
 *
 * <ol>
 *   <li><b>账本永远是微美元</b>。切币种只影响"怎么显示"，不改任何一条记录，
 *       也不改任何一次换算的记账口径（{@code Money} 的类注释）。</li>
 *   <li><b>没有汇率就不许换算</b>。选了人民币但没填汇率时，{@link #format} 会
 *       <b>退回美元</b>，界面照 {@link #needsRate} 显示一句"还没填汇率"。
 *       这和"算不出价显示未知而不是 0"是同一条底线：宁可显示账本原值，
 *       也不拿一个编出来的汇率把人民币算出来——那种数字看起来最正常，也最查不出来。</li>
 *   <li><b>汇率带来源和日期，改动留痕</b>。存的是 {@link FxRate}（值 + 填写时间 + 来源），
 *       每次改还追加一条历史（上限 {@link #HISTORY_MAX} 条）。汇率是时点值，
 *       不写清楚"什么时候、按谁给的"的话，半年后没人知道页面上那个数是怎么来的。</li>
 * </ol>
 *
 * <p>为什么放在本机 prefs 而不是服务端：这个 App 的账本本来就在手机上，
 * 汇率只是给这些数配一个显示单位——它没有任何要跨设备同步的必要，
 * 而放服务端反而多一个"服务端不在线就显示不了金额"的失败点。
 *
 * <p>判断逻辑都写成<b>纯函数重载</b>（参数是币种 + {@link FxRate}），
 * 读 prefs 的那层只是把它接上；这样"没汇率时不换算"这条底线能被单测钉住，
 * 不需要模拟器。
 */
public final class Currency {

    public static final String USD = Money.USD;
    public static final String CNY = Money.CNY;

    private static final String FILE = "app_currency";
    private static final String KEY_CODE = "display_code";
    private static final String KEY_RATE = "fx_cny_per_usd_micros";
    private static final String KEY_RATE_AT = "fx_entered_at";
    private static final String KEY_RATE_SOURCE = "fx_source";
    private static final String KEY_RATE_HISTORY = "fx_history";

    /**
     * 历史最多留几条。10 条足够回答"上个月那个数按哪个汇率算的"，
     * 又不至于把 prefs 当数据库用。
     */
    private static final int HISTORY_MAX = 10;

    private Currency() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ==================== 币种 ====================

    /**
     * 当前选择的币种。默认<b>美元</b>——它是账本的原生单位，显示它不需要用户提供任何东西。
     * 认不出来的值一律回落美元，免得 prefs 里一个脏字符串把整页金额搞成空白。
     */
    public static String code(Context context) {
        return normalize(prefs(context).getString(KEY_CODE, USD));
    }

    public static void setCode(Context context, String code) {
        prefs(context).edit().putString(KEY_CODE, normalize(code)).apply();
    }

    /** 实际显示用的币种：选了人民币但没汇率时是美元（见 {@link #format}）。 */
    public static String shownCode(Context context) {
        return shownCode(code(context), rate(context));
    }

    /**
     * 「选了人民币、但汇率还没填」——界面要为此显式提示。
     * 这不是错误状态，是一个<b>必须说出来的</b>状态：不说的话用户会以为
     * 自己看到的是人民币金额。
     */
    public static boolean needsRate(Context context) {
        return needsRate(code(context), rate(context));
    }

    // ==================== 汇率（留痕） ====================

    /** 当前生效的汇率；没填过就是 {@link FxRate#UNSET}。 */
    public static FxRate rate(Context context) {
        SharedPreferences p = prefs(context);
        return new FxRate(p.getLong(KEY_RATE, 0L), p.getLong(KEY_RATE_AT, 0L),
                p.getString(KEY_RATE_SOURCE, null));
    }

    /**
     * 存一条新汇率（微人民币/美元）并追加历史。
     *
     * <p>{@code micros <= 0} 会被当成"清空"：把当前生效的那条连来源一起删掉，
     * <b>但历史留着</b>——清空本身也是一次改动，而留痕的意义正是"事后能看出
     * 这个数当时按哪版汇率算的"。清完界面上会退回美元显示并说明（见 {@link #needsRate}）。
     */
    public static void setRate(Context context, long cnyPerUsdMicros, String source) {
        SharedPreferences p = prefs(context);
        if (cnyPerUsdMicros <= 0L) {
            p.edit().remove(KEY_RATE).remove(KEY_RATE_AT).remove(KEY_RATE_SOURCE).apply();
            return;
        }
        long now = System.currentTimeMillis();
        List<FxRate> history = new ArrayList<>();
        history.add(new FxRate(cnyPerUsdMicros, now, source));
        history.addAll(history(context));
        while (history.size() > HISTORY_MAX) {
            history.remove(history.size() - 1);
        }
        p.edit()
                .putLong(KEY_RATE, cnyPerUsdMicros)
                .putLong(KEY_RATE_AT, now)
                .putString(KEY_RATE_SOURCE, source == null ? "" : source.trim())
                .putString(KEY_RATE_HISTORY, FxRate.encodeHistory(history))
                .apply();
    }

    /** 汇率改动历史（新的在前，最多 {@link #HISTORY_MAX} 条）。当前这条也在里面。 */
    public static List<FxRate> history(Context context) {
        return FxRate.decodeHistory(prefs(context).getString(KEY_RATE_HISTORY, ""));
    }

    // ==================== 金额显示（唯一入口） ====================

    /**
     * 微美元 → 当前币种的字符串。<b>界面上所有金额都走这里</b>，
     * 别在别处自己判币种、自己乘汇率。
     */
    public static String format(Context context, long usdMicros) {
        return format(code(context), rate(context), usdMicros);
    }

    /**
     * 「这个金额是怎么来的」那一行说明；不需要说明时返回 null。
     *
     * <p>美元显示就是账本原值，没什么可解释的（返回 null，界面别硬凑一句废话）；
     * 人民币显示才有话说——是按<b>谁填的、什么时候填的</b>汇率折过来的。
     * 这一行的存在就是设计稿那条「记录原币、汇率来源与日期」在界面上的样子。
     */
    public static String provenance(Context context) {
        return provenance(code(context), rate(context));
    }

    /**
     * 用户按<b>当前币种</b>输入的金额（比如月度上限）→ 微美元。
     *
     * <p>三种结果，语义分得很开：
     * <ul>
     *   <li>{@code 0} = 空 / 不是数 → 当成"没设"（和 {@link Money#parseMicros} 一致）；</li>
     *   <li>{@code null} = <b>换算不了</b>：填的是人民币但汇率没填。
     *       调用方<b>不能</b>把它当 0，也不能硬存——要提示用户先填汇率；</li>
     *   <li>正数 = 微美元，可以存。</li>
     * </ul>
     */
    public static Long enteredToUsdMicros(Context context, String raw) {
        return enteredToUsdMicros(code(context), rate(context), raw);
    }

    // ==================== 纯函数（可单测的那一层） ====================

    static String normalize(String code) {
        return CNY.equalsIgnoreCase(code) ? CNY : USD;
    }

    static boolean needsRate(String code, FxRate rate) {
        return CNY.equals(normalize(code)) && !rate.isSet();
    }

    static String shownCode(String code, FxRate rate) {
        return needsRate(code, rate) ? USD : normalize(code);
    }

    static String format(String code, FxRate rate, long usdMicros) {
        if (CNY.equals(shownCode(code, rate))) {
            return Money.formatCny(usdMicros, rate.cnyPerUsdMicros);
        }
        return Money.formatUsd(usdMicros);
    }

    static String provenance(String code, FxRate rate) {
        if (!CNY.equals(shownCode(code, rate))) {
            return null;
        }
        return rate.hasSource()
                ? "1 USD = " + rate.label() + " CNY · " + rate.enteredOn() + " · " + rate.source
                : "1 USD = " + rate.label() + " CNY · " + rate.enteredOn();
    }

    static Long enteredToUsdMicros(String code, FxRate rate, String raw) {
        long micros = Money.parseMicros(raw);
        if (micros <= 0L) {
            return 0L;
        }
        if (!CNY.equals(normalize(code))) {
            return micros;
        }
        if (!rate.isSet()) {
            return null;
        }
        return Money.toUsdMicros(micros, CNY, rate.cnyPerUsdMicros);
    }
}
