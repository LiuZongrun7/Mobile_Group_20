package com.mobilegroup20.modelpilot.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.List;

/**
 * 天边界的测试。
 *
 * <p>这个类最该被测：日结算是按天推进的，如果「哪一天」算错一个，资源就会多发
 * 或者少发一天，而且这种错在小数据量下看不出来——一天的 token 差额淹没在总量里。
 *
 * <p>纯 JUnit，不需要模拟器：{@link TimeUtils} 不依赖任何 Android 类。
 */
public class TimeUtilsTest {

    /** 北京时间比 UTC 早 8 小时，所以 UTC 16:00 就是北京的第二天零点。 */
    @Test
    public void dayBoundaryIsBeijingMidnight() {
        // 北京 2026-09-24 23:59:59
        assertEquals("2026-09-24",
                TimeUtils.dayOf(Instant.parse("2026-09-24T15:59:59Z").toEpochMilli()));

        // 北京 2026-09-25 00:00:00 —— 跨天的正是这一秒
        assertEquals("2026-09-25",
                TimeUtils.dayOf(Instant.parse("2026-09-24T16:00:00Z").toEpochMilli()));
    }

    /** 存下来的时刻和算回去的零点必须是同一个点，否则结算区间会整体偏一天。 */
    @Test
    public void startOfDayRoundTrips() {
        long nineAm = Instant.parse("2026-09-25T01:00:00Z").toEpochMilli(); // 北京 09:00
        String day = TimeUtils.dayOf(nineAm);
        assertEquals("2026-09-25", day);

        long midnight = TimeUtils.startOfDayEpochMillis(day);
        assertEquals(Instant.parse("2026-09-24T16:00:00Z").toEpochMilli(), midnight);
        assertTrue(midnight <= nineAm);
    }

    @Test
    public void daysBetweenIsInclusiveOnBothEnds() {
        List<String> days = TimeUtils.daysBetween("2026-09-01", "2026-09-03");
        assertEquals(3, days.size());
        assertEquals("2026-09-01", days.get(0));
        assertEquals("2026-09-03", days.get(2));
    }

    /** 区间反了或者为空是正常情况（比如本月还没过完），要返回空列表而不是抛异常。 */
    @Test
    public void daysBetweenToleratesEmptyAndReversedRanges() {
        assertTrue(TimeUtils.daysBetween("2026-09-03", "2026-09-01").isEmpty());
        assertTrue(TimeUtils.daysBetween(null, "2026-09-03").isEmpty());
        assertEquals(1, TimeUtils.daysBetween("2026-09-03", "2026-09-03").size());
    }

    @Test
    public void plusDaysCrossesMonthAndYearBoundaries() {
        assertEquals("2026-09-01", TimeUtils.plusDays("2026-08-31", 1));
        assertEquals("2026-08-31", TimeUtils.plusDays("2026-09-01", -1));
        assertEquals("2027-01-01", TimeUtils.plusDays("2026-12-31", 1));
        // 2028 是闰年
        assertEquals("2028-02-29", TimeUtils.plusDays("2028-02-28", 1));
    }

    @Test
    public void firstDayOfMonthIgnoresDayPart() {
        assertEquals("2026-09-01", TimeUtils.firstDayOfMonth("2026-09"));
        assertEquals("2026-12-01", TimeUtils.firstDayOfMonth("2026-12"));
    }

    /** 外部日志里的日期字段要先过这一关，脏数据不能进汇总。 */
    @Test
    public void isValidDayRejectsJunk() {
        assertTrue(TimeUtils.isValidDay("2026-09-25"));
        assertFalse(TimeUtils.isValidDay("2026-9-5"));
        assertFalse(TimeUtils.isValidDay("2026-13-01"));
        assertFalse(TimeUtils.isValidDay("2026-02-30"));
        assertFalse(TimeUtils.isValidDay(""));
        assertFalse(TimeUtils.isValidDay(null));
    }

    @Test
    public void monthOfUsesTheSameZone() {
        // 北京 2026-10-01 00:30，UTC 还是 9 月 30 日——按 UTC 算会归错月
        long epochMillis = Instant.parse("2026-09-30T16:30:00Z").toEpochMilli();
        assertEquals("2026-10", TimeUtils.monthOf(epochMillis));
    }
}
