package com.mobilegroup20.modelpilot.data.importer;

import androidx.annotation.Nullable;

import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个导出文件<b>读出来之后</b>的全部内容，格式无关。
 *
 * <p>JSON 和 CSV 两条路读出来都落到这里（{@code ExportFileReader} 决定走哪条），
 * 于是「怎么合并」那部分只有一份代码、也只需要一套测试：{@link DataImporter} 根本
 * 不知道手里这份是从哪种文件读来的。CSV 是一条退化过的路（见下），所以这个类里
 * 才需要 {@link Coverage} 这种东西把"这张表这次带了什么"如实记下来。
 *
 * <h3>CSV 为什么是"退化"的</h3>
 * CSV 是一张表一个文件，为了能用 Excel 打开，消息行里重复了所属对话的信息，
 * 附件被压成了「个数 + 文件名」两列，工具调用根本没地方放。所以 CSV 往返<b>不可能</b>
 * 和 JSON 一样完整——我们不假装它能，而是把丢了什么如实报给用户（{@link Coverage}），
 * 让「要换手机就用 JSON」变成一句有依据的话。这也是导出那边的 {@code meta.notes}
 * 已经写明的取舍，两边口径一致。
 */
public final class ImportBundle {

    /**
     * 这一份文件在哪些表上带了完整内容。
     *
     * <p>不是"有没有这张表"，而是"这张表里的行完不完整"：CSV 里有 {@code conversations.csv}
     * 不代表消息上的附件还在。合并逻辑要按这个决定"缺席的字段"是写成空还是<b>保留本机原值</b>
     * （只有替换已有对话时才轮得到这个问题，见 {@link MergePolicy}）。
     */
    public enum Coverage {
        /** JSON：什么都带齐了。 */
        FULL,
        /** CSV zip：对话/消息/记忆是"扁平"的，附件与工具调用不全。 */
        TABULAR
    }

    /** 文件格式，只为显示与记日志。 */
    public final String format;

    public final ImportFacts facts;

    public final Coverage coverage;

    public final List<ProjectEntity> projects;
    public final List<Conversation> conversations;
    public final List<UsageCall> usageCalls;

    /**
     * 读的过程中被丢掉的行，每条一句人话。
     *
     * <p><b>绝不静默跳过。</b>和导出那边「不知道就留空」是同一条规矩的另一半：
     * 少导进来的东西没有任何提示，用户就会以为数据齐了。界面必须显示这个列表的条数，
     * 至少第一条。
     */
    public final List<String> problems;

    public ImportBundle(String format, ImportFacts facts, Coverage coverage,
                        @Nullable List<ProjectEntity> projects,
                        @Nullable List<Conversation> conversations,
                        @Nullable List<UsageCall> usageCalls,
                        @Nullable List<String> problems) {
        this.format = format;
        this.facts = facts;
        this.coverage = coverage;
        this.projects = immutable(projects);
        this.conversations = immutable(conversations);
        this.usageCalls = immutable(usageCalls);
        this.problems = immutable(problems);
    }

    /** 一条对话，连带它的消息与记忆。 */
    public static final class Conversation {
        public final ChatEntity chat;
        public final List<MessageEntity> messages;
        public final List<MemoryEntity> memories;

        public Conversation(ChatEntity chat, @Nullable List<MessageEntity> messages,
                            @Nullable List<MemoryEntity> memories) {
            this.chat = chat;
            this.messages = immutable(messages);
            this.memories = immutable(memories);
        }
    }

    // ---- 数数：预览和结果都从这里来，不读自述里的 counts ----

    public int messageCount() {
        int total = 0;
        for (Conversation conversation : conversations) {
            total += conversation.messages.size();
        }
        return total;
    }

    public int memoryCount() {
        int total = 0;
        for (Conversation conversation : conversations) {
            total += conversation.memories.size();
        }
        return total;
    }

    /** 用量那边最早/最晚的一天（{@code yyyy-MM-dd}）；没有记录就是 null。 */
    @Nullable
    public String firstUsageDay() {
        String first = null;
        for (UsageCall call : usageCalls) {
            String day = com.mobilegroup20.modelpilot.util.TimeUtils.dayOf(call.startedAtEpochMillis);
            if (first == null || day.compareTo(first) < 0) {
                first = day;
            }
        }
        return first;
    }

    @Nullable
    public String lastUsageDay() {
        String last = null;
        for (UsageCall call : usageCalls) {
            String day = com.mobilegroup20.modelpilot.util.TimeUtils.dayOf(call.startedAtEpochMillis);
            if (last == null || day.compareTo(last) > 0) {
                last = day;
            }
        }
        return last;
    }

    /** 对话框里报的那一行「3 conversations · 340 messages · 88 calls」。 */
    public String describeCounts() {
        List<String> parts = new ArrayList<>();
        if (!conversations.isEmpty() || facts.hasConversations) {
            parts.add(conversations.size() + " conversations");
            parts.add(messageCount() + " messages");
            parts.add(memoryCount() + " summaries");
        }
        if (!usageCalls.isEmpty() || facts.hasUsage) {
            parts.add(usageCalls.size() + " calls");
        }
        return parts.isEmpty() ? "Nothing in this file" : String.join(" · ", parts);
    }

    private static <T> List<T> immutable(@Nullable List<T> list) {
        return list == null ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(list));
    }
}
