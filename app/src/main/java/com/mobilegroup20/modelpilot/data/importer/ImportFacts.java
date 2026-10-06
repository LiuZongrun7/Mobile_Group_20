package com.mobilegroup20.modelpilot.data.importer;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * 一个导出文件<b>自己说</b>的那些事（导出产物里 {@code meta} 那一段）。
 *
 * <p>为什么把「文件说什么」和「文件里有什么」分成两个对象：{@link ImportBundle} 是内容，
 * 这个是<b>自述</b>。自述<b>不参与任何写库判断</b>——它是拿来给用户看的（哪一版 App 导的、
 * 什么时候导的、导的人是谁、当初故意省掉了什么），外加一条「这文件是不是我们家的」。
 * 反过来写（用自述里的条数决定导入多少条）是最危险的一种做法：改一个数字就能让我们
 * 少写或多写。条数一律从实际行数数出来。
 *
 * <p>{@link #notes} 是导出里那段人话，<b>原样带过来、原样显示</b>：
 * 「附件不带本机路径」「消息上没有 token 表示当时没拿到」这些取舍是跟着文件走的，
 * 我们这边不重写一遍——重写迟早会和导出那边分叉，而分叉之后用户看到的就是两种说法。
 */
public final class ImportFacts {

    /** 导出时写进 {@code meta.app} 的那个名字（见 {@code DataExporter.APP_NAME}）。 */
    public static final String APP = "ModelPilot";

    /** 文件里 {@code meta.app} 的原文；没有这个键就是空串。 */
    public final String app;

    /** 导出这个文件的 App 版本；文件里没有就是空串。 */
    public final String appVersion;

    /** 导出时刻，UTC 毫秒；文件里没有或读不懂就是 0。 */
    public final long generatedAtEpochMillis;

    /** 导出那台设备上的账号（现在导出时写的是 {@code local}）。只作展示。 */
    public final String account;

    /** 文件自称导了哪几半（{@code CONVERSATIONS} / {@code USAGE}），按文件里的顺序。 */
    public final List<String> scopes;

    /** 导出文件里那段人话，逐条原样保留。 */
    public final List<String> notes;

    /** 文件里有没有对话这一半（JSON 的 {@code conversations} / CSV 里的表）。 */
    public final boolean hasConversations;

    /** 文件里有没有用量这一半。 */
    public final boolean hasUsage;

    public ImportFacts(@Nullable String app, @Nullable String appVersion,
                       long generatedAtEpochMillis, @Nullable String account,
                       @Nullable List<String> scopes, @Nullable List<String> notes,
                       boolean hasConversations, boolean hasUsage, boolean hasMeta) {
        this.app = app == null ? "" : app;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.generatedAtEpochMillis = generatedAtEpochMillis;
        this.account = account == null ? "" : account;
        this.scopes = scopes == null ? Collections.<String>emptyList() : scopes;
        this.notes = notes == null ? Collections.<String>emptyList() : notes;
        this.hasConversations = hasConversations;
        this.hasUsage = hasUsage;
        this.hasMeta = hasMeta;
    }

    /**
     * 文件自称是我们 App 导出的。
     *
     * <p>为什么要看这一条：用户可能选错文件（相册里那张图、别的 App 的备份）。
     * 选错文件最坏的表现不是报错，而是<b>静默什么都不导</b>——用户以为数据回来了。
     * 所以 {@code meta.app} 有值且不是我们的时候就明确失败，并把读到的值告诉用户；
     * <b>没有这个键</b>则不算错（格式本身已经过了校验，老文件可能没这一段）。
     */
    public boolean isFromThisApp() {
        return app.isEmpty() || APP.equals(app);
    }

    /** 导出时刻的展示形态（北京时间，与导出文件名同一套口径）；没有就是空串。 */
    public String describeGeneratedAt() {
        return generatedAtEpochMillis > 0L
                ? com.mobilegroup20.modelpilot.data.export.ExportTime
                        .isoForDisplay(generatedAtEpochMillis)
                : "";
    }

    /** 文件自称的那几半，给预览用。 */
    public String describeScopes() {
        return scopes.isEmpty() ? "—" : String.join(" + ", scopes);
    }

    /**
     * 文件里到底有没有 {@code meta} 这一段。
     *
     * <p>为什么要单独记这一条：解析器对一个"合法的 JSON 对象"是来者不拒的
     * （要对字段缺失容错就只能这样），于是一份恰好以 <code>{</code> 开头的别的文件
     * （别的 App 的备份、被改过名的配置）会读成"空自述 + 空对话"，然后被当成
     * 「合法但没内容」——用户看到的是"这个文件里没东西"，而真相是<b>这不是我们的文件</b>。
     * 这两句话意味着完全不同的下一步（一句是"重新导一次"，一句是"你选错文件了"）。
     */
    public final boolean hasMeta;
}
