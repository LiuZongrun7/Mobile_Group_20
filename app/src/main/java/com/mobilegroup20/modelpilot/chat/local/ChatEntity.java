package com.mobilegroup20.modelpilot.chat.local;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 一条对话。设计稿首页每条对话下面那行 `Last · GPT-5.5` 徽章读的是
 * {@link #lastProviderId} / {@link #lastModelId}——**记的是最后一次实际回答它的模型**，
 * 不是"当前选中"（用户可能已经切到别的家，"这对话刚才是谁答的"才是他关心的）。
 */
@Entity(tableName = "chat", indices = {@Index(value = {"project_id"}),
        @Index(value = {"updated_at_epoch_millis"})})
public class ChatEntity {

    @PrimaryKey @NonNull
    public String id = "";

    /** 可为空串：不属于任何项目的散聊。 */
    @ColumnInfo(name = "project_id") public String projectId = "";

    @ColumnInfo(name = "title") public String title = "";

    /** 最后一次实际回答的模型；从没答过就是 null（界面不显示徽章，而不是显示"未知"）。 */
    @ColumnInfo(name = "last_provider_id") public String lastProviderId;
    @ColumnInfo(name = "last_model_id") public String lastModelId;

    @ColumnInfo(name = "created_at_epoch_millis") public long createdAtEpochMillis;
    @ColumnInfo(name = "updated_at_epoch_millis") public long updatedAtEpochMillis;
}
