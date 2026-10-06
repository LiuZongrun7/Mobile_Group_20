package com.mobilegroup20.modelpilot.data.importer;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.OffsetDateTime;

/**
 * 读导出文件时用到的那几个"读不准就别装懂"的小工具。
 *
 * <p>导出那边的规矩是「不知道就留空」，这一边对应的是「留空就当不知道」：
 * 一个字段缺失或类型不对，返回的是 {@code null} / 0 / 空串，<b>绝不猜</b>。
 * 猜一个值进来的后果比缺一个值严重得多——缺了用户看得出来，猜错了他看不出来。
 *
 * <p>为什么单独一个类：JSON 与 CSV 两条路都要用同一套转换（毫秒字段、角色、
 * 时间字符串），两处各写一份迟早会出现"JSON 读出来的时间和 CSV 读出来的不一样"。
 */
final class ImportValues {

    private ImportValues() {
    }

    /** 字符串；没有 / 不是字符串 / 是 {@code null} 都给 {@code null}。 */
    @Nullable
    static String str(@Nullable JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsString();
        } catch (RuntimeException notAString) {
            return null;
        }
    }

    /** 字符串，空当空串用（写进不能为 null 的列）。 */
    static String strOrEmpty(@Nullable JsonObject object, String key) {
        String value = str(object, key);
        return value == null ? "" : value;
    }

    /** 整数；读不出来给 {@code fallback}。 */
    static long number(@Nullable JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    /** 可空的整数（金额那几列）：读不出来就是「不知道」，不是 0。 */
    @Nullable
    static Long nullableNumber(@Nullable JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return null;
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    /** 布尔；读不出来给 {@code fallback}。 */
    static boolean bool(@Nullable JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException notABoolean) {
            return fallback;
        }
    }

    /**
     * 时间：<b>先认毫秒</b>（{@code ...EpochMillis}，那是导出里的事实来源），
     * 毫秒没有才退回解析人看的 ISO 字符串。
     *
     * <p>为什么要退这一步：CSV 里两列都有，而用户可能手工删掉一列（他大概会留下
     * 看得懂的那列）。退回去解析 ISO 不改变任何结果，只是让一份被手工编辑过的文件
     * 仍然能用。解析不动就是 0——「不知道」，不是"1970 年"。
     */
    static long millis(@Nullable JsonObject object, String key, String isoKey) {
        long direct = number(object, key, 0L);
        if (direct > 0L) {
            return direct;
        }
        return parseIso(str(object, isoKey));
    }

    /** {@code 2026-10-06T15:30:12+08:00} → 毫秒；读不懂就是 0。 */
    static long parseIso(@Nullable String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return 0L;
        }
        try {
            return OffsetDateTime.parse(iso.trim()).toInstant().toEpochMilli();
        } catch (RuntimeException notIso) {
            return 0L;
        }
    }

    /**
     * 角色：只认导出会写的四个值（{@code SYSTEM}/{@code USER}/{@code ASSISTANT}/{@code TOOL}），
     * <b>认不出就退回 {@code USER}</b>。
     *
     * <p>为什么不认识就整条丢掉：一条消息的正文比它的角色重要得多（用户宁可看到
     * 一条角色标错的消息，也不想看到它凭空消失）。为什么退回 {@code USER} 而不是
     * {@code ASSISTANT}：认不出的东西说成"用户说的"最不容易让人误以为是我们生成的。
     * 这和 {@code MessageEntity} 里 {@code role} 存字符串而不是 ordinal 是同一个理由
     * ——枚举顺序一变，老行会被读成另一种角色。
     */
    static String role(@Nullable String raw) {
        if (raw == null) {
            return "USER";
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("SYSTEM".equals(value) || "USER".equals(value)
                || "ASSISTANT".equals(value) || "TOOL".equals(value)) {
            return value;
        }
        return "USER";
    }
}
