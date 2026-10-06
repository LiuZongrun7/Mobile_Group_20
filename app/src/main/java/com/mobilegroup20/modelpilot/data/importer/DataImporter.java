package com.mobilegroup20.modelpilot.data.importer;

import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把一份读好的导出文件<b>合并</b>进本机库（大纲 §4 export 的另一半）。
 *
 * <p>导出解决「能带走」，这个类解决「换回来」。没有它，导出的文件就只是一份遗物：
 * 换台手机之后你手上有个文件，而 App 里空空如也。
 *
 * <p><b>纯计算</b>：数据从 {@link ImportBundle} 进来，通过 {@link ImportTarget} 出去。
 * 不碰 Android、不碰文件、不知道用户是从哪儿选的文件，所以每条规矩都能用 JUnit 钉住
 * （{@code DataImporterTest}），而界面那一层只管「选哪个文件、要不要覆盖」。
 *
 * <h3>四条规矩</h3>
 * <ol>
 *   <li><b>按 id 认亲，不按名字。</b>对话、消息、记忆、用量都靠 id。名字可以重复、可以改，
 *       而 id 是导出时就在的——同一个文件导两次，第二次一条都不新增
 *       （用量那边的主键是由记录内容算出来的，见 {@code UsageCall#id}）。</li>
 *   <li><b>本机优先是默认，覆盖要用户明说。</b>见 {@link MergePolicy}。</li>
 *   <li><b>不补空、不编造。</b>文件里没有的字段（消息的 token、摘要的 madeBy）就是"不知道"，
 *       覆盖时保留本机的值或如实留空，绝不写 0、也不写 {@code "unknown"}。
 *       这是导出那边「不知道就留空」的反向一半。</li>
 *   <li><b>一行读不懂，不拖垮一整批。</b>坏行计进 {@link ImportSummary#problems} 并继续；
 *       但<b>绝不静默</b>——少导进来的东西没有提示，用户就会以为数据齐了。</li>
 * </ol>
 */
public final class DataImporter {

    /** 本机账本的账号，与 {@code CallLedger.LOCAL_UID} 是同一个值（出处就在那边）。 */
    public static final String LOCAL_UID = "local";

    private final ImportTarget target;

    public DataImporter(ImportTarget target) {
        this.target = target;
    }

    // ==================== 预览 ====================

    /**
     * 先看一遍：这份文件里有什么、本机已经有了哪些。<b>不写任何东西。</b>
     *
     * <p>会话内自带的重复 id 也会被找出来（{@link ImportPreview#duplicateChatIds}）：
     * 不拦（按 id 合并本来就是幂等的），但要在预览里说一句——正常导出的文件不会有，
     * 有就说明它被手工改过或拼接过。
     */
    public ImportPreview preview(ImportBundle bundle) {
        Set<String> local = new HashSet<>(target.existingChatIds());
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        List<String> existing = new ArrayList<>();
        for (ImportBundle.Conversation conversation : bundle.conversations) {
            String id = conversation.chat.id;
            if (!seen.add(id)) {
                duplicates.add(id);
            }
            if (local.contains(id)) {
                existing.add(id);
            }
        }
        return new ImportPreview(bundle, existing, new ArrayList<>(duplicates));
    }

    // ==================== 写 ====================

    /**
     * 真写。<b>同步执行</b>，调用方必须放到后台线程上（要写几张表）。
     *
     * <p>顺序是：项目 → 逐条对话（对话、消息、记忆）→ 用量。用量放最后，因为它是唯一
     * 自带副作用的一步（{@code RoomUsageRepository.importCalls} 会把受影响那几天的
     * 日汇总重滚一遍）；让最后一步是它，前面写好的对话在用量那步出问题时仍然是完整的。
     */
    public ImportSummary apply(ImportBundle bundle, MergePolicy policy) {
        Set<String> local = new HashSet<>(target.existingChatIds());
        Set<String> fromThisFile = chatIdsIn(bundle);
        List<String> problems = new ArrayList<>(bundle.problems);

        int added = 0;
        int replaced = 0;
        int skipped = 0;
        int messagesWritten = 0;
        int memoriesWritten = 0;

        // 项目先写：对话指向它。项目行是整行覆盖，同一个文件导两次没有副作用。
        for (ProjectEntity project : bundle.projects) {
            if (project.id == null || project.id.isEmpty()) {
                continue;   // 空 id 的行在解析那一步已经计进 problems 了
            }
            target.writeProject(project);
        }

        for (ImportBundle.Conversation conversation : bundle.conversations) {
            String id = conversation.chat.id;
            boolean existsOnDevice = local.contains(id);
            if (existsOnDevice && !policy.replacesExisting()) {
                skipped++;
                continue;
            }
            if (existsOnDevice) {
                replaced++;
            } else {
                added++;
            }
            target.writeChat(conversation.chat);
            messagesWritten += writeMessages(conversation);
            memoriesWritten += writeMemories(conversation);
        }

        int usageWritten = 0;
        int usageSkipped = 0;
        if (!bundle.usageCalls.isEmpty()) {
            List<UsageCallEntity> rows = usageRows(bundle.usageCalls, local, fromThisFile, problems);
            usageWritten = target.writeUsage(rows);
            // 仓库回的是"写进去几条"；重复多少由这里减出来——它那边数的是主键撞车的个数，
            // 正好等于 rows.size() - written。
            usageSkipped = Math.max(0, rows.size() - usageWritten);
        }

        return new ImportSummary(policy, added, replaced, skipped, messagesWritten, memoriesWritten,
                usageWritten, usageSkipped, problems);
    }

    /**
     * 写一条对话的消息，返回真正交给存储的条数。
     *
     * <p>为什么不数"文件里有多少条"：{@link ImportTarget#writeMessage} 是冲突忽略，
     * 本机已有的同 id 消息既不会被覆盖、也不该被算成写进去。差别在"两边有共同历史"
     * 的对话上很显眼：本机聊到第 30 条时导出、之后接着聊、再导回来——前 30 条一条都不该重写。
     */
    private int writeMessages(ImportBundle.Conversation conversation) {
        int written = 0;
        for (MessageEntity message : conversation.messages) {
            if (message.id == null || message.id.isEmpty()) {
                continue;
            }
            target.writeMessage(message);
            written++;
        }
        return written;
    }

    private int writeMemories(ImportBundle.Conversation conversation) {
        int written = 0;
        for (MemoryEntity memory : conversation.memories) {
            if (memory.id == null || memory.id.isEmpty()) {
                continue;
            }
            target.writeMemory(memory);
            written++;
        }
        return written;
    }

    /**
     * 契约模型 → 表行。
     *
     * <p><b>账号一律用本机的 {@code local}，不用文件里那个。</b>文件是外部输入，里面那个
     * uid 是导出那台设备的；信它等于允许一份被改过的文件把用量写成别人的号。这和
     * {@code UsageCallEntity.fromModel} 的「uid 从参数来、不从记录里来」是同一条规矩，
     * 只是这次的参数固定是本机账本。
     *
     * <p><b>{@code chatId} 只在本机真有那条对话时才留着。</b>只导了用量、没导对话的文件里，
     * 那些 id 指向的是另一台手机上的对话；留着会让 Insights 把一次调用挂到一条
     * 不存在的对话上。文件自己带对话的那种情况当然保留（它在同一次导入里刚被写进去）。
     */
    private static List<UsageCallEntity> usageRows(List<UsageCall> calls, Set<String> localChatIds,
                                                   Set<String> fromThisFile, List<String> problems) {
        List<UsageCallEntity> rows = new ArrayList<>(calls.size());
        int unusable = 0;
        for (UsageCall call : calls) {
            UsageCallEntity row = UsageCallEntity.fromModel(call, LOCAL_UID);
            if (row == null) {
                // 缺 id / 缺模型 / 时间戳非法，转换那一步就会退回来。不抛异常：坏一条
                // 不该让整批停住；但也不静默，下面统一报出来。
                unusable++;
                continue;
            }
            if (row.chatId != null && !row.chatId.isEmpty()
                    && !localChatIds.contains(row.chatId)
                    && !fromThisFile.contains(row.chatId)) {
                row.chatId = null;
            }
            rows.add(row);
        }
        if (unusable > 0) {
            problems.add(unusable + " usage records were skipped (missing id, model or timestamp)");
        }
        return rows;
    }

    /** 这份文件自己带来的对话 id。 */
    private static Set<String> chatIdsIn(ImportBundle bundle) {
        Set<String> ids = new HashSet<>();
        for (ImportBundle.Conversation conversation : bundle.conversations) {
            ids.add(conversation.chat.id);
        }
        return ids;
    }
}
