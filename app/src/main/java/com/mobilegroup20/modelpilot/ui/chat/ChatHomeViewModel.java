package com.mobilegroup20.modelpilot.ui.chat;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.mobilegroup20.modelpilot.chat.local.ChatDao;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页的数据与"从首页开一条新对话"。
 *
 * <p><b>为什么写库要单独一个线程</b>：Room 的同步 DAO 在主线程上会直接抛
 * `IllegalStateException`（它不是慢，是明确禁止）。首页的写入只有一个入口
 * （按发送），所以一条单线程队列就够，不必上线程池——顺序也就自然保住了
 * （先建对话、再插消息，反过来会插出一条没有对话的消息）。
 *
 * <p><b>对话 id 由客户端生成</b>（`UUID`）：这类 id 会写进 `usage_call` 、
 * 也会被上下文压缩当成边界标记，让服务端发号会多一次往返，而且离线时没法建对话。
 */
public final class ChatHomeViewModel extends ViewModel {

    /** 输入条上"这条对话属于哪个项目"；空串 = `No project`（散聊）。 */
    private String projectId = "";
    /** 输入条上选中的模型；null = `Auto`。**ViewModel 里存**，
     *  这样转屏回来还是用户选的那个，而不是偷偷弹回 Auto。 */
    private String manualProviderId;
    private String manualModelId;

    private final ChatDao dao;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** 写库线程 → 主线程的回调桥。 */
    private final android.os.Handler main =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final LiveData<List<ProjectEntity>> projects;
    private final LiveData<List<ChatEntity>> chats;

    public ChatHomeViewModel() {
        dao = com.mobilegroup20.modelpilot.data.RepositoryProvider.chats();
        // LiveData 查询在这里各拿一次就存住：每调一次 DAO 都会新建一个观察对象，
        // 在 render 里反复取会让每次数据变化都重新注册一遍（很隐蔽的性能与时机问题）。
        projects = dao.projects();
        chats = dao.allChats();
    }

    public LiveData<List<ProjectEntity>> projects() {
        return projects;
    }

    public LiveData<List<ChatEntity>> chats() {
        return chats;
    }

    public String projectId() {
        return projectId;
    }

    public void selectProject(String projectId) {
        this.projectId = projectId == null ? "" : projectId;
    }

    public String manualProviderId() {
        return manualProviderId;
    }

    public String manualModelId() {
        return manualModelId;
    }

    public void selectModel(String providerId, String modelId) {
        manualProviderId = providerId;
        manualModelId = modelId;
    }

    /** 建一个新项目（首页 `+ New`）。 */
    public void createProject(String name, Runnable onDone) {
        final ProjectEntity project = new ProjectEntity();
        project.id = UUID.randomUUID().toString();
        project.name = name == null ? "" : name.trim();
        // 颜色下标**跟着已有的项目数往下排**（`ProjectColors` 按它取色，超了循环）。
        // 2026-10-04 真机上发现：原来固定写 0，于是新建的几个项目全是同一个紫色，
        // 设计稿里"每个项目一种颜色"就没了——而靠颜色区分项目正是那一列图标的作用。
        List<ProjectEntity> existing = projects.getValue();
        project.colorIndex = existing == null ? 0 : existing.size();
        project.createdAtEpochMillis = System.currentTimeMillis();
        project.updatedAtEpochMillis = project.createdAtEpochMillis;
        io.execute(() -> {
            dao.upsertProject(project);
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    /**
     * 从首页开一条新对话，**并把这句话交给对话页去发**。
     *
     * <p><b>2026-10-05 改。</b>原来这里顺手把用户那条消息也插进库，然后只开对话页——
     * 于是从首页发出去之后，用户看到自己那句话躺在对话里、**却没有任何回答**，
     * 得再按一次发送。第一版这么写是为了"先落库再发"（怕发出去而界面没记下），
     * 但那个约束应该由发送链路自己保证，而不是让用户多点一次。
     *
     * <p>现在：这里只建对话（标题取这句话），消息由对话页的发送链路插入并立刻发出去，
     * 一次点击 = 一次请求。代价是"建了对话但还没发出去"的这一瞬间如果进程被杀，
     * 会留下一条没有消息的对话——比"点了没反应"好接受得多。
     */
    public void startChat(String text, String projectId, ProjectCreated onCreated) {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            return;                     // 空消息不发（各家都会 400）
        }
        final ChatEntity chat = new ChatEntity();
        chat.id = UUID.randomUUID().toString();
        chat.projectId = projectId == null ? "" : projectId;
        chat.title = titleFrom(body);
        // **不给 lastProviderId/lastModelId 写"当前选的模型"**：那两个字段的意思是
        // "最后真的答过它的模型"，刚建的对话还没人答过，所以是 null——
        // 界面上就不显示徽章。写了就成了"选过的模型被当成答过的"，徽章会撒谎。
        chat.lastProviderId = null;
        chat.lastModelId = null;
        chat.createdAtEpochMillis = System.currentTimeMillis();
        chat.updatedAtEpochMillis = chat.createdAtEpochMillis;

        io.execute(() -> {
            dao.upsertChat(chat);
            if (onCreated != null) {
                // 回调切回主线程：写库在线程池里，而调用方多半要动界面。
                main.post(() -> onCreated.onCreated(chat.id));
            }
        });
    }

    /**
     * 标题取第一行，最长 {@value #TITLE_LIMIT} 个字符。
     *
     * <p>**这里的截断和上下文压缩那条"不许静默截断"不冲突**：标题只是个标签，
     * 原文一个字不少地存在消息里。真截的是列表里那一行的显示宽度（`maxLines=1`），
     * 与其让它被裁成半句，不如在存的时候就把边界定下来，列表和标题栏才是同一个。
     */
    static String titleFrom(String text) {
        int newline = text.indexOf('\n');
        String firstLine = newline < 0 ? text : text.substring(0, newline);
        firstLine = firstLine.trim();
        if (firstLine.length() <= TITLE_LIMIT) {
            return firstLine;
        }
        return firstLine.substring(0, TITLE_LIMIT).trim() + "…";
    }

    private static final int TITLE_LIMIT = 48;

    /** 落库在别的线程上，所以回调用接口而不是直接拿返回值；**回调在主线程**。 */
    public interface ProjectCreated {
        void onCreated(@NonNull String chatId);
    }

    @Override
    protected void onCleared() {
        // 界面没了就不用再留着这条线程（它只是写库，不需要跑完）。
        io.shutdown();
    }
}
