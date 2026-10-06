package com.mobilegroup20.modelpilot.data.importer;

import androidx.annotation.Nullable;

import com.google.gson.JsonParser;
import com.mobilegroup20.modelpilot.chat.AttachmentCodec;
import com.mobilegroup20.modelpilot.chat.CanonicalMessage;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 读 CSV 那一份导出（{@code modelpilot-export-*.zip}）。
 *
 * <p>导出时 CSV 是"一张表一个文件 + meta.json"打成的 zip；这里反过来，
 * <b>把四张表拼回它本来描述的那几棵树</b>：
 * <ul>
 *   <li>{@code projects.csv} → 项目；</li>
 *   <li>{@code conversations.csv} <b>一行是一条消息</b>，每行都带着所属对话的 id 与标题——
 *       按 {@code chat_id} 分组、按 {@code created_at_ms} 排序就是一条对话；</li>
 *   <li>{@code memory.csv} → 摘要（按 {@code memory_id} 去重：一行本来就代表一条摘要）；</li>
 *   <li>{@code usage.csv} → 用量记录。</li>
 * </ul>
 *
 * <h3>CSV 这条路注定是不完整的，我们如实标出来</h3>
 * 为了"用 Excel 打开就能看懂"，导出把结构压扁了：附件只剩个数与文件名（正文没了）、
 * 工具调用根本没有列、{@code content://} 路径本来就不导。所以这条路读出来的
 * {@link ImportBundle.Coverage#TABULAR} 是"扁平"的——<b>换手机请用 JSON</b>，
 * 这句话在导出那边的 {@code meta.notes} 里就写着，这里不另说一套。
 *
 * <h3>认表头，不认列号</h3>
 * 每一列都按表头的名字取。导出的列顺序将来可能调整（中间插一列是常事），
 * 而按列号取会让那种改动变成"整行错位"——一种在结果里几乎看不出来的错。
 * 表头里少了必需的列，就当这份文件不是我们导的：宁可拒绝，也不要读出一半错位的数据。
 */
final class ExportZipParser {

    /** 单张表解出来最多多大（防 zip bomb：一个几 KB 的 zip 能解出好几个 GB）。 */
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

    private ExportZipParser() {
    }

    static ImportBundle parse(byte[] bytes) throws ImportFileException {
        Map<String, byte[]> entries = unzip(bytes);

        byte[] metaBytes = entries.get("meta.json");
        if (metaBytes == null) {
            throw new ImportFileException(ImportFileException.Reason.NOT_OUR_FILE,
                    "That zip has no meta.json, so it is not a ModelPilot export.");
        }
        byte[] conversationsBytes = entries.get("conversations.csv");
        byte[] projectsBytes = entries.get("projects.csv");
        byte[] memoryBytes = entries.get("memory.csv");
        byte[] usageBytes = entries.get("usage.csv");

        ExportJsonParser.Problems problems = new ExportJsonParser.Problems();

        List<ProjectEntity> projects = projects(table(projectsBytes, "projects",
                "id", "created_at_ms"), problems);
        List<ImportBundle.Conversation> conversations = conversations(
                table(conversationsBytes, "conversations",
                        "chat_id", "message_id", "role", "created_at_ms", "text"),
                memoryBytes == null ? null : table(memoryBytes, "memory",
                        "chat_id", "memory_id", "created_at_ms", "summary"),
                problems);
        List<UsageCall> usage = usage(table(usageBytes, "usage",
                "id", "day", "started_at_ms", "provider", "model"));

        ImportFacts facts = MetaParser.parse(meta(metaBytes),
                conversationsBytes != null, usageBytes != null);

        return new ImportBundle("CSV (zip)", facts, ImportBundle.Coverage.TABULAR,
                projects, conversations, usage, problems.list());
    }

    // ==================== zip ====================

    /** 解开 zip，只收我们认识的那几个条目；其它一律忽略（zip 里多几个文件不是错）。 */
    private static Map<String, byte[]> unzip(byte[] bytes) throws ImportFileException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName() == null ? "" : entry.getName();
                // 有些系统会把文件套一层目录（macOS 的"压缩"就是），所以按最后一段认名字。
                int slash = name.lastIndexOf('/');
                String base = slash >= 0 ? name.substring(slash + 1) : name;
                if (!isOurs(base)) {
                    continue;
                }
                out.put(base, readEntry(zip));
            }
        } catch (IOException broken) {
            throw new ImportFileException(ImportFileException.Reason.UNREADABLE,
                    "That zip file could not be opened (it may be incomplete or renamed).");
        }
        return out;
    }

    private static boolean isOurs(String base) {
        return "meta.json".equals(base) || "projects.csv".equals(base)
                || "conversations.csv".equals(base) || "memory.csv".equals(base)
                || "usage.csv".equals(base);
    }

    private static byte[] readEntry(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = zip.read(chunk)) > 0) {
            if (buffer.size() + read > MAX_ENTRY_BYTES) {
                throw new IOException("entry too large");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    // ==================== 表 ====================

    /**
     * 一张表：表头必须认得出来。
     *
     * <p>{@code null} 字节（文件里没有这张表）返回 {@code null}——"没有这张表"是正常的
     * （用户可能只导了用量），而"有这张表但表头不对"不是：那说明这不是我们的文件，
     * 直接拒绝比读出一半错位的数据好。
     */
    @Nullable
    private static CsvReader.Table table(@Nullable byte[] bytes, String name, String... required)
            throws ImportFileException {
        if (bytes == null) {
            return null;
        }
        List<List<String>> rows = CsvReader.read(bytes);
        if (rows.isEmpty()) {
            return null;    // 空表：那一半这次没导，不算错
        }
        if (!CsvReader.Table.hasHeader(rows, required)) {
            throw new ImportFileException(ImportFileException.Reason.NOT_OUR_FILE,
                    name + ".csv in that zip does not look like a ModelPilot export "
                            + "(missing expected columns).");
        }
        return CsvReader.Table.of(rows);
    }

    /** {@code meta.json} 解析；读不动就当没有自述（内容仍然可以导）。 */
    @Nullable
    private static com.google.gson.JsonObject meta(byte[] bytes) {
        try {
            com.google.gson.JsonElement parsed =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    // ==================== 项目 ====================

    private static List<ProjectEntity> projects(@Nullable CsvReader.Table table,
                                                ExportJsonParser.Problems problems) {
        List<ProjectEntity> out = new ArrayList<>();
        if (table == null) {
            return out;
        }
        for (List<String> row : table.data()) {
            String id = table.getOrEmpty(row, "id");
            if (id.isEmpty()) {
                problems.add("A project row in projects.csv had no id and was skipped.");
                continue;
            }
            ProjectEntity project = new ProjectEntity();
            project.id = id;
            project.name = table.getOrEmpty(row, "name");
            project.instructions = table.getOrEmpty(row, "instructions");
            project.colorIndex = (int) table.getLong(row, "color_index", 0L);
            project.createdAtEpochMillis =
                    table.getMillis(row, "created_at_ms", "created_at");
            project.updatedAtEpochMillis =
                    table.getMillis(row, "updated_at_ms", "updated_at");
            if (project.updatedAtEpochMillis <= 0L) {
                project.updatedAtEpochMillis = project.createdAtEpochMillis;
            }
            out.add(project);
        }
        return out;
    }

    // ==================== 对话（扁平的：一行一条消息） ====================

    /**
     * 把 {@code conversations.csv} 还原成对话树。
     *
     * <p>用 {@link LinkedHashMap} 而不是 HashMap：结果顺序要稳（同一个文件导两次
     * 得到同样的顺序），也让测试能断言顺序。再按 {@code chat_id} 分组之后，
     * <b>组内按时间升序排</b>——消息顺序就是语义，倒过来会得到一段胡话。
     */
    private static List<ImportBundle.Conversation> conversations(
            @Nullable CsvReader.Table table, @Nullable CsvReader.Table memoryTable,
            ExportJsonParser.Problems problems) {
        List<ImportBundle.Conversation> out = new ArrayList<>();
        if (table == null) {
            return out;
        }
        Map<String, ChatEntity> chats = new LinkedHashMap<>();
        Map<String, List<MessageEntity>> messages = new LinkedHashMap<>();
        Map<String, Map<String, MemoryEntity>> memories = new LinkedHashMap<>();

        for (List<String> row : table.data()) {
            String chatId = table.getOrEmpty(row, "chat_id");
            String messageId = table.getOrEmpty(row, "message_id");
            if (chatId.isEmpty() || messageId.isEmpty()) {
                problems.add("A row in conversations.csv had no chat_id or message_id and was skipped.");
                continue;
            }
            ChatEntity chat = chats.get(chatId);
            if (chat == null) {
                chat = new ChatEntity();
                chat.id = chatId;
                chat.title = table.getOrEmpty(row, "chat_title");
                chat.projectId = table.getOrEmpty(row, "project_id");
                chats.put(chatId, chat);
                messages.put(chatId, new ArrayList<MessageEntity>());
                memories.put(chatId, new LinkedHashMap<String, MemoryEntity>());
            }

            MessageEntity message = new MessageEntity();
            message.id = messageId;
            message.chatId = chatId;
            message.role = ImportValues.role(table.get(row, "role"));
            message.text = table.getOrEmpty(row, "text");
            message.providerId = table.get(row, "provider_id");
            message.modelId = table.get(row, "model_id");
            message.route = table.get(row, "route");
            // 空单元格 = 当时没拿到用量 = 0（库里的定义就是 0 表示"还不知道"）。
            message.tokensIn = table.getLong(row, "tokens_in", 0L);
            message.tokensOut = table.getLong(row, "tokens_out", 0L);
            message.createdAtEpochMillis = table.getMillis(row, "created_at_ms", "created_at");
            message.attachmentsJson = flatAttachments(table.getLong(row, "attachment_count", 0L),
                    table.getOrEmpty(row, "attachment_files"));
            messages.get(chatId).add(message);

            long at = message.createdAtEpochMillis;
            if (at > chat.createdAtEpochMillis) {
                chat.updatedAtEpochMillis = Math.max(chat.updatedAtEpochMillis, at);
            }
            if (chat.createdAtEpochMillis == 0L || (at > 0L && at < chat.createdAtEpochMillis)) {
                chat.createdAtEpochMillis = at;
            }
        }

        memories(memoryTable, chats, memories, problems);

        for (Map.Entry<String, ChatEntity> entry : chats.entrySet()) {
            String chatId = entry.getKey();
            ChatEntity chat = entry.getValue();
            List<MessageEntity> rows = messages.get(chatId);
            rows.sort((a, b) -> Long.compare(a.createdAtEpochMillis, b.createdAtEpochMillis));
            if (chat.updatedAtEpochMillis <= 0L) {
                chat.updatedAtEpochMillis = chat.createdAtEpochMillis;
            }
            // 对话的最后一次模型：CSV 里没有这一列（它是对话级的，不是消息级的），
            // 所以从最后一条带模型的消息推回来——比留空有用，而且推的是事实。
            for (int i = rows.size() - 1; i >= 0; i--) {
                MessageEntity message = rows.get(i);
                if (message.modelId != null && !message.modelId.isEmpty()) {
                    chat.lastModelId = message.modelId;
                    chat.lastProviderId = message.providerId;
                    break;
                }
            }
            out.add(new ImportBundle.Conversation(chat, rows,
                    new ArrayList<>(memories.get(chatId).values())));
        }
        return out;
    }

    /**
     * CSV 里的附件列：只有个数和文件名，正文与类型都没有。
     *
     * <p>所以这里造出来的附件是"知道有这么个东西，名字是这个"——<b>类型不敢猜</b>
     * （{@code IMAGE} / {@code PDF} / {@code TEXT} 三个里猜一个就是编造），
     * 于是按最常见的 {@code TEXT} 记，正文没有就是没有。这句话也写在预览要显示的
     * {@code meta.notes} 里（导出那边就是这么说的），用户看得到。
     */
    @Nullable
    private static String flatAttachments(long count, String names) {
        if (count <= 0L && (names == null || names.isEmpty())) {
            return null;
        }
        List<CanonicalMessage.Attachment> attachments = new ArrayList<>();
        if (names != null && !names.isEmpty()) {
            for (String name : names.split(";")) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                attachments.add(new CanonicalMessage.Attachment(
                        CanonicalMessage.Attachment.Kind.TEXT, trimmed, null, 0L, null));
            }
        }
        // 个数比名字多（有的附件本来就没文件名）：补几条只有类型的，别把数量丢掉。
        for (int i = attachments.size(); i < count; i++) {
            attachments.add(new CanonicalMessage.Attachment(
                    CanonicalMessage.Attachment.Kind.TEXT, "", null, 0L, null));
        }
        return AttachmentCodec.toJson(attachments);
    }

    private static void memories(@Nullable CsvReader.Table table, Map<String, ChatEntity> chats,
                                 Map<String, Map<String, MemoryEntity>> memories,
                                 ExportJsonParser.Problems problems) {
        if (table == null) {
            return;
        }
        for (List<String> row : table.data()) {
            String chatId = table.getOrEmpty(row, "chat_id");
            String memoryId = table.getOrEmpty(row, "memory_id");
            if (chatId.isEmpty() || memoryId.isEmpty()) {
                problems.add("A row in memory.csv had no chat_id or memory_id and was skipped.");
                continue;
            }
            Map<String, MemoryEntity> perChat = memories.get(chatId);
            if (perChat == null) {
                // 摘要指向一条这张文件里没有的对话：**不留孤行**（那正是"删对话漏删摘要"
                // 那个 bug 的形状），计一条 problem 报出来。
                problems.add("A summary in memory.csv points at a conversation that is not in this file.");
                continue;
            }
            if (perChat.containsKey(memoryId)) {
                continue;   // 一行就是一条摘要；重复出现取第一条
            }
            MemoryEntity memory = new MemoryEntity();
            memory.id = memoryId;
            memory.chatId = chatId;
            memory.fromMessageId = table.getOrEmpty(row, "from_message_id");
            memory.toMessageId = table.getOrEmpty(row, "to_message_id");
            memory.summary = table.getOrEmpty(row, "summary");
            memory.madeByProvider = table.getOrEmpty(row, "made_by_provider");
            memory.madeByModel = table.getOrEmpty(row, "made_by_model");
            memory.tokensIn = table.getLong(row, "tokens_in", 0L);
            memory.tokensOut = table.getLong(row, "tokens_out", 0L);
            memory.editedByUser = "true".equalsIgnoreCase(table.getOrEmpty(row, "edited_by_user"));
            memory.createdAtEpochMillis = table.getMillis(row, "created_at_ms", "created_at");
            perChat.put(memoryId, memory);
        }
    }

    // ==================== 用量 ====================

    private static List<UsageCall> usage(@Nullable CsvReader.Table table) {
        List<UsageCall> out = new ArrayList<>();
        if (table == null) {
            return out;
        }
        for (List<String> row : table.data()) {
            UsageCall call = new UsageCall();
            call.id = table.getOrEmpty(row, "id");
            call.provider = provider(table.get(row, "provider"));
            call.model = table.getOrEmpty(row, "model");
            call.startedAtEpochMillis = table.getMillis(row, "started_at_ms", "started_at");
            call.input = table.getLong(row, "input_tokens", 0L);
            call.cacheRead = table.getLong(row, "cache_read_tokens", 0L);
            call.cacheWrite = table.getLong(row, "cache_write_tokens", 0L);
            call.output = table.getLong(row, "output_tokens", 0L);
            call.source = source(table.get(row, "source"));
            call.route = table.get(row, "route");
            call.kind = table.get(row, "kind");
            call.taskId = table.get(row, "task_id");
            call.chatId = table.get(row, "chat_id");
            call.toolCalls = table.get(row, "tool_calls");
            call.reason = table.get(row, "reason");
            call.policy = table.get(row, "policy");
            call.rateVersion = table.get(row, "rate_version");
            call.costMicros = table.getNullableLong(row, "cost_micros_usd");
            Long original = table.getNullableLong(row, "original_micros");
            String currency = table.get(row, "original_currency");
            if (original != null && currency != null && !currency.isEmpty()) {
                call.nativeCostMicros = original;
                call.costCurrency = currency;
            }
            out.add(call);
        }
        return out;
    }

    @Nullable
    private static Provider provider(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Provider.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknownProvider) {
            return null;
        }
    }

    private static UsageCall.Source source(@Nullable String raw) {
        return "APP".equalsIgnoreCase(raw == null ? "" : raw.trim())
                ? UsageCall.Source.APP
                : UsageCall.Source.IMPORTED;
    }
}
