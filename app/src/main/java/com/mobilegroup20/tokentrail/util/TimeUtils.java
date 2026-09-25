package com.mobilegroup20.tokentrail.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 全项目唯一的「一天」定义。
 *
 * <p><b>为什么要有这个类。</b>日结算是按日历天走的，而「今天是哪天」这个问题的答案
 * 取决于时区。如果每个模块各自 {@code LocalDate.now()}，那么一台设备认为已经跨天、
 * 另一台认为还没有，同一天会被结算两次或漏掉。所以：
 * <ul>
 *   <li>时区只在这里定义一次 {@link #ZONE}，别处不许再写；</li>
 *   <li>存储里一律用 UTC 毫秒（{@link com.mobilegroup20.tokentrail.contract.model.UsageCall#startedAtEpochMillis}），
 *       只在换算成「哪一天」的时候经过这里；</li>
 *   <li>「天」对外一律是 {@code yyyy-MM-dd} 字符串，跨数据库、跨 JSON、
 *       跨 Firestore 都长得一样，不会因为时区在传输中被重新解释。</li>
 * </ul>
 */
public final class TimeUtils {

    /**
     * 财务口径时区。定的是北京时间，因为它同时是账单周期和用户直觉的参照。
     *
     * <p>注意各家 provider 的账单是按 UTC 切天的，所以这里算出来的天和官方账单的
     * 分界线可能差几个小时。这是<b>已知的估算误差</b>，不是 bug——估算口径要写进
     * 界面的说明里（大纲 §10「所有数字都是估算」）。
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 日期格式。
     *
     * <p>两个细节都不能省：
     * <ul>
     *   <li>{@code uuuu} 而不是 {@code yyyy}——严格模式下 {@code yyyy}（纪元年）需要
     *       额外的纪元字段，会连合法日期都解析不了；</li>
     *   <li>{@link ResolverStyle#STRICT}——默认的 SMART 会把不存在的日期<b>改掉</b>：
     *       {@code 2026-02-30} 会被解析成 2 月 28 日。日志里一个脏日期就这样变成
     *       另一个有效日期，落在错的那天结算，而且没有任何报错。</li>
     * </ul>
     * 见 {@code TimeUtilsTest#isValidDayRejectsJunk}。
     */
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("uuuu-MM").withResolverStyle(ResolverStyle.STRICT);

    private TimeUtils() {
    }

    /** UTC 毫秒 → 北京时间的那一天，格式 {@code yyyy-MM-dd}。 */
    public static String dayOf(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate().format(DAY);
    }

    /** 北京时间今天。 */
    public static String today() {
        return LocalDate.now(ZONE).format(DAY);
    }

    /** 北京时间昨天。结算的上界就是它——今天还没过完。 */
    public static String yesterday() {
        return LocalDate.now(ZONE).minusDays(1).format(DAY);
    }

    /** UTC 毫秒 → 北京时间所在的月份，格式 {@code yyyy-MM}。赛季和预算用这个。 */
    public static String monthOf(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZONE).toLocalDate().format(MONTH);
    }

    /** 北京时间当前月份。 */
    public static String currentMonth() {
        return LocalDate.now(ZONE).format(MONTH);
    }

    /** 某个字符串是本月吗。用来判断赛季该不该重置。 */
    public static boolean isCurrentMonth(String month) {
        return currentMonth().equals(month);
    }

    /** {@code yyyy-MM-dd} 当天零点（北京时间）对应的 UTC 毫秒。 */
    public static long startOfDayEpochMillis(String day) {
        return LocalDate.parse(day, DAY).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    /** 天加上若干天，delta 可以是负数。 */
    public static String plusDays(String day, int delta) {
        return LocalDate.parse(day, DAY).plusDays(delta).format(DAY);
    }

    /**
     * 从 {@code from} 到 {@code to}（都含）的每一天。
     *
     * <p>结算的补齐就是拿这个列表逐天推进。to 早于 from 时返回空列表，
     * 不抛异常——「区间是空的」是正常情况，不该让调用方先判断。
     */
    public static List<String> daysBetween(String from, String to) {
        List<String> days = new ArrayList<>();
        if (from == null || to == null) {
            return days;
        }
        LocalDate cursor = LocalDate.parse(from, DAY);
        LocalDate end = LocalDate.parse(to, DAY);
        while (!cursor.isAfter(end)) {
            days.add(cursor.format(DAY));
            cursor = cursor.plusDays(1);
        }
        return days;
    }

    /** 月份的第一天，给「本月至今」这类查询拼区间用。 */
    public static String firstDayOfMonth(String month) {
        return LocalDate.parse(month + "-01", DAY).withDayOfMonth(1).format(DAY);
    }

    /**
     * 月份的最后一天。
     *
     * <p>为什么不能拿 {@link #yesterday()} 当上界：那只在查<b>当月</b>的时候凑巧对，
     * 查 7 月的用量时上界会落到今天，把 8 月、9 月的数据一起算进 7 月。
     * 这种错不会报错，只会让历史月份的数字偏大。
     */
    public static String lastDayOfMonth(String month) {
        return LocalDate.parse(month + "-01", DAY).withDayOfMonth(
                LocalDate.parse(month + "-01", DAY).lengthOfMonth()).format(DAY);
    }

    /**
     * 某个区间和「今天之前」的交集上界，用来把查询截到已结束的天。
     *
     * <p>结算是按天推进的，今天还没过完，把今天算进去会让余额在一天之内一直变。
     * 返回 null 表示这个区间整个都在未来，调用方应该当作空区间处理。
     */
    public static String clampToYesterday(String day) {
        if (day == null) {
            return null;
        }
        String yesterday = yesterday();
        return day.compareTo(yesterday) > 0 ? yesterday : day;
    }

    /** 某个日期是不是格式合法的 {@code yyyy-MM-dd}。解析外部日志时用来挡脏数据。 */
    public static boolean isValidDay(String day) {
        if (day == null || day.length() != 10) {
            return false;
        }
        try {
            LocalDate.parse(day, DAY);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 给界面看的写法，如 {@code 9月25日}。 */
    public static String formatForDisplay(String day) {
        if (!isValidDay(day)) {
            return day;
        }
        LocalDate date = LocalDate.parse(day, DAY);
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }
}
