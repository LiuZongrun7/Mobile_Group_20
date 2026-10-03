package com.mobilegroup20.modelpilot.chat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 首页那一列相对时间（设计稿里的 `Now` / `Yesterday` / `Mon`）。
 *
 * <p>**为什么不用 `DateUtils.getRelativeTimeSpanString`**：它给出的是
 * "0 minutes ago" / "1 day ago" 这种句子。设计稿那一列只有约 70px 宽，
 * 放不下句子；而且"1 day ago"和"Yesterday"在日历上的意思不一样
 * （跨没跨过午夜），后者才是用户脑子里的那个。
 *
 * <p><b>只返回"类型 + 数字"，不返回拼好的英文</b>：真正显示的文字来自
 * `strings.xml`（"Yesterday" 之类是要翻译的），而星期几 / 月日是从 {@link Locale}
 * 算出来的（不要翻译，翻译了反而错——中文环境就该显示"周一"）。所以这里
 * 只做**判断**，拼字符串留给界面。
 *
 * <p>这个类不碰 Android，所以能被单测钉住：时间这东西的边界（刚好 60 秒、
 * 刚好跨午夜、刚好第 7 天）靠手点是点不全的。
 */
public final class RelativeTime {

    /** 显示成什么。数字类的取 {@link Label#amount}。 */
    public enum Unit {
        /** 刚刚（不足 1 分钟，或时间在将来——时钟飘了也得说人话）。 */
        NOW,
        MINUTES,
        HOURS,
        /** **日历上的**昨天（不是"24 小时前"）。 */
        YESTERDAY,
        /** 最近一周内：显示星期几。 */
        WEEKDAY,
        /** 今年内：显示月日。 */
        DATE,
        /** 跨年了：显示年月日。**跨年必须写年份**，否则"10月4日"会被当成今年的。 */
        DATE_WITH_YEAR
    }

    public static final class Label {
        public final Unit unit;
        /** 只有 {@link Unit#MINUTES} / {@link Unit#HOURS} 用得到。 */
        public final long amount;
        /** 星期几 / 月日的文字，由 {@link Locale} 算出；其余类型为 null。 */
        public final String text;

        Label(Unit unit, long amount, String text) {
            this.unit = unit;
            this.amount = amount;
            this.text = text;
        }
    }

    private RelativeTime() {
    }

    /**
     * @param thenMillis 这条对话最后活跃的时间
     * @param nowMillis  现在（由调用方传进来，**不要在这里读系统时钟**：
     *                   读时钟的代码没法测，"刚好跨午夜"那一类边界只能靠运气撞上）
     * @param locale     星期几、月日的写法（中文环境出"周一"）
     */
    public static Label of(long thenMillis, long nowMillis, Locale locale) {
        Locale safeLocale = locale == null ? Locale.getDefault() : locale;
        long delta = nowMillis - thenMillis;
        if (delta < 60_000L) {
            // 负数（时间在将来）也走这条：设备时钟被改过时，"Now" 比"-3 分钟"老实。
            return new Label(Unit.NOW, 0, null);
        }
        if (delta < 3_600_000L) {
            return new Label(Unit.MINUTES, delta / 60_000L, null);
        }

        Calendar then = dayOf(thenMillis);
        Calendar today = dayOf(nowMillis);
        Calendar yesterday = dayOf(nowMillis);
        yesterday.add(Calendar.DAY_OF_YEAR, -1);

        if (sameDay(then, today)) {
            return new Label(Unit.HOURS, delta / 3_600_000L, null);
        }
        if (sameDay(then, yesterday)) {
            return new Label(Unit.YESTERDAY, 0, null);
        }
        if (isWithinLastWeek(then, today)) {
            return new Label(Unit.WEEKDAY, 0, format(thenMillis, "EEE", safeLocale));
        }
        if (then.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
            return new Label(Unit.DATE, 0, format(thenMillis, "MMM d", safeLocale));
        }
        return new Label(Unit.DATE_WITH_YEAR, 0, format(thenMillis, "MMM d, yyyy", safeLocale));
    }

    /** 落在"今天往前数 7 天"之内（含今天）。 */
    private static boolean isWithinLastWeek(Calendar then, Calendar today) {
        Calendar floor = (Calendar) today.clone();
        floor.add(Calendar.DAY_OF_YEAR, -6);
        return !then.before(floor);
    }

    /**
     * 是不是同一个日历日。
     *
     * <p>用年 + 一年中的第几天比，而不是 {@code Calendar.equals}：后者连时区、
     * 是否夏令时都一起比，两个同样"今天 0 点"的 Calendar 可能因为
     * 构造方式不同而不相等——那种坑只在跨夏令时的那两天出现，最难查。
     */
    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static Calendar dayOf(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        // 归零到当天 0 点：比较的是"日历上的同一天"，不是"相差 24 小时以内"。
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static String format(long millis, String pattern, Locale locale) {
        return new SimpleDateFormat(pattern, locale).format(new Date(millis));
    }
}
