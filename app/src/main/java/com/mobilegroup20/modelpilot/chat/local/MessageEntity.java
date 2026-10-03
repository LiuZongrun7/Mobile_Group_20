package com.mobilegroup20.modelpilot.chat.local;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 规范化消息的持久化形态（`CanonicalMessage` 的落库版本）。
 *
 * <p>**为什么不直接存某家的 JSON**：这个对话随时会被换到另一家（那是本项目的卖点），
 * 一旦把某家的格式当成事实来源，换过去就要反解析一次，而"读回来再写出去"一定会丢东西
 * （多模态附件、工具调用 id、system 位置最容易掉）。所以库里存这一份"哪家都不像"的，
 * 各家的形态由渲染器现算并缓存。
 */
@Entity(tableName = "message", indices = {@Index(value = {"chat_id", "created_at_epoch_millis"})})
public class MessageEntity {

    @PrimaryKey @NonNull
    public String id = "";

    @ColumnInfo(name = "chat_id") public String chatId = "";

    /** `SYSTEM` / `USER` / `ASSISTANT` / `TOOL`。存字符串而不是 ordinal：
     * 枚举顺序一变，老行就会被读成另一种角色（Room 转换器那条注释里踩过）。 */
    @ColumnInfo(name = "role") public String role = "USER";

    @ColumnInfo(name = "text") public String text = "";

    /** 附件与工具调用的 JSON（结构小、只在本机用，不值得为它开两张表）。 */
    @ColumnInfo(name = "attachments_json") public String attachmentsJson;
    @ColumnInfo(name = "tool_calls_json") public String toolCallsJson;
    @ColumnInfo(name = "tool_call_id") public String toolCallId;

    /** 真实用量（模型返回后回填）。0 = 还不知道。 */
    @ColumnInfo(name = "tokens_in") public long tokensIn;
    @ColumnInfo(name = "tokens_out") public long tokensOut;

    /** 这次是谁答的（手动选的还是 Auto 挑的都要能追）。 */
    @ColumnInfo(name = "provider_id") public String providerId;
    @ColumnInfo(name = "model_id") public String modelId;
    @ColumnInfo(name = "route") public String route;

    @ColumnInfo(name = "created_at_epoch_millis") public long createdAtEpochMillis;
}
