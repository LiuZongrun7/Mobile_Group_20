package com.mobilegroup20.modelpilot.data.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入这一屏上要显示的那几句话。<b>纯静态、纯字符串、不碰 Android</b>。
 *
 * <p>为什么要单独一个类而不是直接写在 {@code ImportSheet} 里：写在弹层里就只能靠
 * 仪器测试去验，而这些句子恰恰是"错了会骗人"的部分——
 * <ul>
 *   <li>本机已经有 0 条时必须说「一条都不在本机」，不能说「0 of these are already here」：
 *       后者读起来像"有 0 条重复"，而重复和不重复对用户是两件不同的事；</li>
 *   <li>「跳过了 N 条」不能单独出现，它听起来像做成了什么，而用户问的是"数据回来了没有"；</li>
 *   <li>结果里必须同时说"写进去多少"和"有几行没读进来"，只说前者会让一份残缺的文件
 *       看起来和完整的一样。</li>
 * </ul>
 * 分开之后这些句子都能用 JUnit 钉住，而界面那一层只剩"把这几行塞进哪个 TextView"。
 *
 * <p>文案是英文，和 {@code export_*} 那一套一致（这一屏和导出是同一件事的两半）。
 */
public final class ImportWording {

    private ImportWording() {
    }

    /**
     * 预览区那几行：有多少条、涵盖哪段日期、本机已经有几条、这个文件是不是被手工改过。
     */
    public static List<String> previewLines(ImportPreview preview) {
        List<String> lines = new ArrayList<>();
        lines.add(preview.describeCounts());
        if (preview.usageCalls() > 0) {
            String range = preview.describeUsageRange();
            if (!range.isEmpty()) {
                // 日期范围是这个文件最容易被误读的地方：一份"最近 7 天"的导出看起来和
                // 全量的一样，导进去之后用户才发现少了半年。
                lines.add("Usage records cover " + range + ".");
            }
        }
        int already = preview.alreadyOnDevice();
        lines.add(already == 0
                ? "None of these conversations are on this phone yet."
                : already + " of these conversations are already on this phone.");
        if (preview.hasInternalDuplicates()) {
            lines.add("This file lists the same conversation id more than once, so it was "
                    + "probably edited by hand.");
        }
        return lines;
    }

    /**
     * 写完之后那一句：{@link ImportSummary#describe()} 加上"有几行没读进来"。
     *
     * <p>两件事必须一起说。只报写进去多少的话，一份有 30 行读不动的文件在屏幕上
     * 和一份完整的文件一模一样——而用户会据此以为数据齐了。
     */
    public static String resultLine(ImportSummary summary) {
        String base = summary.describe();
        int unread = summary.problems.size();
        if (unread == 0) {
            return base;
        }
        return base + " · " + unread + " rows in this file could not be read";
    }

    /** 文件那一行的自述：「文件名 · 大小 · 格式」。 */
    public static String sourceLine(String fileName, String size, String format) {
        return fileName + " · " + size + " · " + format;
    }

    /** 「Exported by ModelPilot 1.0 · 2026-10-06T15:30:12+08:00」。两者都没有就如实说没有。 */
    public static String factsLine(ImportFacts facts) {
        boolean noVersion = facts.appVersion.isEmpty();
        boolean noTime = facts.generatedAtEpochMillis <= 0L;
        if (noVersion && noTime) {
            return "This file doesn't say which version of the app exported it.";
        }
        StringBuilder out = new StringBuilder("Exported by ")
                .append(ImportFacts.APP);
        if (!noVersion) {
            out.append(' ').append(facts.appVersion);
        }
        if (!noTime) {
            out.append(" · ").append(facts.describeGeneratedAt());
        }
        return out.toString();
    }
}
