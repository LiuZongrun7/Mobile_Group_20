package com.mobilegroup20.tokentrail.util;

/**
 * token 数怎么写成人看的样子，以及<b>"屏幕上的几个数字必须自洽"</b>这件事。
 *
 * <p>纯 Java，不 import 任何 {@code android.*}：这是算术，不是界面，能脱离模拟器
 * 跑测试（见 {@code TokenFormatTest}）。HUD 上那四个数字是并排放在一起的，
 * 读的人一定会去加——所以它得经得起加。
 *
 * <h2>为什么要有 {@link #roundForDisplay}</h2>
 *
 * <p>三个桶各自四舍五入之后再相加，和"先相加再四舍五入"<b>不是一回事</b>，
 * 能差出显示精度的整整一格。真机上就撞上过：
 *
 * <pre>
 *   4.73M + 3.25M + 871.0K = 8.851M      ← 三个桶显示出来的数，读者自己加出来的
 *   大数字写着                 8.84M      ← 直接对 total() 四舍五入的结果
 * </pre>
 *
 * <p>三个数字并排摆着，加起来差 0.01M，第一反应是"这软件在骗我"，而不是
 * "这是四舍五入"。所以大数字<b>必须由三个桶取整后的值相加</b>得到，
 * 这样读者加出来的数和大数字最多差最后一位的一半（正常的四舍五入），
 * 不会差一整格。
 *
 * <p><b>改这里的精度要连着改 HUD 的用法</b>：写大数字的地方不能再直接用
 * {@code bundle.total()}，要用三个桶的 {@link #roundForDisplay} 之和。
 */
public final class TokenFormat {

    /** 不到 1 千就写原数，不做小数。 */
    private static final long K = 1_000L;

    /** 「百万」是 10^6，不是 2^20——账单上就是这么写的。 */
    private static final long M = 1_000_000L;

    /** K 保留一位小数，所以精度是 100。 */
    private static final long K_STEP = 100L;

    /** M 保留两位小数，所以精度是 1 万。 */
    private static final long M_STEP = 10_000L;

    private TokenFormat() {
    }

    /**
     * 写成 {@code 8.84M} / {@code 871.0K} / {@code 949} 这种短写法。
     * HUD 上那行是大字号，写全了会被挤断。
     */
    public static String format(long tokens) {
        if (tokens < K) {
            return String.valueOf(tokens);
        }
        if (tokens < M) {
            return String.format(java.util.Locale.ENGLISH, "%.1fK", tokens / (double) K);
        }
        return String.format(java.util.Locale.ENGLISH, "%.2fM", tokens / (double) M);
    }

    /**
     * 按 {@link #format} 的精度取整，返回"显示出来就是那个数"的整数值。
     *
     * <p>语义：{@code format(roundForDisplay(x))} 和 {@code format(x)} 看起来一样，
     * 但前者是**可以相加的**——相加之后仍然每个数都对得上自己显示的那串字符。
     *
     * <p>注意结果可能跨过阈值：{@code 999_999} 会变成 1,000,000，于是显示成
     * {@code 1.00M} 而不是 {@code 1000.0K}。这是要的——四个数字里出现一个四位数的 K
     * 更难看，而且它和其余几个照样对得上。
     */
    public static long roundForDisplay(long tokens) {
        if (tokens < K) {
            return tokens;
        }
        if (tokens < M) {
            return Math.round(tokens / (double) K_STEP) * K_STEP;
        }
        return Math.round(tokens / (double) M_STEP) * M_STEP;
    }
}
