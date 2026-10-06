package com.mobilegroup20.modelpilot.data.importer;

import android.content.Context;

import com.mobilegroup20.modelpilot.chat.local.ChatDao;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.RepositoryProvider;
import com.mobilegroup20.modelpilot.data.local.AppDatabase;
import com.mobilegroup20.modelpilot.data.local.CallImporter;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;
import com.mobilegroup20.modelpilot.data.repository.UsageRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ImportTarget} 落在本机 Room 上的实现。**这里只有查询和写入，没有任何判断**——
 * 「哪些对话该覆盖、缺字段怎么办」全在 {@link DataImporter} 里，这样那些规矩能用单测钉住。
 *
 * <p>同步写，所以调用方必须在后台线程（要写几张表）。
 *
 * <h3>三件不显眼但很重要的事</h3>
 * <ol>
 *   <li><b>用量那一段走 {@link CallImporter}，不自己拼 SQL。</b>那条路已经定义了
 *       「按主键去重 + 把受影响那几天的日汇总重滚一遍」，自己再写一遍迟早会漏掉后半句，
 *       而漏掉的后果是账本里原始记录有了、Insights 的数字没变（表现是"导入了但什么都
 *       看不到"）。它也是<b>同步</b>的，所以这里能拿到"真的写进去几条"并报给用户。</li>
 *   <li><b>每条对话/消息/记忆各自一个事务。</b>这三张表之间没有外键（见 {@code ChatDao}
 *       的注释），所以不会有"级联"帮我们兜底；各自成事务意味着一次失败最多影响一条，
 *       不会留下"一条没有消息的对话"那种半截状态——而那种状态在界面上看起来
 *       和"这条对话是空的"一模一样。</li>
 *   <li><b>写入用 REPLACE 语义，靠 {@code ChatDao} 上已有的 {@code upsert*}</b>：
 *       一行就是一条完整记录，没有"只改其中一列"的写法。</li>
 * </ol>
 */
public final class RoomImportTarget implements ImportTarget {

    private final AppDatabase database;
    private final ChatDao chatDao;
    private final CallImporter calls;

    /** 给界面用的构造：一次把仓库取齐（{@link RepositoryProvider} 自己会保证单例）。 */
    public RoomImportTarget(Context context) {
        this(RepositoryProvider.databaseForImport(), RepositoryProvider.pricingSource());
    }

    /**
     * 测试用：直接给库和价目表。
     *
     * <p>接的是 {@code PricingSource} 而不是 {@code UsageRepository}：导入要的是
     * "写完立刻知道几条、并且那几天的汇总也重滚过了"，那件事只有 {@link CallImporter}
     * 提供，而它只依赖库和价目表。
     */
    public RoomImportTarget(AppDatabase database,
                           com.mobilegroup20.modelpilot.data.PricingSource pricing) {
        this.database = database;
        this.chatDao = database.chatDao();
        this.calls = new CallImporter(database, pricing);
    }

    @Override
    public List<String> existingChatIds() {
        List<String> ids = new ArrayList<>();
        for (ChatEntity chat : chatDao.allChatsForExport()) {
            ids.add(chat.id);
        }
        return ids;
    }

    @Override
    public void writeProject(ProjectEntity project) {
        database.runInTransaction(() -> chatDao.upsertProject(project));
    }

    @Override
    public void writeChat(ChatEntity chat) {
        database.runInTransaction(() -> chatDao.upsertChat(chat));
    }

    @Override
    public void writeMessage(MessageEntity message) {
        database.runInTransaction(() -> chatDao.upsertMessage(message));
    }

    @Override
    public void writeMemory(MemoryEntity memory) {
        database.runInTransaction(() -> chatDao.upsertMemory(memory));
    }

    /**
     * 用量：交给 {@link CallImporter}，返回<b>真的写进去的条数</b>（不含主键撞车被跳过的）。
     *
     * <p>{@code uid} 固定 {@link DataImporter#LOCAL_UID}——花的是这台手机上的 key，
     * 和登录了没有无关（见 {@code CallLedger} 的类注释）。
     */
    @Override
    public int writeUsage(List<UsageCallEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<UsageCall> models = new ArrayList<>(rows.size());
        for (UsageCallEntity row : rows) {
            models.add(row.toModel());
        }
        UsageRepository.ImportResult result =
                calls.importCalls(DataImporter.LOCAL_UID, models);
        return result.inserted;
    }
}
