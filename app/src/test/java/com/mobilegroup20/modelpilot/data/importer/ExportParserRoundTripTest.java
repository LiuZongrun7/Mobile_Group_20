package com.mobilegroup20.modelpilot.data.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.Nullable;

import com.mobilegroup20.modelpilot.chat.AttachmentCodec;
import com.mobilegroup20.modelpilot.chat.CanonicalMessage;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.FxRate;
import com.mobilegroup20.modelpilot.data.export.DataExporter;
import com.mobilegroup20.modelpilot.data.export.ExportArtifact;
import com.mobilegroup20.modelpilot.data.export.ExportFormat;
import com.mobilegroup20.modelpilot.data.export.ExportRequest;
import com.mobilegroup20.modelpilot.data.export.ExportScope;
import com.mobilegroup20.modelpilot.data.export.ExportSource;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * 「导出 → 导入」的往返：拿<b>真的导出器</b>吐出来的字节喂给导入器，断言两边说的是同一件事。
 *
 * <p>为什么要这样测，而不是手写一份样例文件：两份手写的东西迟早会一起漂移，
 * 漂到最后测试全绿而真机上一导就坏。走真导出器意味着<b>任何一边改了字段名，
 * 这个测试立刻红</b>——它钉的是"两边对得上"这件事本身，而那正是这个功能唯一的失败模式。
 *
 * <p>JSON 与 CSV 两条路都测，但期望值不同：CSV 是扁平的，附件只剩名字、工具调用没有列，
 * 所以它的断言里明确写着"这些本来就回不来"——我们不假装它和 JSON 一样完整。
 */
public class ExportParserRoundTripTest {

    /** 2026-10-06 15:30（北京时间）= 07:30 UTC。 */
    private static final long AT = 1_791_271_800_000L;

    private static final String UID = "local";

    // ==================== JSON ====================

    @Test
    public void jsonRoundTripKeepsEverything() throws Exception {
        FakeSource source = fullSource();

        ImportBundle bundle = ExportFileReader.read(export(source, ExportFormat.JSON), "export.json");

        assertEquals("JSON", bundle.format);
        assertEquals(ImportBundle.Coverage.FULL, bundle.coverage);
        assertTrue(bundle.problems.isEmpty());
        assertTrue("是我们家的文件", bundle.facts.isFromThisApp());
        assertEquals(ImportFacts.APP, bundle.facts.app);
        assertEquals("1.0", bundle.facts.appVersion);
        assertEquals(AT, bundle.facts.generatedAtEpochMillis);
        assertEquals("local", bundle.facts.account);
        assertTrue(bundle.facts.hasConversations);
        assertTrue(bundle.facts.hasUsage);

        assertEquals(1, bundle.projects.size());
        ProjectEntity project = bundle.projects.get(0);
        assertEquals("p1", project.id);
        assertEquals("COMP3011", project.name);
        assertEquals("用中文回答", project.instructions);
        assertEquals(2, project.colorIndex);
        assertEquals(AT - 100_000L, project.createdAtEpochMillis);

        assertEquals(1, bundle.conversations.size());
        ImportBundle.Conversation conversation = bundle.conversations.get(0);
        assertEquals("ch1", conversation.chat.id);
        assertEquals("第一次对话", conversation.chat.title);
        assertEquals("p1", conversation.chat.projectId);
        assertEquals("DEEPSEEK", conversation.chat.lastProviderId);
        assertEquals("deepseek-chat", conversation.chat.lastModelId);
        assertEquals(2, conversation.messages.size());

        MessageEntity first = conversation.messages.get(0);
        assertEquals("m1", first.id);
        assertEquals("ch1", first.chatId);
        assertEquals("USER", first.role);
        assertEquals("你好，带 , 逗号和 \"引号\"", first.text);
        assertEquals(1_000L, first.tokensIn);
        assertEquals(200L, first.tokensOut);
        assertEquals("DEEPSEEK", first.providerId);
        assertEquals("deepseek-chat", first.modelId);
        assertEquals("MANUAL", first.route);
        assertEquals(AT - 10_000L, first.createdAtEpochMillis);
        // 附件：类型、文件名、大小、抽取正文都在；本机 uri 不在（导出就故意没写）。
        List<CanonicalMessage.Attachment> attachments =
                AttachmentCodec.fromJson(first.attachmentsJson);
        assertEquals(1, attachments.size());
        assertEquals(CanonicalMessage.Attachment.Kind.PDF, attachments.get(0).kind);
        assertEquals("作业.pdf", attachments.get(0).fileName);
        assertEquals(12_345L, attachments.get(0).bytes);
        assertEquals("PDF 正文", attachments.get(0).extractedText);
        assertTrue("本机路径永远不回来，回来的是空",
                attachments.get(0).uri == null || attachments.get(0).uri.isEmpty());

        assertEquals(1, conversation.memories.size());
        MemoryEntity memory = conversation.memories.get(0);
        assertEquals("mem1", memory.id);
        assertEquals("ch1", memory.chatId);
        assertEquals("m1", memory.fromMessageId);
        assertEquals("m2", memory.toMessageId);
        assertEquals("之前聊了作业", memory.summary);
        assertEquals("DEEPSEEK", memory.madeByProvider);
        assertEquals("deepseek-chat", memory.madeByModel);
        assertEquals(500L, memory.tokensIn);
        assertEquals(90L, memory.tokensOut);
        assertTrue(memory.editedByUser);

        assertEquals(1, bundle.usageCalls.size());
        UsageCall call = bundle.usageCalls.get(0);
        assertEquals("call:deepseek:req-1:2026-10-06", call.id);
        assertEquals(Provider.DEEPSEEK, call.provider);
        assertEquals("deepseek-chat", call.model);
        assertEquals(AT - 5_000L, call.startedAtEpochMillis);
        assertEquals(1_000L, call.input);
        assertEquals(300L, call.cacheRead);
        assertEquals(0L, call.cacheWrite);
        assertEquals(200L, call.output);
        assertEquals(Long.valueOf(4_321L), call.costMicros);
        assertEquals(UsageCall.Source.APP, call.source);
        assertEquals("AUTO", call.route);
        assertEquals("ANSWER", call.kind);
        assertEquals("只看对话", call.reason);
        assertEquals("capability-then-price-v1", call.policy);
        assertEquals("deepseek-2026-09-01", call.rateVersion);
        assertEquals(Long.valueOf(30_000L), call.nativeCostMicros);
        assertEquals("CNY", call.costCurrency);
    }

    @Test
    public void unknownValuesStayUnknownInsteadOfBecomingZero() throws Exception {
        FakeSource source = new FakeSource();
        source.chats.add(chat("ch1", "散的对话", ""));
        MessageEntity message = message("m1", "ch1", "USER", "嗨");
        message.tokensIn = 0L;          // 「还不知道」——导出不写这个字段
        message.tokensOut = 0L;
        message.providerId = null;
        message.modelId = null;
        message.route = null;
        source.messages.put("ch1", Collections.singletonList(message));
        source.calls.add(callWithoutCost("call:x:1:2026-10-06"));

        ImportBundle bundle = ExportFileReader.read(export(source, ExportFormat.JSON), "e.json");

        MessageEntity read = bundle.conversations.get(0).messages.get(0);
        assertEquals("没拿到用量就是 0，不是别的数", 0L, read.tokensIn);
        assertNull(read.providerId);
        assertNull(read.modelId);
        assertNull("没有路由理由就是没有，不写 MANUAL", read.route);
        // 金额不知道：不能变成 0（0 是一笔金额，会被加进合计）。
        assertNull(bundle.usageCalls.get(0).costMicros);
        assertNull(bundle.usageCalls.get(0).nativeCostMicros);
        assertNull(bundle.usageCalls.get(0).costCurrency);
    }

    // ==================== CSV（zip） ====================

    @Test
    public void csvRoundTripKeepsConversationsAndUsage() throws Exception {
        ImportBundle bundle = ExportFileReader.read(export(fullSource(), ExportFormat.CSV),
                "export.zip");

        assertEquals("CSV (zip)", bundle.format);
        assertEquals("CSV 是扁平的，这件事要如实标出来", ImportBundle.Coverage.TABULAR, bundle.coverage);
        assertTrue(bundle.problems.isEmpty());

        assertEquals(1, bundle.conversations.size());
        ImportBundle.Conversation conversation = bundle.conversations.get(0);
        assertEquals("ch1", conversation.chat.id);
        assertEquals("第一次对话", conversation.chat.title);
        assertEquals("p1", conversation.chat.projectId);
        // 对话级的 last_* 在 CSV 里没有列，从最后一条带模型的消息推回来。
        assertEquals("deepseek-chat", conversation.chat.lastModelId);
        assertEquals("DEEPSEEK", conversation.chat.lastProviderId);
        assertEquals(2, conversation.messages.size());
        assertEquals("消息按时间升序排", "m1", conversation.messages.get(0).id);
        assertEquals("m2", conversation.messages.get(1).id);
        assertEquals("你好，带 , 逗号和 \"引号\"", conversation.messages.get(0).text);
        assertEquals(1_000L, conversation.messages.get(0).tokensIn);
        assertEquals(200L, conversation.messages.get(0).tokensOut);
        assertEquals("USER", conversation.messages.get(0).role);
        assertEquals("ASSISTANT", conversation.messages.get(1).role);

        // 附件只剩下"有这么个文件、名字是这个"——类型和正文都回不来（CSV 就没这些列）。
        List<CanonicalMessage.Attachment> attachments =
                AttachmentCodec.fromJson(conversation.messages.get(0).attachmentsJson);
        assertEquals(1, attachments.size());
        assertEquals("作业.pdf", attachments.get(0).fileName);
        assertNull(attachments.get(0).extractedText);

        assertEquals(1, conversation.memories.size());
        assertEquals("mem1", conversation.memories.get(0).id);
        assertEquals("之前聊了作业", conversation.memories.get(0).summary);
        assertTrue(conversation.memories.get(0).editedByUser);

        assertEquals(1, bundle.usageCalls.size());
        assertEquals("call:deepseek:req-1:2026-10-06", bundle.usageCalls.get(0).id);
        assertEquals(Long.valueOf(4_321L), bundle.usageCalls.get(0).costMicros);
        assertEquals(UsageCall.Source.APP, bundle.usageCalls.get(0).source);
        assertEquals("AUTO", bundle.usageCalls.get(0).route);
        assertEquals(300L, bundle.usageCalls.get(0).cacheRead);
    }

    @Test
    public void csvKeepsAMessageThatContainsNewlinesAndCommas() throws Exception {
        FakeSource source = new FakeSource();
        source.chats.add(chat("ch1", "多行", ""));
        String tricky = "第一行\n第二行, 带逗号\n\"引号\" 与 =公式";
        source.messages.put("ch1", Collections.singletonList(
                message("m1", "ch1", "USER", tricky)));

        ImportBundle bundle = ExportFileReader.read(export(source, ExportFormat.CSV), "e.zip");

        assertEquals(tricky, bundle.conversations.get(0).messages.get(0).text);
    }

    // ==================== 坏文件 ====================

    @Test
    public void refusesSomethingThatIsNotAnExport() {
        assertReason(ImportFileException.Reason.NOT_OUR_FILE,
                "一张图.png".getBytes(StandardCharsets.UTF_8), "一张图.png");
        assertReason(ImportFileException.Reason.NOT_OUR_FILE,
                "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8), "x.json");
        assertReason(ImportFileException.Reason.EMPTY, new byte[0], "空文件.json");
    }

    @Test
    public void refusesAFileThatSaysItCameFromAnotherApp() {
        String json = "{\"meta\":{\"app\":\"SomeOtherApp\"},\"conversations\":[]}";
        assertReason(ImportFileException.Reason.NOT_OUR_FILE,
                json.getBytes(StandardCharsets.UTF_8), "别的App.json");
    }

    @Test
    public void refusesAFileWithNothingInIt() {
        String json = "{\"meta\":{\"app\":\"ModelPilot\"},\"conversations\":[],\"usage\":[]}";
        assertReason(ImportFileException.Reason.NO_CONTENT,
                json.getBytes(StandardCharsets.UTF_8), "空导出.json");
    }

    @Test
    public void refusesBrokenJsonWithoutLeakingItsContent() {
        try {
            ExportFileReader.read("{ this is not json".getBytes(StandardCharsets.UTF_8), "坏.json");
            fail("坏 JSON 必须被挡住");
        } catch (ImportFileException expected) {
            assertEquals(ImportFileException.Reason.UNREADABLE, expected.reason);
            assertFalse("错误消息里不许夹带文件内容",
                    expected.problem.contains("this is not json"));
        }
    }

    @Test
    public void aRowWithoutAnIdIsSkippedAndReported() throws Exception {
        String json = "{\"meta\":{\"app\":\"ModelPilot\",\"appVersion\":\"1.0\"},"
                + "\"conversations\":[{\"id\":\"ch1\",\"title\":\"t\",\"messages\":["
                + "{\"role\":\"USER\",\"text\":\"没有 id 的一条\"},"
                + "{\"id\":\"m2\",\"role\":\"USER\",\"text\":\"有 id 的\"}]}]}";

        ImportBundle bundle = ExportFileReader.read(json.getBytes(StandardCharsets.UTF_8), "x.json");

        assertEquals(1, bundle.conversations.get(0).messages.size());
        assertEquals("m2", bundle.conversations.get(0).messages.get(0).id);
        assertEquals("少了一条必须说出来", 1, bundle.problems.size());
    }

    // ==================== 小工具 ====================

    private static void assertReason(ImportFileException.Reason reason, byte[] bytes, String name) {
        try {
            ExportFileReader.read(bytes, name);
            fail("这份文件不该被读进来");
        } catch (ImportFileException expected) {
            assertEquals(reason, expected.reason);
            assertNotNull(expected.problem);
            assertFalse(expected.problem.isEmpty());
        }
    }

    private static byte[] export(ExportSource source, ExportFormat format) {
        DataExporter exporter = new DataExporter(source, UID, "1.0", "CNY", FxRate.UNSET);
        ExportRequest request = new ExportRequest(
                EnumSet.of(ExportScope.CONVERSATIONS, ExportScope.USAGE), format, null, null, AT);
        ExportArtifact artifact = exporter.export(request).artifact();
        return artifact.bytes;
    }

    /** 一份各字段都填满的数据：往返测试要能发现"某个字段其实没写出去"。 */
    private static FakeSource fullSource() {
        FakeSource source = new FakeSource();
        source.projects.add(project("p1", "COMP3011", "用中文回答"));
        source.chats.add(chat("ch1", "第一次对话", "p1"));
        MessageEntity user = message("m1", "ch1", "USER", "你好，带 , 逗号和 \"引号\"");
        user.providerId = "DEEPSEEK";
        user.modelId = "deepseek-chat";
        user.route = "MANUAL";
        user.tokensIn = 1_000L;
        user.tokensOut = 200L;
        user.attachmentsJson = AttachmentCodec.toJson(Collections.singletonList(
                new CanonicalMessage.Attachment(CanonicalMessage.Attachment.Kind.PDF,
                        "作业.pdf", "content://media/1234", 12_345L, "PDF 正文")));
        MessageEntity assistant = message("m2", "ch1", "ASSISTANT", "好的");
        assistant.createdAtEpochMillis = AT - 9_000L;
        assistant.providerId = "DEEPSEEK";
        assistant.modelId = "deepseek-chat";
        source.messages.put("ch1", Arrays.asList(user, assistant));
        source.memories.put("ch1", Collections.singletonList(memory("mem1", "ch1", "之前聊了作业")));
        UsageCallEntity call = callWithoutCost("call:deepseek:req-1:2026-10-06");
        call.costMicros = 4_321L;
        call.nativeCostMicros = 30_000L;
        call.costCurrency = "CNY";
        call.source = UsageCall.Source.APP;
        call.route = "AUTO";
        call.kind = "ANSWER";
        call.reason = "只看对话";
        call.policy = "capability-then-price-v1";
        call.rateVersion = "deepseek-2026-09-01";
        call.input = 1_000L;
        call.cacheRead = 300L;
        call.output = 200L;
        source.calls.add(call);
        return source;
    }

    private static ProjectEntity project(String id, String name, String instructions) {
        ProjectEntity project = new ProjectEntity();
        project.id = id;
        project.name = name;
        project.instructions = instructions;
        project.colorIndex = 2;
        project.createdAtEpochMillis = AT - 100_000L;
        project.updatedAtEpochMillis = AT - 50_000L;
        return project;
    }

    private static ChatEntity chat(String id, String title, String projectId) {
        ChatEntity chat = new ChatEntity();
        chat.id = id;
        chat.title = title;
        chat.projectId = projectId;
        chat.lastProviderId = "DEEPSEEK";
        chat.lastModelId = "deepseek-chat";
        chat.createdAtEpochMillis = AT - 40_000L;
        chat.updatedAtEpochMillis = AT - 20_000L;
        return chat;
    }

    private static MessageEntity message(String id, String chatId, String role, String text) {
        MessageEntity message = new MessageEntity();
        message.id = id;
        message.chatId = chatId;
        message.role = role;
        message.text = text;
        message.createdAtEpochMillis = AT - 10_000L;
        return message;
    }

    private static MemoryEntity memory(String id, String chatId, String summary) {
        MemoryEntity memory = new MemoryEntity();
        memory.id = id;
        memory.chatId = chatId;
        memory.fromMessageId = "m1";
        memory.toMessageId = "m2";
        memory.summary = summary;
        memory.madeByProvider = "DEEPSEEK";
        memory.madeByModel = "deepseek-chat";
        memory.tokensIn = 500L;
        memory.tokensOut = 90L;
        memory.editedByUser = true;
        memory.createdAtEpochMillis = AT - 9_500L;
        return memory;
    }

    private static UsageCallEntity callWithoutCost(String id) {
        UsageCallEntity call = new UsageCallEntity();
        call.id = id;
        call.uid = UID;
        call.provider = Provider.DEEPSEEK;
        call.model = "deepseek-chat";
        call.startedAtEpochMillis = AT - 5_000L;
        call.day = "2026-10-06";
        call.input = 1_000L;
        call.output = 200L;
        call.source = UsageCall.Source.APP;
        return call;
    }

    /** 假数据源（和 {@code DataExporterTest} 里那个同形）。 */
    private static final class FakeSource implements ExportSource {

        final List<ProjectEntity> projects = new ArrayList<>();
        final List<ChatEntity> chats = new ArrayList<>();
        final Map<String, List<MessageEntity>> messages = new LinkedHashMap<>();
        final Map<String, List<MemoryEntity>> memories = new LinkedHashMap<>();
        final List<UsageCallEntity> calls = new ArrayList<>();

        @Override
        public List<ProjectEntity> projects() {
            return projects;
        }

        @Override
        public List<ChatEntity> chats() {
            return chats;
        }

        @Override
        public List<MessageEntity> messages(String chatId) {
            return messages.getOrDefault(chatId, Collections.emptyList());
        }

        @Override
        public List<MemoryEntity> memories(String chatId) {
            return memories.getOrDefault(chatId, Collections.emptyList());
        }

        @Override
        public List<UsageCallEntity> usageCalls(@Nullable String fromDay, @Nullable String toDay) {
            return calls;
        }
    }
}
