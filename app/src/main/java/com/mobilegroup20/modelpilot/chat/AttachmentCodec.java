package com.mobilegroup20.modelpilot.chat;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 附件的落库形态：{@link CanonicalMessage.Attachment} ⇄ `message.attachments_json`。
 *
 * <p>为什么用 JSON 塞一列、而不是给附件开一张表：附件永远是"跟着某一条消息"读出来的，
 * 从来没有"单独查所有附件"这种需求，为它开表要加主键、外键、级联删除三样东西，
 * 换来的只是更规整——而代价是每读一条消息多一次 join。
 * （这段权衡在 `MessageEntity.attachmentsJson` 的注释里也写着。）
 *
 * <p><b>用 Gson 而不是 org.json</b>：`org.json` 在 JVM 单测里是"没实现"的
 * （调用直接抛 RuntimeException），而这个编解码是最该被单测钉住的一段——
 * 它是"附件能不能活过重启"的唯一保证。
 *
 * <p><b>解不出来就当没有附件</b>（返回空表），不抛异常：一条读不懂的旧记录
 * 不该让整条对话打不开（和 `role` 的容错是同一个口径）。
 */
public final class AttachmentCodec {

    private static final Gson GSON = new Gson();

    private AttachmentCodec() {
    }

    /** 附件表 → JSON 字符串；空表返回 null（**不写 `"[]"`**：空和"没有这一列"是一回事）。 */
    @Nullable
    public static String toJson(List<CanonicalMessage.Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        JsonArray array = new JsonArray();
        for (CanonicalMessage.Attachment attachment : attachments) {
            JsonObject item = new JsonObject();
            item.addProperty("kind", attachment.kind.name());
            item.addProperty("fileName", attachment.fileName);
            item.addProperty("uri", attachment.uri);
            item.addProperty("bytes", attachment.bytes);
            if (attachment.extractedText != null) {
                item.addProperty("text", attachment.extractedText);
            }
            array.add(item);
        }
        return GSON.toJson(array);
    }

    /** JSON 字符串 → 附件表。null / 空串 / 解析失败都返回空表（见类注释）。 */
    public static List<CanonicalMessage.Attachment> fromJson(@Nullable String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<CanonicalMessage.Attachment> attachments = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) {
                return Collections.emptyList();
            }
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String kindName = item.has("kind") ? item.get("kind").getAsString() : "";
                CanonicalMessage.Attachment.Kind kind;
                try {
                    kind = CanonicalMessage.Attachment.Kind.valueOf(kindName);
                } catch (IllegalArgumentException unknownKind) {
                    // 认不出的类型跳过这一条，而不是整条消息打不开。
                    continue;
                }
                attachments.add(new CanonicalMessage.Attachment(kind,
                        string(item, "fileName"), string(item, "uri"),
                        item.has("bytes") ? item.get("bytes").getAsLong() : 0L,
                        item.has("text") ? item.get("text").getAsString() : null));
            }
        } catch (RuntimeException unreadable) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(attachments);
    }

    private static String string(JsonObject item, String name) {
        return item.has(name) && !item.get(name).isJsonNull() ? item.get(name).getAsString() : "";
    }
}
