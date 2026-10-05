package com.mobilegroup20.modelpilot.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * 币种、汇率、以及金额换算的规则测试（纯计算，不用模拟器）。
 *
 * <p>盯住的是四件"错了也不报错、只是屏幕上悄悄换了个数"的事：
 * <ol>
 *   <li><b>没有汇率时不许换算</b>。选了人民币但没填汇率，必须退回美元显示——
 *       编一个默认汇率会让整页金额变成假的，而且看起来完全是正常的；</li>
 *   <li><b>填反了要认出来</b>（把「1 元 = 0.14 美元」当成「1 美元 = 0.14 元」），
 *       这种输入最像正常数字，后果是差 50 倍；</li>
 *   <li><b>换算不能溢出</b>。金额和汇率都是微单位（1e6），两个一乘就是 1e12，
 *       大额一进来就绕回负数；</li>
 *   <li><b>汇率留痕</b>：填的值、日期、来源要一起存下来，历史串坏了一条也不能
 *       连累汇率本身能不能用。</li>
 * </ol>
 */
public class CurrencyFxTest {

    /** 2026-10-05（北京时间）——用来钉住"填写日期"这一列。 */
    private static final long AT = Instant.parse("2026-10-05T03:00:00Z").toEpochMilli();

    private static final long RATE_715 = 7_150_000L;      // 1 美元 = 7.15 元

    private static FxRate rate(long micros, String source) {
        return new FxRate(micros, AT, source);
    }

    // ==================== 解析 ====================

    @Test
    public void rateTextIsParsedLenientlyButStillExactly() {
        assertEquals(RATE_715, FxRate.parse("7.15"));
        assertEquals(RATE_715, FxRate.parse("  7.15  "));
        assertEquals(RATE_715, FxRate.parse("1 USD = 7.15"));
        assertEquals(RATE_715, FxRate.parse("7.15 CNY/USD"));
        assertEquals(RATE_715, FxRate.parse("¥7.15"));
        assertEquals(RATE_715, FxRate.parse("7.150000"));
        assertEquals(7_100_000L, FxRate.parse("7.1"));
    }

    @Test
    public void junkRateTextMeansNotFilledInsteadOfGuessing() {
        assertEquals(0L, FxRate.parse(null));
        assertEquals(0L, FxRate.parse(""));
        assertEquals(0L, FxRate.parse("   "));
        assertEquals(0L, FxRate.parse("abc"));
        assertEquals(0L, FxRate.parse("0"));
        assertEquals(0L, FxRate.parse("-7.15"));
        assertEquals(0L, FxRate.parse("7.1.5"));
    }

    @Test
    public void invertedRateIsDetectedAndCanBeFlippedBack() {
        long inverted = FxRate.parse("0.1398");
        assertTrue("0.1398 是「1 元 = 0.1398 美元」，必须认出来",
                FxRate.looksInverted(inverted));
        assertFalse(FxRate.looksInverted(RATE_715));
        assertFalse("没填不算填反", FxRate.looksInverted(0L));

        long flipped = FxRate.inverted(inverted);
        assertEquals("7.153075", FxRate.format(flipped));
        assertFalse(FxRate.looksInverted(flipped));
    }

    @Test
    public void implausibleRatesAreFlaggedButNotRejected() {
        assertTrue(FxRate.plausible(RATE_715));
        assertTrue(FxRate.plausible(1_000_000L));          // 1.0
        assertTrue(FxRate.plausible(100_000_000L));        // 100
        assertFalse(FxRate.plausible(500_000L));           // 0.5：多半是填反了
        assertFalse(FxRate.plausible(715_000_000L));       // 715：多半多打了一位
    }

    @Test
    public void rateLabelKeepsWhatTheUserTyped() {
        assertEquals("7.15", FxRate.format(RATE_715));
        assertEquals("7.1", FxRate.format(7_100_000L));
        assertEquals("7", FxRate.format(7_000_000L));
        assertEquals("7.1532", FxRate.format(7_153_200L));
        assertEquals("", FxRate.format(0L));
    }

    // ==================== 换算 ====================

    @Test
    public void cnyBillIsConvertedWithTheRateItWasGiven() {
        assertEquals(1_983_098L, Money.toUsdMicros(14_080_000L, "CNY", 7_100_000L));
        assertEquals("美元账单原样返回", 14_080_000L,
                Money.toUsdMicros(14_080_000L, "USD", 7_100_000L));
        assertEquals("不认识的币种原样返回（币种本身已经留痕）", 14_080_000L,
                Money.toUsdMicros(14_080_000L, "JPY", 7_100_000L));
    }

    @Test
    public void cnyBillWithoutARateMustFailInsteadOfBeingTreatedAsUsd() {
        try {
            Money.toUsdMicros(14_080_000L, "CNY", 0L);
            fail("没有汇率却把人民币账单当美元记账，会差 7 倍而且查不出来");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("汇率"));
        }
    }

    @Test
    public void conversionDoesNotOverflowOnLargeAmounts() {
        long usdMicros = 1_000_000_000_000_000L;              // $1e9
        assertTrue("前提：朴素写法在这里会溢出", usdMicros * RATE_715 < 0);

        long cnyMicros = Money.toCnyMicros(usdMicros, RATE_715);
        assertEquals(reference(usdMicros, RATE_715), cnyMicros);
        assertTrue("溢出会变成负数，正好是屏幕上最不该出现的东西", cnyMicros > 0);
    }

    @Test
    public void conversionStaysWithinTwoMicrosBothWays() {
        long usdMicros = 1_234_567_890L;
        long cnyMicros = Money.toCnyMicros(usdMicros, RATE_715);
        long back = Money.toUsdMicros(cnyMicros, "CNY", RATE_715);
        assertTrue("来回折最多差 1 微美元（1e-6 美元），不能越折越偏",
                Math.abs(back - usdMicros) <= 2);
    }

    /** 独立算一遍（BigDecimal），免得断言里写的期望值是我手算错的。 */
    private static long reference(long micros, long rate) {
        return BigDecimal.valueOf(micros).multiply(BigDecimal.valueOf(rate))
                .divide(BigDecimal.valueOf(1_000_000L), RoundingMode.DOWN)
                .longValueExact();
    }

    @Test
    public void tinyAmountsSaySoInsteadOfShowingZero() {
        assertEquals("< $0.01", Money.formatUsd(5_000L));
        assertEquals("$0.00", Money.formatUsd(0L));
        assertEquals("$1.00", Money.formatUsd(1_000_000L));
        assertEquals("¥7.15", Money.formatCny(1_000_000L, RATE_715));
        assertEquals("< ¥0.01", Money.formatCny(1_000L, RATE_715));
    }

    @Test
    public void amountTextParsingRejectsThingsThatAreNotMoney() {
        assertEquals(20_000_000L, Money.parseMicros("20"));
        assertEquals(1_400_000L, Money.parseMicros(" 1.4 "));
        assertEquals(0L, Money.parseMicros(""));
        assertEquals(0L, Money.parseMicros(null));
        assertEquals(0L, Money.parseMicros("abc"));
        assertEquals(0L, Money.parseMicros("0"));
        assertEquals(0L, Money.parseMicros("-5"));
        assertEquals("NaN 不许变成一个巨大的上限", 0L, Money.parseMicros("NaN"));
        assertEquals("Infinity 同理", 0L, Money.parseMicros("Infinity"));
        assertEquals(0L, Money.parseMicros("1e13"));
    }

    // ==================== 币种选择（没汇率就不换算） ====================

    @Test
    public void cnyWithoutARateFallsBackToUsdInsteadOfInventingOne() {
        assertTrue(Currency.needsRate(Currency.CNY, FxRate.UNSET));
        assertEquals("实际显示的是美元", Currency.USD,
                Currency.shownCode(Currency.CNY, FxRate.UNSET));
        assertEquals("$0.42", Currency.format(Currency.CNY, FxRate.UNSET, 420_000L));
        assertNull("没折过就没有「按谁的汇率折的」可写",
                Currency.provenance(Currency.CNY, FxRate.UNSET));
    }

    @Test
    public void cnyWithARateIsConvertedAndSaysWhereTheRateCameFrom() {
        FxRate rate = rate(RATE_715, "bank rate");
        assertFalse(Currency.needsRate(Currency.CNY, rate));
        assertEquals("¥3.00", Currency.format(Currency.CNY, rate, 420_000L));

        String provenance = Currency.provenance(Currency.CNY, rate);
        assertNotNull(provenance);
        assertTrue(provenance.contains("7.15"));
        assertTrue("汇率是时点值，必须带日期", provenance.contains("2026-10-05"));
        assertTrue("来源是用户填的，要一起显示", provenance.contains("bank rate"));

        String withoutSource = Currency.provenance(Currency.CNY, rate(RATE_715, null));
        assertTrue(withoutSource.contains("7.15"));
        assertFalse("没填来源就别编一个", withoutSource.contains("null"));
    }

    @Test
    public void usdDisplayNeverMentionsARate() {
        assertEquals("$0.42", Currency.format(Currency.USD, rate(RATE_715, "bank"), 420_000L));
        assertNull(Currency.provenance(Currency.USD, rate(RATE_715, "bank")));
    }

    @Test
    public void unknownCurrencyCodeFallsBackToUsd() {
        assertEquals(Currency.USD, Currency.normalize("cny "));
        assertEquals(Currency.CNY, Currency.normalize("cny"));
        assertEquals(Currency.USD, Currency.normalize("CNY2"));
        assertEquals(Currency.USD, Currency.normalize(null));
    }

    // ==================== 按币种输入金额（月度上限） ====================

    @Test
    public void amountTypedInUsdIsStoredAsIs() {
        assertEquals(Long.valueOf(20_000_000L),
                Currency.enteredToUsdMicros(Currency.USD, FxRate.UNSET, "20"));
    }

    @Test
    public void amountTypedInCnyIsConvertedAtTheUsersRate() {
        // ¥143 / 7.15 = $20，正好整。
        assertEquals(Long.valueOf(20_000_000L),
                Currency.enteredToUsdMicros(Currency.CNY, rate(RATE_715, null), "143"));
    }

    @Test
    public void amountTypedInCnyWithoutARateIsNotStoredAtAll() {
        assertNull("折不出来就是折不出来：不许当美元存，也不许编个汇率",
                Currency.enteredToUsdMicros(Currency.CNY, FxRate.UNSET, "143"));
    }

    @Test
    public void blankAmountMeansNotSet() {
        assertEquals(Long.valueOf(0L),
                Currency.enteredToUsdMicros(Currency.CNY, FxRate.UNSET, "  "));
        assertEquals(Long.valueOf(0L),
                Currency.enteredToUsdMicros(Currency.USD, FxRate.UNSET, "abc"));
    }

    // ==================== 留痕：历史串 ====================

    @Test
    public void rateHistorySurvivesARoundTrip() {
        List<FxRate> entries = new ArrayList<>(Arrays.asList(
                rate(RATE_715, "bank rate"),
                new FxRate(7_100_000L, AT - 86_400_000L, null)));

        List<FxRate> decoded = FxRate.decodeHistory(FxRate.encodeHistory(entries));

        assertEquals(2, decoded.size());
        assertEquals(RATE_715, decoded.get(0).cnyPerUsdMicros);
        assertEquals(AT, decoded.get(0).enteredAtMillis);
        assertEquals("bank rate", decoded.get(0).source);
        assertEquals(7_100_000L, decoded.get(1).cnyPerUsdMicros);
        assertNull("没填来源就该是 null，不是空串", decoded.get(1).source);
    }

    @Test
    public void aSourceWithSeparatorsCannotBreakTheHistory() {
        FxRate messy = rate(RATE_715, "招行|现汇\n卖出价");
        List<FxRate> decoded = FxRate.decodeHistory(FxRate.encodeHistory(
                Arrays.asList(messy, new FxRate(7_100_000L, AT, "xe.com"))));

        assertEquals("一条备注里的分隔符不能把历史拆成三条", 2, decoded.size());
        assertEquals("招行 现汇 卖出价", decoded.get(0).source);
        assertEquals("xe.com", decoded.get(1).source);
    }

    @Test
    public void brokenHistoryRowsAreSkippedNotFatal() {
        List<FxRate> decoded = FxRate.decodeHistory(
                "oops\n" + AT + "|" + RATE_715 + "|bank\n" + AT + "|0|zero\n\n");

        assertEquals("坏行跳过，好行照旧；0 不是汇率", 1, decoded.size());
        assertEquals(RATE_715, decoded.get(0).cnyPerUsdMicros);
        assertTrue(FxRate.decodeHistory(null).isEmpty());
        assertTrue(FxRate.decodeHistory("").isEmpty());
    }

    @Test
    public void unsetRateIsNeverEncoded() {
        assertEquals("", FxRate.encodeHistory(Arrays.asList(FxRate.UNSET, null)));
    }
}
