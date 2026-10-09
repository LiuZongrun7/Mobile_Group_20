package com.mobilegroup20.modelpilot.chat.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

/**
 * 对话库的读写。
 *
 * <p>两条规矩：① **列表用 `LiveData`**（设计稿首页要跟着变，别让界面自己轮询）；
 * ② 消息按时间升序取——渲染上下文时顺序就是语义，倒过来会得到一段胡话。
 */
@Dao
public interface ChatDao {

    /** Adapted history boundary from Pydantic AI Database.get_messages.
     * One Room transaction prevents edits/deletion interleaving the separate queries.
     */
    @Transaction
    default ChatHistorySnapshot readHistory(String chatId) {
        ChatEntity value = chat(chatId);
        if (value == null) throw new IllegalStateException("This conversation no longer exists.");
        ProjectEntity owner = value.projectId == null || value.projectId.isEmpty()
                ? null : project(value.projectId);
        return new ChatHistorySnapshot(value, owner, messages(chatId), memories(chatId));
    }

    /** Extension of upstream Database.add_messages: reply and last-model badge commit together.
     * A deleted chat is never resurrected by a late model callback.
     */
    @Transaction
    default boolean completeReply(String chatId, MessageEntity answer, String providerId, String modelId, long at) {
        if (chat(chatId) == null) return false;
        if (answer != null) {
            if (!chatId.equals(answer.chatId)) throw new IllegalArgumentException("Reply belongs to another conversation.");
            upsertMessage(answer);
        }
        touchChat(chatId, providerId, modelId, at);
        return true;
    }

    // ---- 项目 ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertProject(ProjectEntity project);

    @Query("SELECT * FROM project ORDER BY updated_at_epoch_millis DESC")
    LiveData<List<ProjectEntity>> projects();

    @Query("SELECT * FROM project WHERE id = :id")
    ProjectEntity project(String id);

    /** 对话页顶上那行小字要的是名字，不是整个项目；只取一列，界面就不必处理整条记录。 */
    @Query("SELECT name FROM project WHERE id = :id")
    LiveData<String> projectName(String id);

    @Query("SELECT * FROM project WHERE id = :id")
    LiveData<ProjectEntity> projectLive(String id);

    @Query("UPDATE project SET instructions = :instructions, updated_at_epoch_millis = :at WHERE id = :id")
    int updateProjectInstructions(String id, String instructions, long at);

    // ---- 对话 ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertChat(ChatEntity chat);

    @Query("SELECT * FROM chat WHERE project_id = :projectId ORDER BY updated_at_epoch_millis DESC")
    LiveData<List<ChatEntity>> chatsInProject(String projectId);

    /** 首页那个 "Recent chats"：不分项目，按最近活跃。 */
    @Query("SELECT * FROM chat ORDER BY updated_at_epoch_millis DESC LIMIT :limit")
    LiveData<List<ChatEntity>> recentChats(int limit);

    /**
     * 全部对话，按最近活跃。
     *
     * <p>首页要按项目分组显示，而"每个项目各挂一个 LiveData"会在项目数变化时
     * 反复注册/注销观察者（列表一动就要重挂），很容易漏掉一次而显示成旧数据。
     * 一次性拿全量、在内存里分组，是这个数据量级（本机、几十到几百条）下更稳的写法。
     */
    @Query("SELECT * FROM chat ORDER BY updated_at_epoch_millis DESC")
    LiveData<List<ChatEntity>> allChats();

    @Query("SELECT * FROM chat WHERE id = :id")
    ChatEntity chat(String id);

    /**
     * 全部项目 / 全部对话的**同步**版本，给导出用（{@code data/export}）。
     *
     * <p>和上面那两个 {@code LiveData} 版本有两处不同，都是故意的：
     * <ul>
     *   <li><b>同步返回</b>：导出是「读一遍、写一个文件」的一次性动作，它要的是一份
     *       前后一致的快照，不是一个会自己变的列表。用 LiveData 反而得挂个观察者
     *       再等它回调，导出逻辑就变成了异步的（见 {@code ExportSource} 的注释）；</li>
     *   <li><b>按创建时间升序</b>：界面按「最近活跃」排是对的（人找的是刚聊过的），
     *       而导出是一份存档，存档按时间顺序排才好读、也才稳定——同样的数据导两次
     *       得到的行序一致，diff 才有意义。</li>
     * </ul>
     */
    @Query("SELECT * FROM chat ORDER BY created_at_epoch_millis ASC")
    List<ChatEntity> allChatsForExport();

    @Query("SELECT * FROM project ORDER BY created_at_epoch_millis ASC")
    List<ProjectEntity> allProjectsForExport();

    /** 对话页顶部那两行（项目名 + 标题）要跟着改名走，所以这里是 LiveData。 */
    @Query("SELECT * FROM chat WHERE id = :id")
    LiveData<ChatEntity> chatLive(String id);

    @Query("UPDATE chat SET last_provider_id = :providerId, last_model_id = :modelId,"
            + " updated_at_epoch_millis = :at WHERE id = :id")
    void touchChat(String id, String providerId, String modelId, long at);

    @Query("DELETE FROM chat WHERE id = :id")
    void deleteChat(String id);

    // ---- 消息 ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertMessage(MessageEntity message);

    /** 渲染上下文要按时间升序**全量**取（压缩的边界靠 id，不靠分页）。 */
    @Query("SELECT * FROM message WHERE chat_id = :chatId ORDER BY created_at_epoch_millis ASC")
    List<MessageEntity> messages(String chatId);

    @Query("SELECT * FROM message WHERE chat_id = :chatId ORDER BY created_at_epoch_millis ASC")
    LiveData<List<MessageEntity>> messagesLive(String chatId);

    @Query("UPDATE message SET tokens_in = :in, tokens_out = :out WHERE id = :id")
    void recordTokens(String id, long in, long out);

    // ---- 记忆 ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertMemory(MemoryEntity memory);

    @Query("SELECT * FROM memory WHERE chat_id = :chatId ORDER BY created_at_epoch_millis ASC")
    List<MemoryEntity> memories(String chatId);

    @Query("SELECT * FROM memory WHERE chat_id = :chatId ORDER BY created_at_epoch_millis ASC")
    LiveData<List<MemoryEntity>> memoriesLive(String chatId);

    @Query("UPDATE memory SET summary = :text, edited_by_user = 1 WHERE id = :id")
    void editMemory(String id, String text);

    @Query("DELETE FROM memory WHERE id = :id")
    void deleteMemory(String id);

    /** 改对话标题（对话页 `⋮ → Rename`）。 */
    @Query("UPDATE chat SET title = :title, updated_at_epoch_millis = :at WHERE id = :id")
    void renameChat(String id, String title, long at);

    /**
     * 删掉一条对话的**全部消息与记忆**。
     *
     * <p>为什么写在这里而不是靠数据库的级联：这几张表之间没有建外键
     * （Room 里我们只加了索引），所以"删对话"必须显式把三张表都清掉。
     * 漏掉 `memory` 的后果是：下次新建一条同 id 的对话（uuid，理论上不会撞）
     * 或导出时，会看到一条指向已经不存在的消息的摘要。删就删干净。
     */
    @Query("DELETE FROM message WHERE chat_id = :chatId")
    void deleteMessages(String chatId);

    @Query("DELETE FROM memory WHERE chat_id = :chatId")
    void deleteMemories(String chatId);
}
