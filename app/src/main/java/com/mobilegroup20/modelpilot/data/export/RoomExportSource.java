package com.mobilegroup20.modelpilot.data.export;

import androidx.annotation.Nullable;

import com.mobilegroup20.modelpilot.chat.local.ChatDao;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.data.local.AppDatabase;
import com.mobilegroup20.modelpilot.data.local.UsageCallDao;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.util.List;

/**
 * {@link ExportSource} 落在 Room 上的实现。**这里只有查询，没有任何判断**——
 * 「哪些行该进文件、未知怎么写」全在 {@link DataExporter} 里，这样那些规矩能用单测钉住。
 *
 * <p>同步读，所以调用方必须在后台线程（导出一次要读几张表，放主线程会卡界面，
 * 而这个 App 的会话可能有很多条消息）。
 *
 * <p>对话与记忆**按对话逐条查**，而不是一次 join 全捞出来：本机库只有几十到几百条对话，
 * 一次一条多几十次查询无所谓，而写一条 `chat JOIN message JOIN memory` 的 SQL 回来
 * 还得在内存里把三张表重新对齐——那正是「读回来再拼」最容易丢东西的地方。
 */
public final class RoomExportSource implements ExportSource {

    private final ChatDao chatDao;
    private final UsageCallDao usageCallDao;

    /**
     * 账本的账号。本机发出去的调用统一挂 {@code local}（见 {@code CallLedger.LOCAL_UID}），
     * 由调用方传进来而不是在这儿写死：导入的历史记录用它们自己的 uid，
     * 将来真要按账号导出时，这个参数就是唯一要改的地方。
     */
    private final String uid;

    public RoomExportSource(AppDatabase database, String uid) {
        this(database.chatDao(), database.usageCallDao(), uid);
    }

    public RoomExportSource(ChatDao chatDao, UsageCallDao usageCallDao, String uid) {
        this.chatDao = chatDao;
        this.usageCallDao = usageCallDao;
        this.uid = uid;
    }

    @Override
    public List<ProjectEntity> projects() {
        return chatDao.allProjectsForExport();
    }

    @Override
    public List<ChatEntity> chats() {
        return chatDao.allChatsForExport();
    }

    @Override
    public List<MessageEntity> messages(String chatId) {
        return chatDao.messages(chatId);
    }

    @Override
    public List<MemoryEntity> memories(String chatId) {
        return chatDao.memories(chatId);
    }

    @Override
    public List<UsageCallEntity> usageCalls(@Nullable String fromDay, @Nullable String toDay) {
        return usageCallDao.callsForExport(uid, fromDay, toDay);
    }
}
