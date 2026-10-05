package com.mobilegroup20.modelpilot.data.export;

import androidx.annotation.Nullable;

import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.util.List;

/**
 * 导出要读的那几样数据。
 *
 * <p><b>为什么中间夹一层接口，而不是让 {@link DataExporter} 直接拿 DAO。</b>
 * 导出的逻辑里真正容易写错的部分（日期范围、未知字段留空、附件里的本机路径要不要带出去）
 * 全都是<b>不需要真数据库</b>就能测的。夹这一层之后，那些规则可以用 JUnit 直接钉住
 * （{@code DataExporterTest}），不用起模拟器、也不用为了测一个 CSV 转义去建 Room 库。
 *
 * <p>实现方必须<b>同步</b>返回：调用方（界面）负责放到后台线程上去跑，
 * 这里不做异步——导出要读的是几张表，把它做成一半同步一半异步只会让「一致性」
 * 变成一个没人说得清的问题（导出到一半用户删了一条对话会怎样）。
 */
public interface ExportSource {

    List<ProjectEntity> projects();

    List<ChatEntity> chats();

    List<MessageEntity> messages(String chatId);

    List<MemoryEntity> memories(String chatId);

    /**
     * 本机账本。{@code fromDay} / {@code toDay} 是 {@code yyyy-MM-dd}，含两端，
     * {@code null} = 那一头不限。
     */
    List<UsageCallEntity> usageCalls(@Nullable String fromDay, @Nullable String toDay);
}
