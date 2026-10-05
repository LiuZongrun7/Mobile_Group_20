package com.mobilegroup20.modelpilot.data;

/**
 * 金额的换算与显示，<b>全项目换算只在这一处</b>。
 *
 * <p>计价（账本、费率、成本）全程是<b>微美元</b>（{@code long}，不许用 {@code double}，
 * 理由见 {@link com.mobilegroup20.modelpilot.contract.model.PricingRate}）。这一版多了一件事：
 * <b>人民币汇率不再由代码定，而是用户自己填</b>——见 {@link Currency}：币种 + 一个带
 * 「来源」和「填写日期」的汇率，由用户负责，我们只负责如实标出它是谁填的。
 *
 * <p><b>所以这个类里没有任何汇率常量，每个换算方法都要求把汇率当参数传进来。</b>
 * 原来那个 {@code CNY_PER_USD_MICROS = 7_100_000} 的占位值已经删掉：留一个"看不见的兜底汇率"
 * 是最坏的做法——屏幕上会出现一个没人知道从哪来的数字，而且它看起来和其他数字一样可信。
 * 没有汇率时唯一的正确行为是<b>不换算</b>（{@code Currency} 会退回美元显示并说明原因），
 * 而不是拿一个默认值把人民币算出来。
 *
 * <p>汇率的单位是<b>微人民币</b>（1 美元 = X 人民币，X 用 1e-6 精度存），和金额同精度，
 * 这样"7.15"这种两位小数不会有二进制浮点的尾巴。
 *
 * @see com.mobilegroup20.modelpilot.contract.model.UsageCall#nativeCostMicros
 */
public final class Money {

    /** 美元的 ISO 代码。别在别处再写一遍字符串。 */
    public static final String USD = "USD";

    /** 人民币的 ISO 代码。别在别处再写一遍字符串。 */
    public static final String CNY = "CNY";

    /** 1 元 = 1e6 微元；换算里到处都要这个数，写成一个名字免得数错零。 */
    private static final long MICROS = 1_000_000L;

    /** 一分的微单位（1 分 = 0.01 元 = 1e4 微元）。 */
    private static final long MICROS_PER_CENT = 10_000L;

    private Money() {
    }

    /**
     * 用户输入的一段金额文字 → 微单位。空、不是数、0、负数、大到离谱的<b>一律返回 0</b>。
     *
     * <p><b>0 的含义由调用方定</b>：月度上限那边 0 = "没设"（见 {@link Budget}），
     * 而"没设"和"设成 0 元"在界面上是两句不同的话，别混。
     *
     * <p>为什么这里连 {@code Infinity} 和 {@code NaN} 都要挡：{@code Double.parseDouble}
     * 认得这两个词，而 {@code Math.round(NaN)} 是 0、{@code Math.round(Infinity)} 是
     * {@code Long.MAX_VALUE}——后者会变成一个天文数字的上限，界面上看起来像"永远花不完"。
     */
    public static long parseMicros(String raw) {
        if (raw == null) {
            return 0L;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return 0L;
        }
        double value;
        try {
            value = Double.parseDouble(trimmed);
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
        // 1e12 美元这个量级已经没有"金额"的意义了，当成手滑。
        if (!Double.isFinite(value) || value <= 0 || value > 1e12) {
            return 0L;
        }
        return Math.round(value * MICROS);
    }

    /**
     * 来源账单上的金额 → 微美元。导入时用。
     *
     * <p>币种不认识就<b>原样返回</b>并当成美元，不抛异常：导入不该因为多了一个没见过的
     * 币种就整批失败，而 {@code UsageCall.costCurrency} 已经把这个币种记下来了，
     * 对账的时候查得出来。
     *
     * <p><b>但"是人民币、却没给汇率"必须炸。</b>那不是"不认识的币种"，是"知道该换算、
     * 但手上没有换算依据"——这时候返回原值等于把一笔 14.08 元的账单记成 14.08 美元，
     * 差 7 倍，而且账面上看不出任何异常。宁可在调用处失败（导入路径要把它计进
     * {@code rejected}），也不要写进去一个错的数。
     *
     * @param cnyPerUsdMicros 1 美元合多少人民币（微），用户填的那个；人民币必须给
     */
    public static long toUsdMicros(long nativeMicros, String currency, long cnyPerUsdMicros) {
        if (CNY.equalsIgnoreCase(currency)) {
            if (cnyPerUsdMicros <= 0L) {
                throw new IllegalArgumentException("人民币账单没有汇率：不能按 1:1 当成美元记账");
            }
            return mulDiv(nativeMicros, MICROS, cnyPerUsdMicros);
        }
        return nativeMicros;
    }

    /** 微美元 → 微人民币。展示时用。<b>汇率没填就别调它</b>（见类注释）。 */
    public static long toCnyMicros(long usdMicros, long cnyPerUsdMicros) {
        if (cnyPerUsdMicros <= 0L) {
            throw new IllegalArgumentException("没有汇率就不能换算人民币");
        }
        return mulDiv(usdMicros, cnyPerUsdMicros, MICROS);
    }

    /**
     * {@code value * factor / scale}，**整数、不溢出**。
     *
     * <p>为什么不直接写 {@code value * factor / scale}：中间那个乘积会先炸。
     * 汇率的微单位是 1e6 量级，金额的微单位也是 1e6 量级，两个一乘就是 1e12，
     * 而一个"大但合法"的金额（月度上限填了 1e9）在微单位下是 1e15——
     * 乘起来 1e21，long 直接绕回负数。屏幕上就会显示一个负的金额，
     * 而根因是一个谁也不觉得会出事的乘法。
     *
     * <p>拆成"整数部分 + 余数"两步就不会溢出：{@code value / scale} 是真实金额的量级
     * （很小），余数一定小于 {@code scale}，所以 {@code rem * factor} 最多 1e12 量级。
     * 两条路的结果按截断（向零取整）完全一样，负号也对得上——这是整数除法的恒等式，
     * 不是近似。
     */
    private static long mulDiv(long value, long factor, long scale) {
        long whole = value / scale;
        long remainder = value % scale;
        return whole * factor + remainder * factor / scale;
    }

    /**
     * 微美元 → `$0.42` 这样的字符串。
     *
     * <p>账本里的价目是<b>按美元抄的官方定价页</b>（见 `BundledPricingSource`），
     * 所以美元这条路上不做任何换算，直接把账本里的数照原样显示。
     *
     * <p>小于 1 分钱的显示成 `< $0.01` 而不是 `$0.00`：
     * 后者看起来像"没花钱"，而它其实是"花了，但小到显示不出来"。
     */
    public static String formatUsd(long usdMicros) {
        if (usdMicros > 0 && usdMicros < MICROS_PER_CENT) {
            return "< $0.01";
        }
        if (usdMicros < 0 && usdMicros > -MICROS_PER_CENT) {
            return "> -$0.01";
        }
        return String.format(java.util.Locale.US, "$%.2f", usdMicros / (double) MICROS);
    }

    /**
     * 微美元 → 给人看的人民币字符串，如 {@code "¥14.08"}。**必须传用户填的汇率**。
     *
     * <p>界面不做乘法，只做显示——同一笔钱在两个页面上写成两个值是这类项目
     * 最常见也最难发现的 bug。所以调用点应该走 {@link Currency#format}，
     * 而不是自己拿汇率在这里乘一遍。
     */
    public static String formatCny(long usdMicros, long cnyPerUsdMicros) {
        long cnyMicros = toCnyMicros(usdMicros, cnyPerUsdMicros);
        boolean negative = cnyMicros < 0;
        long abs = Math.abs(cnyMicros);
        if (abs > 0 && abs < MICROS_PER_CENT) {
            return negative ? "> -¥0.01" : "< ¥0.01";
        }
        long cents = Math.round(abs / (double) MICROS_PER_CENT);
        return String.format(java.util.Locale.US, "%s¥%d.%02d",
                negative ? "-" : "", cents / 100, cents % 100);
    }
}
