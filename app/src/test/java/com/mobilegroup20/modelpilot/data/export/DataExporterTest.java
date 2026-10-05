package com.mobilegroup20.modelpilot.data.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.FxRate;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.Test;

/**
 * 导出的规矩。这些规矩的共性是：<b>写错了不会报错，只会导出一份看起来正常、
 * 实际在骗人的文件</b>——未知被写成 0、本机路径被带出去、只选用量却把聊天记录也读了。
 * 所以每一条都有一个测试。
 */
public class DataExporterTest {

    /** 2026-10-06 15:30（北京时间）= 07:30 UTC。 */
    private static final long AT = 1_791_271_800_000L;

    private static final String UID = "local";

    // ==================== 范围 ====================

    @Test
    public void usageOnlyExportNeverReadsTheChatTables() {
        FakeSource source = new FakeSource();
        source.calls.add(call("c1", "2026-10-05", 1_000L, null));
        source.failIfChatsRead = true;

        ExportResult result = export(source, request(EnumSet.of(ExportScope.USAGE),
                ExportFormat.JSON, null, null), FxRate.UNSET);

        assertNotNull(result.artifact());
        assertFalse("只选用量时不该碰聊天表", source.chatTablesRead);
    }

    @Test
    public void conversationsOnlyExportNeverReadsTheLedger() {
        FakeSource source = new FakeSource();
        source.chats.add(chat("ch1", "第一次对话", "p1"));
        source.messages.put("ch1", Collections.singletonList(message("m1", "ch1", "USER", "你好")));
        source.failIfUsageRead = true;

        export(source, request(EnumSet.of(ExportScope.CONVERSATIONS), ExportFormat.JSON, null, null),
                FxRate.UNSET);

        assertFalse("只选对话时不该读账本", source.usageRead);
    }

    @Test
    public void usageRangeGoesToTheSourceAndIntoMeta() {
        FakeSource source = new FakeSource();
        source.calls.add(call("c1", "2026-09-01", 1_000L, null));

        ExportResult result = export(source,
                request(EnumSet.of(ExportScope.USAGE), ExportFormat.JSON, "2026-09-01", "2026-09-30"),
                FxRate.UNSET);

        assertEquals("2026-09-01", source.askedFrom);
        assertEquals("2026-09-30", source.askedTo);
        JsonObject range = json(result).getAsJsonObject("meta").getAsJsonObject("usageRange");
        assertEquals("2026-09-01", range.get("from").getAsString());
        assertEquals("2026-09-30", range.get("to").getAsString());
    }

    @Test
    public void unlimitedRangeIsWrittenAsNullNotAsTheWordAll() {
        FakeSource source = new FakeSource();
        ExportResult result = export(source, ExportRequest.usage(AT), FxRate.UNSET);

        JsonObject range = json(result).getAsJsonObject("meta").getAsJsonObject("usageRange");
        assertTrue("不限就该是 null，程序拿到文件要能判断", range.get("from").isJsonNull());
        assertTrue(range.get("to").isJsonNull());
        assertNull(source.askedFrom);
        assertNull(source.askedTo);
    }

    @Test
    public void rejectsAnEmptyScopeOrAReversedRange() {
        try {
            new ExportRequest(EnumSet.noneOf(ExportScope.class), ExportFormat.JSON, null, null, AT);
            fail("空范围导出的文件没有任何意义");
        } catch (IllegalArgumentException expected) {
            // 期望
        }
        try {
            // 悄悄交换会让 meta 里写的范围与用户选的反过来，而拿到文件的人发现不了。
            new ExportRequest(EnumSet.of(ExportScope.USAGE), ExportFormat.JSON,
                    "2026-10-05", "2026-10-01", AT);
            fail("起止日反了必须当场抛");
        } catch (IllegalArgumentException expected) {
            // 期望
        }
    }

    // ==================== 不知道就留空 ====================

    @Test
    public void unknownCostIsAnEmptyCellNeverZero() {
        FakeSource source = new FakeSource();
        source.calls.add(call("c1", "2026-10-05", 1_000L, null));   // 查不到费率
        source.calls.add(callWithCost("c2", "2026-10-05", 2_000L, 4_312L));

        String csv = table(export(source, request(EnumSet.of(ExportScope.USAGE),
                ExportFormat.CSV, null, null), FxRate.UNSET), "usage.csv");
        String[] lines = csv.split("\r\n");
        String header = lines[0];
        int costAt = indexOf(header, "cost_micros_usd");
        int amountAt = indexOf(header, "cost_amount_usd");

        assertEquals("", cell(lines[1], costAt));
        assertEquals("", cell(lines[1], amountAt));
        assertEquals("4312", cell(lines[2], costAt));
        assertFalse("不知道的金额不能变成 0", "0".equals(cell(lines[1], costAt)));
    }

    @Test
    public void unknownCnyStaysEmptyWhenNoRateIsConfigured() {
        FakeSource source = new FakeSource();
        source.calls.add(callWithCost("c1", "2026-10-05", 1_000L, 4_312L));

        String csv = table(export(source, request(EnumSet.of(ExportScope.USAGE),
                ExportFormat.CSV, null, null), FxRate.UNSET), "usage.csv");
        int cnyAt = indexOf(csv.split("\r\n")[0], "cost_amount_cny");
        assertEquals("没填汇率就不换算，宁可空着", "", cell(csv.split("\r\n")[1], cnyAt));
    }

    @Test
    public void cnyColumnIsFilledAndExplainedWhenTheUserHasSetARate() {
        FakeSource source = new FakeSource();
        source.calls.add(callWithCost("c1", "2026-10-05", 1_000L, 100_000_000L)); // $100
        FxRate rate = new FxRate(7_150_000L, AT, "招行现汇卖出价");

        ExportResult result = export(source, request(EnumSet.of(ExportScope.USAGE),
                ExportFormat.CSV, null, null), rate);
        String csv = table(result, "usage.csv");
        int cnyAt = indexOf(csv.split("\r\n")[0], "cost_amount_cny");
        assertEquals("¥715.00", cell(csv.split("\r\n")[1], cnyAt));

        // 汇率是用户填的、带来源和日期，导出里必须能查到这个出处。
        JsonObject display = meta(result).getAsJsonObject("display");
        assertEquals("CNY", display.get("currency").getAsString());
        assertEquals(7_150_000L, display.get("cnyPerUsdMicros").getAsLong());
        assertEquals("招行现汇卖出价", display.get("fxSource").getAsString());
        assertNotNull(display.get("fxEnteredAt"));
    }

    @Test
    public void messageTokenZeroMeansUnknownSoItIsOmitted() {
        FakeSource source = new FakeSource();
        source.chats.add(chat("ch1", "标题", "p1"));
        MessageEntity message = message("m1", "ch1", "ASSISTANT", "答");
        message.tokensIn = 0;       // 库里 0 的定义就是"还没拿到上游用量"
        message.tokensOut = 0;
        source.messages.put("ch1", Collections.singletonList(message));

        JsonObject row = conversations(export(source, request(EnumSet.of(ExportScope.CONVERSATIONS),
                ExportFormat.JSON, null, null), FxRate.UNSET)).get(0).getAsJsonObject()
                .getAsJsonArray("messages").get(0).getAsJsonObject();
        assertFalse("0 按库里的定义是'不知道'，不该写成 0", row.has("tokensIn"));

        MessageEntity measured = message("m2", "ch1", "ASSISTANT", "答");
        measured.tokensIn = 812;
        measured.tokensOut = 64;
        source.messages.put("ch1", Collections.singletonList(measured));
        JsonObject measuredRow = conversations(export(source, request(EnumSet.of(ExportScope.CONVERSATIONS),
                ExportFormat.JSON, null, null), FxRate.UNSET)).get(0).getAsJsonObject()
                .getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals(812L, measuredRow.get("tokensIn").getAsLong());
    }

    @Test
    public void unknownRouteAndKindAreOmittedFromJsonRows() {
        FakeSource source = new FakeSource();
        UsageCallEntity imported = call("c1", "2026-10-05", 1_000L, null);
        imported.route = null;      // 导入的记录没有"自动还是手动"这个概念
        imported.kind = null;
        imported.reason = null;
        source.calls.add(imported);

        JsonObject row = json(export(source, ExportRequest.usage(AT), FxRate.UNSET))
                .getAsJsonArray("usage").get(0).getAsJsonObject();
        assertFalse(row.has("route"));
        assertFalse(row.has("kind"));
        assertFalse(row.has("reason"));
    }

    // ==================== 不带出去的东西 ====================

    @Test
    public void attachmentsLoseTheirLocalUriButKeepNameSizeAndText() {
        FakeSource source = new FakeSource();
        source.chats.add(chat("ch1", "带着 PDF 的对话", "p1"));
        MessageEntity message = message("m1", "ch1", "USER", "看看这份");
        message.attachmentsJson = "[{\"kind\":\"PDF\",\"fileName\":\"报告.pdf\","
                + "\"uri\":\"content://com.android.providers.downloads.documents/document/42\","
                + "\"bytes\":12345,\"text\":\"第一页的内容\"}]";
        source.messages.put("ch1", Collections.singletonList(message));

        ExportResult jsonExport = export(source, request(EnumSet.of(ExportScope.CONVERSATIONS),
                ExportFormat.JSON, null, null), FxRate.UNSET);
        JsonObject attachment = conversations(jsonExport).get(0).getAsJsonObject()
                .getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonArray("attachments").get(0).getAsJsonObject();
        assertEquals("报告.pdf", attachment.get("fileName").getAsString());
        assertEquals(12345L, attachment.get("bytes").getAsLong());
        assertEquals("第一页的内容", attachment.get("text").getAsString());
        assertFalse("本机路径换台设备没用，还会暴露文件来自哪个应用", attachment.has("uri"));

        ExportResult csvExport = export(source, request(EnumSet.of(ExportScope.CONVERSATIONS),
                ExportFormat.CSV, null, null), FxRate.UNSET);
        assertFalse(text(csvExport, "conversations.csv").contains("content://"));
        assertFalse(text(csvExport, "meta.json").contains("content://"));
    }

    @Test
    public void noOutputMentionsKeysUrlsOrEndpoints() {
        FakeSource source = new FakeSource();
        source.chats.add(chat("ch1", "对话", "p1"));
        source.messages.put("ch1", Collections.singletonList(message("m1", "ch1", "USER", "你好")));
        source.calls.add(callWithCost("c1", "2026-10-05", 1_000L, 4_312L));

        ExportRequest both = request(EnumSet.of(ExportScope.CONVERSATIONS, ExportScope.USAGE),
                ExportFormat.CSV, null, null);
        ExportResult result = export(source, both, FxRate.UNSET);

        for (String name : new String[]{"meta.json", "usage.csv", "conversations.csv",
                "projects.csv", "memory.csv"}) {
            String body = text(result, name).toLowerCase(java.util.Locale.ROOT);
            assertFalse(name + " 里出现了 content://", body.contains("content://"));
            assertFalse(name + " 里出现了 api key 字样", body.contains("api_key"));
            assertFalse(name + " 里出现了 base url 字样", body.contains("base_url"));
            assertFalse(name + " 里出现了 endpoint 字样", body.contains("endpoint"));
        }
    }

    // ==================== 产物形状 ====================

    @Test
    public void jsonExportIsOneReadableFile() {
        FakeSource source = new FakeSource();
        source.chats.add(chat("ch1", "对话", "p1"));
        source.messages.put("ch1", Collections.singletonList(message("m1", "ch1", "USER", "你好")));
        source.memories.put("ch1", Collections.singletonList(memory("mem1", "ch1", "聊了打招呼")));
        source.projects.add(project("p1", "作业", "用中文回答"));

        ExportResult result = export(source, request(
                EnumSet.of(ExportScope.CONVERSATIONS, ExportScope.USAGE),
                ExportFormat.JSON, null, null), FxRate.UNSET);

        assertEquals("modelpilot-export-20261006-1530.json", result.artifact().fileName);
        assertEquals("application/json", result.artifact().mimeType());

        JsonObject root = json(result);
        assertTrue(root.has("meta"));
        assertTrue(root.has("projects"));
        assertTrue(root.has("conversations"));
        assertTrue(root.has("usage"));
        JsonObject chat = root.getAsJsonArray("conversations").get(0).getAsJsonObject();
        assertEquals("对话", chat.get("title").getAsString());
        assertEquals("你好", chat.getAsJsonArray("messages").get(0).getAsJsonObject()
                .get("text").getAsString());
        assertEquals("聊了打招呼", chat.getAsJsonArray("memory").get(0).getAsJsonObject()
                .get("summary").getAsString());
        // 中文不转义成 Unicode 转义序列：对话里全是中文，转了就没法读。
        assertTrue(text(result.artifact()).contains("你好"));
    }

    @Test
    public void csvExportIsAZipWithOneFilePerTablePlusMeta() {
        FakeSource source = new FakeSource();
        source.projects.add(project("p1", "作业", "用中文回答"));
        source.chats.add(chat("ch1", "对话", "p1"));
        source.messages.put("ch1", Collections.singletonList(message("m1", "ch1", "USER", "你好")));
        source.memories.put("ch1", Collections.singletonList(memory("mem1", "ch1", "摘要")));
        source.calls.add(call("c1", "2026-10-05", 1_000L, null));

        ExportResult result = export(source, request(
                EnumSet.of(ExportScope.CONVERSATIONS, ExportScope.USAGE),
                ExportFormat.CSV, null, null), FxRate.UNSET);

        assertEquals("modelpilot-export-20261006-1530.zip", result.artifact().fileName);
        assertEquals("application/zip", result.artifact().mimeType());
        Map<String, String> files = unzip(result.artifact());
        assertEquals("[meta.json, projects.csv, conversations.csv, memory.csv, usage.csv]",
                files.keySet().toString());

        // 一张表一个文件：对话那一张里每行都带着它所属对话的标题，打开就能看懂。
        String messages = files.get("conversations.csv");
        assertTrue(messages.startsWith("\uFEFF"));
        String[] lines = messages.substring(1).split("\r\n");
        assertEquals("chat_id,chat_title,project_id,project_name,message_id,role,created_at,"
                + "created_at_ms,provider_id,model_id,route,tokens_in,tokens_out,"
                + "attachment_count,attachment_files,text", lines[0]);
        assertEquals("ch1", cell(lines[1], 0));
        assertEquals("对话", cell(lines[1], 1));
        assertEquals("作业", cell(lines[1], 3));
        assertEquals("你好", cell(lines[1], 15));
    }

    @Test
    public void countsSayHowMuchWasActuallyWritten() {
        FakeSource source = new FakeSource();
        source.projects.add(project("p1", "作业", ""));
        source.chats.add(chat("ch1", "对话", "p1"));
        source.chats.add(chat("ch2", "第二条", "p1"));
        source.messages.put("ch1", Arrays.asList(
                message("m1", "ch1", "USER", "一"), message("m2", "ch1", "ASSISTANT", "二")));
        source.messages.put("ch2", Collections.singletonList(message("m3", "ch2", "USER", "三")));
        source.memories.put("ch1", Collections.singletonList(memory("mem1", "ch1", "摘要")));
        source.calls.add(call("c1", "2026-10-05", 1_000L, null));

        ExportResult result = export(source, request(
                EnumSet.of(ExportScope.CONVERSATIONS, ExportScope.USAGE),
                ExportFormat.JSON, null, null), FxRate.UNSET);

        assertEquals(1, result.projects);
        assertEquals(2, result.conversations);
        assertEquals(3, result.messages);
        assertEquals(1, result.memories);
        assertEquals(1, result.usageCalls);

        JsonObject counts = meta(result).getAsJsonObject("counts");
        assertEquals(3L, counts.get("messages").getAsLong());
        assertEquals(1L, counts.get("usageCalls").getAsLong());
    }

    @Test
    public void emptyDatabaseStillProducesAValidFileThatSaysZero() {
        FakeSource source = new FakeSource();
        ExportResult result = export(source, request(
                EnumSet.of(ExportScope.CONVERSATIONS, ExportScope.USAGE),
                ExportFormat.JSON, null, null), FxRate.UNSET);

        JsonObject root = json(result);
        assertEquals(0, root.getAsJsonArray("conversations").size());
        assertEquals(0, root.getAsJsonArray("usage").size());
        assertEquals(0L, meta(result).getAsJsonObject("counts").get("messages").getAsLong());
    }

    @Test
    public void metaSaysWhatIsMissingInPlainWords() {
        FakeSource source = new FakeSource();
        ExportResult result = export(source, request(
                EnumSet.of(ExportScope.CONVERSATIONS, ExportScope.USAGE),
                ExportFormat.JSON, null, null), FxRate.UNSET);

        JsonArray notes = meta(result).getAsJsonArray("notes");
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            all.append(notes.get(i).getAsString()).append('\n');
        }
        String text = all.toString();
        assertTrue(text.contains("不含任何 API key"));
        assertTrue("没填汇率就得说清楚为什么没有人民币金额", text.contains("没有填写汇率"));
        assertTrue(text.contains("本机文件路径"));
        assertTrue(text.contains("公式"));
        // 时间是按北京时间切的，文件里要写明，免得和按 UTC 切天的官方账单对不上时被当成 bug。
        assertEquals("Asia/Shanghai", meta(result).get("timeZone").getAsString());
    }

    // ==================== 工具 ====================

    private static ExportResult export(FakeSource source, ExportRequest request, FxRate rate) {
        return new DataExporter(source, UID, "1.0", "CNY", rate).export(request);
    }

    private static ExportRequest request(EnumSet<ExportScope> scopes, ExportFormat format,
                                        @Nullable String from, @Nullable String to) {
        return new ExportRequest(scopes, format, from, to, AT);
    }

    private static JsonObject json(ExportResult result) {
        return JsonParser.parseString(text(result.artifact())).getAsJsonObject();
    }

    private static JsonObject meta(ExportResult result) {
        if (result.artifact().fileName.endsWith(".zip")) {
            return JsonParser.parseString(unzip(result.artifact()).get("meta.json")).getAsJsonObject();
        }
        return json(result).getAsJsonObject("meta");
    }

    private static JsonArray conversations(ExportResult result) {
        return json(result).getAsJsonArray("conversations");
    }

    private static String text(ExportArtifact artifact) {
        String all = new String(artifact.bytes, StandardCharsets.UTF_8);
        return all.startsWith("\uFEFF") ? all.substring(1) : all;
    }

    private static String text(ExportResult result, String entry) {
        if (result.artifact().fileName.endsWith(".zip")) {
            return unzip(result.artifact()).get(entry);
        }
        return text(result.artifact());
    }

    private static String table(ExportResult result, String entry) {
        return text(result, entry);
    }

    private static Map<String, String> unzip(ExportArtifact artifact) {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(artifact.bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    body.write(buffer, 0, read);
                }
                files.put(entry.getName(), new String(body.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException broken) {
            throw new AssertionError("导出的 zip 读不回来", broken);
        }
        return files;
    }

    private static int indexOf(String header, String column) {
        String[] columns = header.split(",");
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equals(column)) {
                return i;
            }
        }
        throw new AssertionError("表头里没有 " + column + "：" + header);
    }

    /** 取一格。**只支持没有引号的简单情况**，够这里用（有引号的用例在 CsvTest 里）。 */
    private static String cell(String line, int index) {
        String[] cells = line.split(",", -1);
        return index < cells.length ? cells[index] : "";
    }

    private static ProjectEntity project(String id, String name, String instructions) {
        ProjectEntity project = new ProjectEntity();
        project.id = id;
        project.name = name;
        project.instructions = instructions;
        project.createdAtEpochMillis = AT - 100_000L;
        project.updatedAtEpochMillis = AT - 50_000L;
        return project;
    }

    private static ChatEntity chat(String id, String title, String projectId) {
        ChatEntity chat = new ChatEntity();
        chat.id = id;
        chat.title = title;
        chat.projectId = projectId;
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
        memory.createdAtEpochMillis = AT - 9_000L;
        return memory;
    }

    private static UsageCallEntity call(String id, String day, long at, @Nullable Long costMicros) {
        UsageCallEntity call = new UsageCallEntity();
        call.id = id;
        call.uid = UID;
        call.provider = Provider.DEEPSEEK;
        call.model = "deepseek-chat";
        call.startedAtEpochMillis = at;
        call.day = day;
        call.input = 1_000;
        call.output = 200;
        call.source = UsageCall.Source.IMPORTED;
        call.costMicros = costMicros;
        return call;
    }

    private static UsageCallEntity callWithCost(String id, String day, long at, long costMicros) {
        UsageCallEntity call = call(id, day, at, costMicros);
        call.source = UsageCall.Source.APP;
        call.route = "AUTO";
        call.kind = "ANSWER";
        call.reason = "最便宜的可用路由";
        call.policy = "capability-then-price-v1";
        call.rateVersion = "deepseek-2026-09-01";
        call.costCurrency = "USD";
        return call;
    }

    /** 假数据源。**记下被问了什么**，这样「该读的读了没有」和「范围传对了没有」都能断言。 */
    private static final class FakeSource implements ExportSource {

        final List<ProjectEntity> projects = new ArrayList<>();
        final List<ChatEntity> chats = new ArrayList<>();
        final Map<String, List<MessageEntity>> messages = new LinkedHashMap<>();
        final Map<String, List<MemoryEntity>> memories = new LinkedHashMap<>();
        final List<UsageCallEntity> calls = new ArrayList<>();

        @Nullable String askedFrom;
        @Nullable String askedTo;
        boolean chatTablesRead;
        boolean usageRead;
        boolean failIfChatsRead;
        boolean failIfUsageRead;

        @Override
        public List<ProjectEntity> projects() {
            chatTablesRead = true;
            if (failIfChatsRead) {
                throw new AssertionError("这次导出不该读项目表");
            }
            return projects;
        }

        @Override
        public List<ChatEntity> chats() {
            chatTablesRead = true;
            if (failIfChatsRead) {
                throw new AssertionError("这次导出不该读对话表");
            }
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
            usageRead = true;
            if (failIfUsageRead) {
                throw new AssertionError("这次导出不该读账本");
            }
            askedFrom = fromDay;
            askedTo = toDay;
            return calls;
        }
    }
}
