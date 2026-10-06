package com.mobilegroup20.modelpilot.data.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * 合并的规矩。这些规矩的共性是：<b>写错了不会报错，只会悄悄吃掉用户的数据</b>——
 * 本机刚聊的那条被一份旧文件盖掉、本机没有的用量被算成重复、一条坏行被静默跳过。
 * 所以每一条都有一个测试。
 */
public class DataImporterTest {

    /** 2026-10-06 15:30（北京时间）。 */
    private static final long AT = 1_791_271_800_000L;

    // ==================== 默认：只加本机没有的 ====================

    @Test
    public void addOnlyKeepsTheConversationThatIsAlreadyOnThePhone() {
        FakeTarget target = new FakeTarget();
        target.plantChat(chat("ch1", "本机版本（刚聊过）"));
        MessageEntity localOnly = message("m9", "ch1", "USER", "本机新加的一条");
        target.plantMessage(localOnly);

        ImportSummary summary = importer(target).apply(bundle(conversation("ch1", "文件里的旧版本",
                message("m1", "ch1", "USER", "旧消息"))), MergePolicy.ADD_ONLY);

        assertEquals("本机已有的那条不许动", "本机版本（刚聊过）", target.chats.get("ch1").title);
        assertNotNull("本机新加的消息必须还在", target.messages.get("m9"));
        assertNull("文件里那条旧消息不该被写进来（整条对话都跳过了）", target.messages.get("m1"));
        assertEquals("只加新的：不该有新增", 0, summary.conversationsAdded);
        assertEquals("只加新的：不该有覆盖", 0, summary.conversationsReplaced);
        assertEquals("本机已有的那条应该被跳过", 1, summary.conversationsSkipped);
        assertEquals("一个字都没写", 0, summary.messagesWritten);
    }

    @Test
    public void addOnlyStillAddsConversationsThatAreNotOnThePhone() {
        FakeTarget target = new FakeTarget();
        target.plantChat(chat("ch1", "本机的"));

        ImportSummary summary = importer(target).apply(bundle(
                conversation("ch1", "文件里的 ch1", message("m1", "ch1", "USER", "旧")),
                conversation("ch2", "文件里的 ch2", message("m2", "ch2", "USER", "新"))),
                MergePolicy.ADD_ONLY);

        assertEquals("文件里那条新的应该被写进去", 1, summary.conversationsAdded);
        assertEquals("本机已有的那条应该被跳过", 1, summary.conversationsSkipped);
        assertEquals("文件里的 ch2", target.chats.get("ch2").title);
        assertEquals("本机的 ch1 一个字没动", "本机的", target.chats.get("ch1").title);
        assertNotNull(target.messages.get("m2"));
        assertNull(target.messages.get("m1"));
    }

    // ==================== 覆盖：换手机时用户明确选的 ====================

    @Test
    public void replaceOverwritesTheChatButKeepsMessagesTheFileDoesNotKnowAbout() {
        FakeTarget target = new FakeTarget();
        target.plantChat(chat("ch1", "本机版本"));
        target.plantMessage(message("m1", "ch1", "USER", "两边都有的一条"));
        target.plantMessage(message("m9", "ch1", "ASSISTANT", "本机独有的后续"));

        ImportSummary summary = importer(target).apply(bundle(conversation("ch1", "文件里的版本",
                message("m1", "ch1", "USER", "被改过的一条"),
                message("m2", "ch1", "ASSISTANT", "文件里的第二条"))), MergePolicy.REPLACE);

        assertEquals("文件里的版本", target.chats.get("ch1").title);
        assertEquals("覆盖策略下应该是覆盖而不是新增", 1, summary.conversationsReplaced);
        assertEquals("只加新的：不该有新增", 0, summary.conversationsAdded);
        assertEquals(0, summary.conversationsSkipped);
        // 同 id 的消息**冲突忽略**：本机那条不会被文件的版本盖掉（导入不当"回滚"用）。
        assertEquals("两边都有的一条", target.messages.get("m1").text);
        assertNotNull("文件带来的新消息要写进来", target.messages.get("m2"));
        assertNotNull("本机独有的后续不许被删（导入从不删东西）", target.messages.get("m9"));
    }

    @Test
    public void replaceNeverTouchesConversationsThatAreNotInTheFile() {
        FakeTarget target = new FakeTarget();
        target.plantChat(chat("local-only", "只在本机"));

        importer(target).apply(bundle(conversation("ch1", "文件里的",
                message("m1", "ch1", "USER", "x"))), MergePolicy.REPLACE);

        assertNotNull("本机独有的对话在任何策略下都不动", target.chats.get("local-only"));
    }

    // ==================== 预览 ====================

    @Test
    public void previewCountsWhatWouldBeWrittenUnderEachPolicy() {
        FakeTarget target = new FakeTarget();
        target.plantChat(chat("ch1", "本机的"));

        ImportPreview preview = importer(target).preview(bundle(
                conversation("ch1", "a", message("m1", "ch1", "USER", "x"),
                        message("m2", "ch1", "ASSISTANT", "y")),
                conversation("ch2", "b", message("m3", "ch2", "USER", "z"))));

        assertEquals(2, preview.conversations());
        assertEquals(3, preview.messages());
        assertEquals(1, preview.alreadyOnDevice());
        assertEquals("只加新的：只有 ch2 要写", 1, preview.conversationsToWrite(MergePolicy.ADD_ONLY));
        assertEquals("只加新的：只有 ch2 那条消息要写", 1, preview.messagesToWrite(MergePolicy.ADD_ONLY));
        assertEquals(2, preview.conversationsToWrite(MergePolicy.REPLACE));
        assertEquals(3, preview.messagesToWrite(MergePolicy.REPLACE));
        assertFalse(preview.nothingToAdd());
    }

    @Test
    public void previewSaysWhenThereIsNothingToAdd() {
        FakeTarget target = new FakeTarget();
        target.plantChat(chat("ch1", "本机的"));

        ImportPreview preview = importer(target).preview(bundle(
                conversation("ch1", "a", message("m1", "ch1", "USER", "x"))));

        assertTrue("本机全都有，且策略是只加新的 → 没有可做的事", preview.nothingToAdd());
        assertFalse("换成覆盖就有事可做", preview.conversationsToWrite(MergePolicy.REPLACE) == 0);
    }

    @Test
    public void previewNoticesDuplicateIdsInsideTheFile() {
        FakeTarget target = new FakeTarget();
        ImportPreview preview = importer(target).preview(bundle(
                conversation("ch1", "第一次出现", message("m1", "ch1", "USER", "x")),
                conversation("ch1", "第二次出现", message("m2", "ch1", "USER", "y"))));

        assertTrue("同一份文件里出现两次同一个 id：不拦，但要说出来", preview.hasInternalDuplicates());
    }

    // ==================== 项目、记忆、坏行 ====================

    @Test
    public void projectsAndMemoriesAreWritten() {
        FakeTarget target = new FakeTarget();
        ProjectEntity project = new ProjectEntity();
        project.id = "p1";
        project.name = "COMP3011";
        ImportBundle base = bundle(conversation("ch1", "c", message("m1", "ch1", "USER", "x")));
        ImportBundle withProject = new ImportBundle("JSON", base.facts,
                ImportBundle.Coverage.FULL, Collections.singletonList(project),
                Collections.singletonList(conversationWithMemory("ch1", "c",
                        message("m1", "ch1", "USER", "x"), memory("mem1", "ch1", "摘要"))),
                base.usageCalls, base.problems);

        ImportSummary summary = importer(target).apply(withProject, MergePolicy.ADD_ONLY);

        assertEquals("COMP3011", target.projects.get("p1").name);
        assertEquals(1, summary.memoriesWritten);
        assertEquals("摘要", target.memories.get("mem1").summary);
    }

    @Test
    public void problemsFromTheFileSurviveIntoTheResult() {
        ImportBundle bundle = new ImportBundle("JSON", facts(),
                ImportBundle.Coverage.FULL, null,
                Collections.singletonList(conversation("ch1", "c",
                        message("m1", "ch1", "USER", "x"))),
                null, Collections.singletonList("一条没有 id 的消息被跳过了"));

        ImportSummary summary = importer(new FakeTarget()).apply(bundle, MergePolicy.ADD_ONLY);

        assertEquals(1, summary.problems.size());
        assertEquals("一条没有 id 的消息被跳过了", summary.problems.get(0));
    }

    // ==================== 用量 ====================

    @Test
    public void usageIsWrittenUnderTheLocalAccountAndCounted() {
        FakeTarget target = new FakeTarget();
        ImportSummary summary = importer(target).apply(
                bundleWithUsage(usage("call:a:1:2026-10-06"), usage("call:a:2:2026-10-06")),
                MergePolicy.ADD_ONLY);

        assertEquals(2, summary.usageWritten);
        assertEquals(0, summary.usageSkippedDuplicates);
        assertEquals("同一条记录不该变成两笔", 2, target.usage.size());
        for (UsageCallEntity row : target.usage) {
            // 账号一律是本机的，不信文件里写的那个（文件是外部输入）。
            assertEquals("账号必须换成本机的", DataImporter.LOCAL_UID, row.uid);
        }
    }

    @Test
    public void usageThatIsAlreadyInTheLedgerIsSkippedNotDuplicated() {
        FakeTarget target = new FakeTarget();
        // 库里已经有一条同 id 的（记录内容算出来的 id，所以"同一条"就是同一个 id）。
        target.plantUsage(DataImporter.LOCAL_UID, usage("call:a:1:2026-10-06"));

        ImportSummary summary = importer(target).apply(
                bundleWithUsage(usage("call:a:1:2026-10-06"), usage("call:a:2:2026-10-06")),
                MergePolicy.ADD_ONLY);

        assertEquals("同一条记录导两次不该变成两笔", 1, summary.usageWritten);
        assertEquals(1, summary.usageSkippedDuplicates);
        assertEquals("同一条记录不该变成两笔", 2, target.usage.size());
    }

    @Test
    public void usageThatCannotBeUsedIsReportedNotSilentlyDropped() {
        FakeTarget target = new FakeTarget();
        UsageCall broken = usage("call:a:3:2026-10-06");
        broken.model = "";      // 缺模型 → 入库那一步会退回来

        ImportSummary summary = importer(target).apply(bundleWithUsage(broken), MergePolicy.ADD_ONLY);

        assertEquals(0, summary.usageWritten);
        assertEquals("一条都没写进去", 0, target.usage.size());
        assertFalse("必须说出来，不能静默跳过", summary.problems.isEmpty());
        assertTrue(summary.problems.get(0).contains("1 usage records"));
    }

    @Test
    public void usagePointingAtAConversationThatIsNotHereLosesTheLink() {
        FakeTarget target = new FakeTarget();
        UsageCall call = usage("call:a:4:2026-10-06");
        call.chatId = "ch-from-another-phone";

        importer(target).apply(bundleWithUsage(call), MergePolicy.ADD_ONLY);

        assertNull("指向一条不存在的对话会让 Insights 挂错，所以清掉",
                target.usage.get(0).chatId);
    }

    @Test
    public void usageKeepsTheLinkWhenTheConversationComesInTheSameFile() {
        FakeTarget target = new FakeTarget();
        UsageCall call = usage("call:a:5:2026-10-06");
        call.chatId = "ch1";
        ImportBundle bundle = bundleWithUsage(conversation("ch1", "c",
                message("m1", "ch1", "USER", "x")), call);

        importer(target).apply(bundle, MergePolicy.ADD_ONLY);

        assertEquals("ch1", target.usage.get(0).chatId);
    }

    // ==================== 结果怎么说 ====================

    @Test
    public void summarySaysWhatHappenedInPlainWords() {
        FakeTarget target = new FakeTarget();
        target.plantChat(chat("ch1", "本机的"));

        ImportSummary summary = importer(target).apply(bundle(
                conversation("ch1", "文件里的", message("m1", "ch1", "USER", "x")),
                conversation("ch2", "新的", message("m2", "ch2", "USER", "y"))),
                MergePolicy.ADD_ONLY);

        String text = summary.describe();
        assertTrue(text, text.contains("added 1 conversations"));
        assertTrue(text, text.contains("kept 1 already on this phone"));
        assertTrue(text, text.contains("1 messages restored"));
        assertFalse(summary.wroteNothing());
    }

    @Test
    public void summarySaysNothingNewWhenThereIsNothingNew() {
        FakeTarget target = new FakeTarget();
        target.plantChat(chat("ch1", "本机的"));

        ImportSummary summary = importer(target).apply(bundle(
                conversation("ch1", "文件里的", message("m1", "ch1", "USER", "x"))),
                MergePolicy.ADD_ONLY);

        assertTrue(summary.wroteNothing());
        assertEquals("跳过了 N 条听起来像做成了什么，得说实话", "Nothing new in this file", summary.describe());
    }

    // ==================== 小工具 ====================

    private static DataImporter importer(FakeTarget target) {
        return new DataImporter(target);
    }

    private static ImportFacts facts() {
        return new ImportFacts("ModelPilot", "1.0", AT, "local", null, null, true, false, true);
    }

    private static ImportBundle bundle(ImportBundle.Conversation... conversations) {
        return new ImportBundle("JSON", facts(), ImportBundle.Coverage.FULL, null,
                Arrays.asList(conversations), null, null);
    }

    private static ImportBundle bundleWithUsage(UsageCall... calls) {
        return new ImportBundle("JSON", facts(), ImportBundle.Coverage.FULL, null, null,
                Arrays.asList(calls), null);
    }

    private static ImportBundle bundleWithUsage(ImportBundle.Conversation conversation,
                                                UsageCall... calls) {
        return new ImportBundle("JSON", facts(), ImportBundle.Coverage.FULL, null,
                Collections.singletonList(conversation), Arrays.asList(calls), null);
    }

    private static ImportBundle.Conversation conversation(String chatId, String title,
                                                          MessageEntity... messages) {
        return new ImportBundle.Conversation(chat(chatId, title), Arrays.asList(messages),
                Collections.<MemoryEntity>emptyList());
    }

    /** 带一条摘要的对话（{@link #conversation} 的 varargs 装不下摘要，所以单独一个）。 */
    private static ImportBundle.Conversation conversationWithMemory(String chatId, String title,
                                                                    MessageEntity message,
                                                                    MemoryEntity memory) {
        return new ImportBundle.Conversation(chat(chatId, title),
                Collections.singletonList(message), Collections.singletonList(memory));
    }

    private static ChatEntity chat(String id, String title) {
        ChatEntity chat = new ChatEntity();
        chat.id = id;
        chat.title = title;
        chat.createdAtEpochMillis = AT - 10_000L;
        chat.updatedAtEpochMillis = AT;
        return chat;
    }

    private static MessageEntity message(String id, String chatId, String role, String text) {
        MessageEntity message = new MessageEntity();
        message.id = id;
        message.chatId = chatId;
        message.role = role;
        message.text = text;
        message.createdAtEpochMillis = AT - 1_000L;
        return message;
    }

    private static MemoryEntity memory(String id, String chatId, String summary) {
        MemoryEntity memory = new MemoryEntity();
        memory.id = id;
        memory.chatId = chatId;
        memory.summary = summary;
        memory.fromMessageId = "m1";
        memory.toMessageId = "m1";
        memory.createdAtEpochMillis = AT - 500L;
        return memory;
    }

    private static UsageCall usage(String id) {
        UsageCall call = new UsageCall();
        call.id = id;
        call.uid = "somebody-else";             // 文件里写的账号，必须被忽略
        call.provider = Provider.DEEPSEEK;
        call.model = "deepseek-chat";
        call.startedAtEpochMillis = AT - 3_000L;
        call.input = 100L;
        call.output = 20L;
        call.source = UsageCall.Source.APP;
        return call;
    }

    /**
     * 假存储：四个表用 map 表示，<b>冲突语义照着 Room 来</b>
     * （对话整行覆盖，消息/记忆/用量同 id 忽略）。冲突语义抄错的话，
     * 上面那些测试就会变成"测试自己错了"，所以这里刻意写得和 DAO 的注解一一对应。
     */
    private static final class FakeTarget implements ImportTarget {

        final Map<String, ProjectEntity> projects = new LinkedHashMap<>();
        final Map<String, ChatEntity> chats = new LinkedHashMap<>();
        final Map<String, MessageEntity> messages = new LinkedHashMap<>();
        final Map<String, MemoryEntity> memories = new LinkedHashMap<>();
        final List<UsageCallEntity> usage = new ArrayList<>();

        void plantChat(ChatEntity chat) {
            chats.put(chat.id, chat);
        }

        void plantMessage(MessageEntity message) {
            messages.put(message.id, message);
        }

        void plantUsage(String uid, UsageCall call) {
            usage.add(UsageCallEntity.fromModel(call, uid));
        }

        @Override
        public List<String> existingChatIds() {
            return new ArrayList<>(chats.keySet());
        }

        @Override
        public void writeProject(ProjectEntity project) {
            projects.put(project.id, project);
        }

        @Override
        public void writeChat(ChatEntity chat) {
            chats.put(chat.id, chat);       // 整行覆盖，同 ChatDao.upsertChat
        }

        @Override
        public void writeMessage(MessageEntity message) {
            if (!messages.containsKey(message.id)) {    // 冲突忽略，同 ChatDao.upsertMessage
                messages.put(message.id, message);
            }
        }

        @Override
        public void writeMemory(MemoryEntity memory) {
            if (!memories.containsKey(memory.id)) {
                memories.put(memory.id, memory);
            }
        }

        @Override
        public int writeUsage(List<UsageCallEntity> calls) {
            int written = 0;
            for (UsageCallEntity call : calls) {
                boolean duplicate = false;
                for (UsageCallEntity existing : usage) {
                    if (existing.id.equals(call.id)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    usage.add(call);
                    written++;
                }
            }
            return written;
        }
    }
}
