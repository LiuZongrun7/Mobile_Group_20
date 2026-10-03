package com.mobilegroup20.modelpilot.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

/**
 * 首页那一列时间的边界。
 *
 * <p>为什么值得单测：这段逻辑的坑全在边界上——刚好 60 秒、刚好跨午夜、
 * 刚好第 7 天、跨年。手点的话要改系统时间，而且改完就忘了上一次是什么状态。
 * 这里把"现在"当成参数传进去（{@link RelativeTime#of} 的第二个参数），
 * 于是每个边界都能被钉住。
 */
public class RelativeTimeTest {

    /** 2026-10-04（周日）12:00 本地时间，当"现在"。 */
    private static final long NOW = at(2026, Calendar.OCTOBER, 4, 12, 0);
    private static final Locale EN = Locale.US;

    @Test public void justNowIsNow() {
        assertEquals(RelativeTime.Unit.NOW, RelativeTime.of(NOW - 30_000L, NOW, EN).unit);
    }

    @Test public void exactlyOneMinuteIsMinutesNotNow() {
        RelativeTime.Label label = RelativeTime.of(NOW - 60_000L, NOW, EN);
        assertEquals(RelativeTime.Unit.MINUTES, label.unit);
        assertEquals(1L, label.amount);
    }

    @Test public void fiftyNineMinutesIsStillMinutes() {
        assertEquals(RelativeTime.Unit.MINUTES,
                RelativeTime.of(NOW - 59L * 60_000L, NOW, EN).unit);
    }

    @Test public void exactlyOneHourIsHours() {
        RelativeTime.Label label = RelativeTime.of(NOW - 3_600_000L, NOW, EN);
        assertEquals(RelativeTime.Unit.HOURS, label.unit);
        assertEquals(1L, label.amount);
    }

    @Test public void futureTimeIsNowNotNegative() {
        // 设备时钟被往回调过：显示 "Now" 比 "-3m" 老实。
        assertEquals(RelativeTime.Unit.NOW, RelativeTime.of(NOW + 180_000L, NOW, EN).unit);
    }

    /** **日历上的昨天**：昨晚 23:30 距今不到 24 小时，但它是"昨天"。 */
    @Test public void lastNightIsYesterdayNotHours() {
        long lastNight = at(2026, Calendar.OCTOBER, 3, 23, 30);
        assertEquals(RelativeTime.Unit.YESTERDAY, RelativeTime.of(lastNight, NOW, EN).unit);
    }

    /** 今天 00:10 距今 11 小时多，它仍然是"今天"（小时数），不是"昨天"。 */
    @Test public void earlierTodayIsHoursEvenPastMidnight() {
        assertEquals(RelativeTime.Unit.HOURS,
                RelativeTime.of(at(2026, Calendar.OCTOBER, 4, 0, 10), NOW, EN).unit);
    }

    @Test public void sixDaysAgoIsWeekday() {
        assertEquals(RelativeTime.Unit.WEEKDAY,
                RelativeTime.of(at(2026, Calendar.SEPTEMBER, 28, 9, 0), NOW, EN).unit);
    }

    /** 第 7 天是这条线的边界：8 天前要给日期，不能给星期几（两个"周一"分不清）。 */
    @Test public void sevenDaysAgoIsDateNotWeekday() {
        assertEquals(RelativeTime.Unit.DATE,
                RelativeTime.of(at(2026, Calendar.SEPTEMBER, 27, 9, 0), NOW, EN).unit);
    }

    @Test public void sameYearShowsMonthAndDayWithoutYear() {
        RelativeTime.Label label = RelativeTime.of(at(2026, Calendar.JANUARY, 9, 9, 0), NOW, EN);
        assertEquals(RelativeTime.Unit.DATE, label.unit);
        assertNotNull(label.text);
        // 今年内的日期不带年份：界面上那一列放不下，而且"今年"是默认语境。
        assertEquals("Jan 9", label.text);
    }

    @Test public void previousYearCarriesTheYear() {
        RelativeTime.Label label = RelativeTime.of(at(2025, Calendar.DECEMBER, 31, 9, 0), NOW, EN);
        assertEquals(RelativeTime.Unit.DATE_WITH_YEAR, label.unit);
        assertEquals("Dec 31, 2025", label.text);
    }

    @Test public void weekdayAndDateCarryTextButNumbersDoNot() {
        assertNotNull(RelativeTime.of(at(2026, Calendar.OCTOBER, 1, 9, 0), NOW, EN).text);
        assertNull(RelativeTime.of(NOW - 5 * 60_000L, NOW, EN).text);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, hour, minute, 0);
        return c.getTimeInMillis();
    }
}
