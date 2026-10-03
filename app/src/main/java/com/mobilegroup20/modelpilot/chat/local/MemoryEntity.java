package com.mobilegroup20.modelpilot.chat.local;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 压缩产物的持久化形态（`Memory` 的落库版本）。
 *
 * <p>它**属于对话，不属于任何 provider**——这正是"压一次、改写所有上下文"能成立的前提。
 * `madeByProvider` / `madeByModel` 记的是这次压缩是谁做的：压缩模型是用户在设置里选的，
 * 事后要能回答"这段摘要是谁写的、花了多少"。
 */
@Entity(tableName = "memory", indices = {@Index(value = {"chat_id", "created_at_epoch_millis"})})
public class MemoryEntity {

    @PrimaryKey @NonNull
    public String id = "";

    @ColumnInfo(name = "chat_id") public String chatId = "";

    /** 覆盖区间（含两端）。这两条消息仍在 `message` 表里，只是不再进上下文。 */
    @ColumnInfo(name = "from_message_id") public String fromMessageId = "";
    @ColumnInfo(name = "to_message_id") public String toMessageId = "";

    @ColumnInfo(name = "summary") public String summary = "";

    @ColumnInfo(name = "made_by_provider") public String madeByProvider = "";
    @ColumnInfo(name = "made_by_model") public String madeByModel = "";

    @ColumnInfo(name = "tokens_in") public long tokensIn;
    @ColumnInfo(name = "tokens_out") public long tokensOut;

    /** 用户手动改过：界面上要能看出来这不是模型写的了。 */
    @ColumnInfo(name = "edited_by_user") public boolean editedByUser;

    @ColumnInfo(name = "created_at_epoch_millis") public long createdAtEpochMillis;
}
