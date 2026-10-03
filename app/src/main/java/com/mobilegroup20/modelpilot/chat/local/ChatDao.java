package com.mobilegroup20.modelpilot.chat.local;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 对话库的读写。
 *
 * <p>两条规矩：① **列表用 `LiveData`**（设计稿首页要跟着变，别让界面自己轮询）；
 * ② 消息按时间升序取——渲染上下文时顺序就是语义，倒过来会得到一段胡话。
 */
@Dao
public interface ChatDao {

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
}
