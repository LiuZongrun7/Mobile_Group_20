package com.mobilegroup20.modelpilot.data.export;

import com.mobilegroup20.modelpilot.util.TimeUtils;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * 导出里出现的两种时间写法。
 *
 * <p>毫秒数是事实来源，但只有毫秒的导出文件对人没用（没人能心算 {@code 1780000000000}
 * 是哪天），所以导出同时给「人看的」时间字符串。
 *
 * <p><b>贴的是 {@code +08:00} 而不是 {@code Z}。</b>账本的「一天」是按北京时间切的
 * （{@link TimeUtils#ZONE}，这是全项目唯一的时区定义，不在这里另写一个），
 * 如果导出的时间戳用 UTC 写，那么「9 月 30 日的记录」在文件里会显示成 9 月 29 日 16:00，
 * 用户拿它跟界面一对就以为我们算错了天。偏移量写在字符串里，两边的口径都能看见。
 *
 * <p><b>为什么是 public</b>：导入那边（{@code data/importer}）要在预览里显示
 * "这个文件是什么时候导出的"，用的必须是同一套写法——两份实现迟早在某个偏移量上分叉，
 * 而那是用户唯一能拿来对时间的东西。
 */
public final class ExportTime {

    /** {@code 2026-10-06T15:30:12+08:00}。带偏移量，任何解析器都不会误读。 */
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");

    /** {@code 20261006-1530}，只用于文件名：不带冒号，任何文件系统都能用。 */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmm");

    private ExportTime() {
    }

    static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(TimeUtils.ZONE).format(ISO);
    }

    /** 给导入那边用的同一个写法（见类注释最后一段）。 */
    public static String isoForDisplay(long epochMillis) {
        return iso(epochMillis);
    }

    static String stamp(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(TimeUtils.ZONE).format(STAMP);
    }
}
