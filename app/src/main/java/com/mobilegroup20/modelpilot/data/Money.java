package com.mobilegroup20.modelpilot.data;

/**
 * 金额的两个换算方向，<b>全项目只有这一处汇率</b>。
 *
 * <p>为什么要单独一个类：{@code CONTRACTS.md} §9 定的口径是「计价全程用微美元，
 * 只在最后一步换成人民币」。但实际数据推翻了一半——<b>DeepSeek 的账单本来就是人民币</b>
 * （导出里的 {@code currency} 列写死 {@code CNY}），所以除了「展示时美元 → 人民币」，
 * 还多了一个反向的「导入时人民币 → 美元」。两个方向必须用同一个汇率，
 * 否则屏幕上会出现两个对不上的数，而且没人知道该信哪个。
 *
 * <p><b>微单位一律是 1e-6</b>：微美元、微人民币都一样。用 {@code long} 存，
 * 钱不许有浮点误差，理由见 {@link com.mobilegroup20.modelpilot.contract.model.PricingRate}。
 *
 * @see com.mobilegroup20.modelpilot.contract.model.UsageCall#nativeCostMicros
 */
public final class Money {

    /**
     * 1 美元合多少人民币，单位是<b>微人民币</b>。
     *
     * <p><b>⚠️ 这是个占位值，交之前必须换成真实汇率并写上下面的日期。</b>
     * 汇率的来源和取值口径见 {@code CONTRACTS.md} §9——那条 TBD 本来就归数据侧定。
     * 它一直没定，这里先用一个量级正确的数占着，好让链路能跑通、能测。
     *
     * <p>换成真值时要一起改的：这个常量、下面的 {@link #RATE_AS_OF}、
     * 以及报告里那句「所有金额都是估算」的说明。
     */
    public static final long CNY_PER_USD_MICROS = 7_100_000L;

    /** 上面那个汇率的日期。汇率是时点值，不写日期的话半年后就没人知道它有多旧。 */
    public static final String RATE_AS_OF = "2026-09-26（占位，未核实）";

    /** 人民币的 ISO 代码。别在别处再写一遍字符串。 */
    public static final String CNY = "CNY";

    private Money() {
    }

    /**
     * 来源账单上的金额 → 微美元。导入时用。
     *
     * <p>币种不认识就<b>原样返回</b>并当成美元，不抛异常：导入不该因为多了一个没见过的
     * 币种就整批失败，而 {@code UsageCall.costCurrency} 已经把这个币种记下来了，
     * 对账的时候查得出来。
     */
    public static long toUsdMicros(long nativeMicros, String currency) {
        if (CNY.equalsIgnoreCase(currency)) {
            return nativeMicros * 1_000_000L / CNY_PER_USD_MICROS;
        }
        return nativeMicros;
    }

    /** 微美元 → 微人民币。展示时用。 */
    public static long toCnyMicros(long usdMicros) {
        return usdMicros * CNY_PER_USD_MICROS / 1_000_000L;
    }

    /**
     * 微美元 → 给人看的人民币字符串，如 {@code "¥14.08"}。
     *
     * <p>界面不做乘法，只做显示——同一笔钱在两个页面上写成两个值是这类项目
     * 最常见也最难看出来的 bug。
     */
    public static String formatCny(long usdMicros) {
        long cnyMicros = toCnyMicros(usdMicros);
        long sign = cnyMicros < 0 ? -1 : 1;
        long abs = Math.abs(cnyMicros);
        long cents = Math.round(abs / 10_000.0);
        return String.format(java.util.Locale.US, "%s¥%d.%02d",
                sign < 0 ? "-" : "", cents / 100, cents % 100);
    }
}
