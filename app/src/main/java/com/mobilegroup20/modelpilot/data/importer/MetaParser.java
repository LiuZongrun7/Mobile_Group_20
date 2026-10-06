package com.mobilegroup20.modelpilot.data.importer;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 读导出里那段 {@code meta}（文件的"自述"）。
 *
 * <p>JSON 与 CSV（{@code meta.json}）两条路读的是同一份东西，所以只有这一处实现——
 * 两份实现迟早在某个字段上分叉，而 {@code meta} 恰恰是我们在界面上要念给用户听的。
 *
 * <p>这里只读、不判断「是不是我们的文件」：那条判断在 {@link ExportFileReader} 里
 * （统一一处，两条路都经过它）。
 */
final class MetaParser {

    private MetaParser() {
    }

    /**
     * @param hasConversations 解析器实际读到的内容（不是自述里的 {@code scopes}）——
     *                         自述可以骗人，行不会。
     */
    static ImportFacts parse(@Nullable JsonObject meta, boolean hasConversations,
                            boolean hasUsage) {
        if (meta == null) {
            return new ImportFacts(null, null, 0L, null, null, null,
                    hasConversations, hasUsage, false);
        }
        return new ImportFacts(
                ImportValues.str(meta, "app"),
                ImportValues.str(meta, "appVersion"),
                ImportValues.number(meta, "generatedAtEpochMillis",
                        // 老文件可能只有人看的那个字符串；退回去解析一下，读不懂就是 0。
                        ImportValues.parseIso(ImportValues.str(meta, "generatedAt"))),
                ImportValues.str(meta, "account"),
                strings(array(meta, "scopes")),
                strings(array(meta, "notes")),
                hasConversations,
                hasUsage,
                true);
    }

    /** 取一个数组字段；不是数组、是 null、根本没有，都当空表。 */
    @Nullable
    private static JsonArray array(@Nullable JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return null;
        }
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static List<String> strings(@Nullable JsonArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (JsonElement element : array) {
            if (element != null && element.isJsonPrimitive()) {
                try {
                    out.add(element.getAsString());
                } catch (RuntimeException skip) {
                    // 一个读不动的元素不该让整段自述失败——自述只是给人看的。
                }
            }
        }
        return out;
    }
}
