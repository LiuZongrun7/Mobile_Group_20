package com.mobilegroup20.tokentrail.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link TokenFormat} 的测试。
 *
 * <p>盯着一件事：<b>HUD 上并排放着的那几个数字，读者加起来要加得通。</b>
 * 这不是"看着差不多"的事——三个桶各自四舍五入再相加，和"先加再四舍五入"
 * 能差出显示精度的整整一格，真机上已经撞到过一次
 * （4.73M + 3.25M + 871.0K = 8.85M，大数字却写 8.84M）。
 */
public class TokenFormatTest {

    /** 把 {@link TokenFormat#format} 写出来的字符串读回数字，就是读者心算的那一步。 */
    private static long readBack(String shown) {
        if (shown.endsWith("M")) {
            return Math.round(Double.parseDouble(shown.substring(0, shown.length() - 1)) * 1_000_000);
        }
        if (shown.endsWith("K")) {
            return Math.round(Double.parseDouble(shown.substring(0, shown.length() - 1)) * 1_000);
        }
        return Long.parseLong(shown);
    }

    // ---- 写法本身 ----

    @Test
    public void writesShortFormsAtEachScale() {
        assertEquals("0", TokenFormat.format(0));
        assertEquals("949", TokenFormat.format(949));
        assertEquals("1.0K", TokenFormat.format(1_000));
        assertEquals("871.0K", TokenFormat.format(871_000));
        assertEquals("1.00M", TokenFormat.format(1_000_000));
        assertEquals("8.84M", TokenFormat.format(8_844_000));
    }

    /** 百万是 10^6，不是 2^20。用 1024 的话账单上的数字全对不上。 */
    @Test
    public void millionIsDecimalNotBinary() {
        assertEquals("1.00M", TokenFormat.format(1_000_000));
        assertEquals("1.05M", TokenFormat.format(1_048_576));
    }

    // ---- 关键性质：写出来的字符串必须能原样读回来 ----

    /**
     * {@code format(roundForDisplay(x))} 读回来正好等于 {@code roundForDisplay(x)}。
     *
     * <p>这条成立，三个桶"显示出来的值"相加才等于"取整后的值"相加——
     * 也就是大数字可以用它算。不成立的话，屏幕上就差得出来。
     */
    @Test
    public void displayedValueReadsBackExactly() {
        long[] samples = {
                0, 1, 949, 950, 999, 1_000, 1_049, 1_050, 12_345, 99_999,
                100_000, 871_000, 999_949, 999_950, 999_999,
                1_000_000, 1_004_999, 1_005_000, 4_725_100, 8_844_000, 12_345_678,
        };
        for (long value : samples) {
            long rounded = TokenFormat.roundForDisplay(value);
            assertEquals("format 出来的字符串读不回原值: " + value,
                    rounded, readBack(TokenFormat.format(rounded)));
        }
    }

    /**
     * 三个桶显示出来的数加起来，和大数字最多差最后一位的一半。
     *
     * <p>这正是真机上出问题的那一步：差一整格（8.85M 对 8.84M）是 bug，
     * 差半格以内是正常的四舍五入，谁也看不出来。
     */
    @Test
    public void threeBucketsAddUpToTheHeadline() {
        long[][] cases = {
                // 真机 2026-09 的桩数据（HUD 上就是这三格 + 大数字）
                {4_725_100, 3_248_000, 870_950},
                // 月初，都还不到 1M，走 K 那一档
                {12_300, 900, 400},
                // 三个桶都刚好压在阈值上下
                {999_950, 1_000_050, 0},
                // 一个桶占绝大多数
                {9_000_000, 10_000, 5_000},
        };

        for (long[] c : cases) {
            long a = TokenFormat.roundForDisplay(c[0]);
            long b = TokenFormat.roundForDisplay(c[1]);
            long d = TokenFormat.roundForDisplay(c[2]);

            long headline = TokenFormat.roundForDisplay(a + b + d);
            long readerSum = readBack(TokenFormat.format(a))
                    + readBack(TokenFormat.format(b))
                    + readBack(TokenFormat.format(d));

            // 大数字自己也是按同一精度取整的，所以允许的偏差就是半格
            long step = a + b + d < 1_000 ? 1
                    : a + b + d < 1_000_000 ? 100
                    : 10_000;
            assertTrue("加出来差了不止半格: " + readerSum + " vs " + headline,
                    Math.abs(readerSum - headline) <= step / 2);
        }
    }

    /** 不许出现四位数的 K（"1000.0K"）——取整时跨过阈值就该写成 1.00M。 */
    @Test
    public void neverWritesFourDigitThousands() {
        assertEquals("1.00M", TokenFormat.format(TokenFormat.roundForDisplay(999_999)));
        assertEquals("1.00M", TokenFormat.format(TokenFormat.roundForDisplay(999_950)));
    }
}
