package com.mobilegroup20.modelpilot.data.export;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一次导出请求：要什么范围、什么格式、用量取哪一段。
 *
 * <p>**不可变**。它是界面（选了什么）和导出逻辑（写了什么）之间唯一的一份约定，
 * 而 {@code meta} 里的「范围」就是照着它写的——如果这个对象能被中途改，
 * 导出的文件就会在头部声称一个和内容不符的范围，那比不写还糟。
 */
public final class ExportRequest {

    /** 导出范围，不能为空（空范围导出的文件没有任何意义）。 */
    public final Set<ExportScope> scopes;

    public final ExportFormat format;

    /**
     * 用量范围的起止日（{@code yyyy-MM-dd}，含当天）。{@code null} = 不限那一头。
     *
     * <p><b>只作用于用量。</b>对话没有「按日期范围导出」这个概念：一条对话是连续的
     * 上下文，砍掉中间几天等于交给用户一份读不通的记录（也和他的记忆对不上）。
     * 要少导出就少选范围，而不是切对话。
     */
    public final String fromDay;
    public final String toDay;

    /** 生成时刻，写进文件名和 {@code meta}。由调用方传进来，这样单测能钉住文件名。 */
    public final long generatedAtMillis;

    public ExportRequest(Set<ExportScope> scopes, ExportFormat format,
                         String fromDay, String toDay, long generatedAtMillis) {
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("导出范围不能为空");
        }
        if (fromDay != null && toDay != null && fromDay.compareTo(toDay) > 0) {
            // 不悄悄交换：交换之后 meta 里写的范围会和用户在界面上选的相反，
            // 而文件到了别人手里没人能发现这件事。
            throw new IllegalArgumentException("起止日反了：" + fromDay + " → " + toDay);
        }
        this.scopes = Collections.unmodifiableSet(EnumSet.copyOf(scopes));
        this.format = format;
        this.fromDay = fromDay;
        this.toDay = toDay;
        this.generatedAtMillis = generatedAtMillis;
    }

    /** 只导出用量、不限日期、JSON——命令行和测试里最常用的一种。 */
    public static ExportRequest usage(long generatedAtMillis) {
        return new ExportRequest(EnumSet.of(ExportScope.USAGE), ExportFormat.JSON,
                null, null, generatedAtMillis);
    }

    public boolean includes(ExportScope scope) {
        return scopes.contains(scope);
    }

    /** 用量那一段是不是全量（{@code meta} 里要如实写「全部」还是「某段」）。 */
    public boolean usageUnbounded() {
        return fromDay == null && toDay == null;
    }
}
