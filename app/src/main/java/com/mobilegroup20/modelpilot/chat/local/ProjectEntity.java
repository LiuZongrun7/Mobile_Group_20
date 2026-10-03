package com.mobilegroup20.modelpilot.chat.local;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 一个项目（设计稿首页那些 `Campus event` / `COMP3011` 分组）。
 *
 * <p>项目**不是文件夹**：它同时承载"这个项目的对话共用哪些指令与文件"
 * （大纲 §4 Chat and projects：each project holds multiple chats, files and
 * explicit instructions）。所以 {@link #instructions} 是项目级 system 提示，
 * 每次请求都要带上——它属于"永远不能丢"的那部分上下文。
 */
@Entity(tableName = "project", indices = {@Index(value = {"created_at_epoch_millis"})})
public class ProjectEntity {

    @PrimaryKey @NonNull
    public String id = "";

    /** 空 = 未命名项目（设计稿里那个 `No project` 的默认去处）。 */
    @ColumnInfo(name = "name") public String name = "";

    /** 项目级显式指令。空串 = 没有。**它永远进上下文，不受压缩影响。** */
    @ColumnInfo(name = "instructions") public String instructions = "";

    /** 有颜色的那只文件夹图标（设计稿里是紫/绿两色，按 index 取）。 */
    @ColumnInfo(name = "color_index") public int colorIndex;

    @ColumnInfo(name = "created_at_epoch_millis") public long createdAtEpochMillis;
    @ColumnInfo(name = "updated_at_epoch_millis") public long updatedAtEpochMillis;
}
