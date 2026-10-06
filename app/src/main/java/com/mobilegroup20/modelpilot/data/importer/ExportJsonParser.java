package com.mobilegroup20.modelpilot.data.importer;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobilegroup20.modelpilot.chat.AttachmentCodec;
import com.mobilegroup20.modelpilot.chat.CanonicalMessage;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 读 JSON 那一份导出（{@code modelpilot-export-*.json}）。<b>这是完整的那条路。</b>
 *
 * <p>字段名逐条对着 {@code DataExporter} 的写出代码来（那边是唯一的出处，
 * 任何一边改动都必须同时改另一边，{@code ExportJsonParserTest} 里的往返测试就是钉这件事的）。
 *
 * <h3>两条读法上的规矩</h3>
 * <ol>
 *   <li><b>先认毫秒，再看 ISO。</b>导出把时间写成两份（给人看的 {@code createdAt} 和
 *       给程序用的 {@code createdAtEpochMillis}）。毫秒是事实来源，人看的那份只在
 *       毫秒缺失时才拿来解析——这样一份被手工删过列的导出文件仍然能用。</li>
 *   <li><b>id 缺了就丢这一行，并且说出来。</b>没有 id 的行写不进库（主键非空），
 *       而"悄悄少一条消息"是用户永远发现不了的错。丢的行进 {@link ImportBundle#problems}。</li>
 * </ol>
 */
final class ExportJsonParser {

    /** 一次导入最多容忍的坏行数：超过就不再逐条记，改记一条汇总。防的是一份被改坏的文件刷屏。 */
    private static final int MAX_REPORTED_PROBLEMS = 20;

    private ExportJsonParser() {
    }

    static ImportBundle parse(byte[] bytes) throws ImportFileException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (parsed == null || !parsed.isJsonObject()) {
                throw new ImportFileException(ImportFileException.Reason.NOT_OUR_FILE,
                        "That JSON file is not a ModelPilot export.");
            }
            root = parsed.getAsJsonObject();
        } catch (ImportFileException ours) {
            throw ours;
        } catch (RuntimeException broken) {
            // Gson 的异常消息里可能带一小段原文，不带出去（见 ImportFileException 的注释）。
            throw new ImportFileException(ImportFileException.Reason.UNREADABLE,
                    "That JSON file could not be read (it looks incomplete or edited).");
        }

        Problems problems = new Problems();
        List<ProjectEntity> projects = projects(array(root, "projects"), problems);
        List<ImportBundle.Conversation> conversations =
                conversations(array(root, "conversations"), problems);
        List<UsageCall> usage = usage(array(root, "usage"), problems);

        boolean hasConversations = root.has("conversations") && root.get("conversations").isJsonArray();
        boolean hasUsage = root.has("usage") && root.get("usage").isJsonArray();
        ImportFacts facts = MetaParser.parse(object(root, "meta"), hasConversations, hasUsage);

        return new ImportBundle("JSON", facts, ImportBundle.Coverage.FULL,
                projects, conversations, usage, problems.list());
    }

    // ==================== 项目 ====================

    private static List<ProjectEntity> projects(@Nullable JsonArray array, Problems problems) {
        List<ProjectEntity> out = new ArrayList<>();
        for (JsonObject item : objects(array)) {
            String id = ImportValues.strOrEmpty(item, "id");
            if (id.isEmpty()) {
                problems.add("A project row had no id and was skipped.");
                continue;
            }
            ProjectEntity project = new ProjectEntity();
            project.id = id;
            project.name = ImportValues.strOrEmpty(item, "name");
            project.instructions = ImportValues.strOrEmpty(item, "instructions");
            project.colorIndex = (int) ImportValues.number(item, "colorIndex", 0L);
            project.createdAtEpochMillis =
                    ImportValues.millis(item, "createdAtEpochMillis", "createdAt");
            project.updatedAtEpochMillis =
                    ImportValues.millis(item, "updatedAtEpochMillis", "updatedAt");
            if (project.updatedAtEpochMillis <= 0L) {
                project.updatedAtEpochMillis = project.createdAtEpochMillis;
            }
            out.add(project);
        }
        return out;
    }

    // ==================== 对话 ====================

    private static List<ImportBundle.Conversation> conversations(@Nullable JsonArray array,
                                                                Problems problems) {
        List<ImportBundle.Conversation> out = new ArrayList<>();
        for (JsonObject item : objects(array)) {
            String id = ImportValues.strOrEmpty(item, "id");
            if (id.isEmpty()) {
                problems.add("A conversation row had no id and was skipped.");
                continue;
            }
            ChatEntity chat = new ChatEntity();
            chat.id = id;
            chat.title = ImportValues.strOrEmpty(item, "title");
            chat.projectId = ImportValues.strOrEmpty(item, "projectId");
            chat.lastProviderId = ImportValues.str(item, "lastProviderId");
            chat.lastModelId = ImportValues.str(item, "lastModelId");
            chat.createdAtEpochMillis =
                    ImportValues.millis(item, "createdAtEpochMillis", "createdAt");
            chat.updatedAtEpochMillis =
                    ImportValues.millis(item, "updatedAtEpochMillis", "updatedAt");
            if (chat.updatedAtEpochMillis <= 0L) {
                chat.updatedAtEpochMillis = chat.createdAtEpochMillis;
            }

            List<MessageEntity> messages = messages(array(item, "messages"), id, problems);
            List<MemoryEntity> memories = memories(array(item, "memory"), id, problems);
            out.add(new ImportBundle.Conversation(chat, messages, memories));
        }
        return out;
    }

    private static List<MessageEntity> messages(@Nullable JsonArray array, String chatId,
                                                Problems problems) {
        List<MessageEntity> out = new ArrayList<>();
        for (JsonObject item : objects(array)) {
            String id = ImportValues.strOrEmpty(item, "id");
            if (id.isEmpty()) {
                problems.add("A message in conversation " + shortId(chatId) + " had no id and was skipped.");
                continue;
            }
            MessageEntity message = new MessageEntity();
            message.id = id;
            message.chatId = chatId;
            message.role = ImportValues.role(ImportValues.str(item, "role"));
            message.text = ImportValues.strOrEmpty(item, "text");
            message.providerId = ImportValues.str(item, "providerId");
            message.modelId = ImportValues.str(item, "modelId");
            message.route = ImportValues.str(item, "route");
            message.toolCallId = ImportValues.str(item, "toolCallId");
            // 导出里 token 为 0 时**不写这个字段**（0 在库里的定义是"还不知道"），
            // 所以这里缺字段就是 0，正好对上。
            message.tokensIn = ImportValues.number(item, "tokensIn", 0L);
            message.tokensOut = ImportValues.number(item, "tokensOut", 0L);
            message.createdAtEpochMillis =
                    ImportValues.millis(item, "createdAtEpochMillis", "createdAt");
            message.attachmentsJson = attachmentsJson(array(item, "attachments"));
            JsonElement toolCalls = item == null ? null : item.get("toolCalls");
            if (toolCalls != null && !toolCalls.isJsonNull()) {
                // 原样收着：导出那边写的就是库里的那一列（能解析时是 JSON，不能时是字符串）。
                message.toolCallsJson = toolCalls.isJsonPrimitive()
                        ? toolCalls.getAsString() : toolCalls.toString();
            }
            out.add(message);
        }
        return out;
    }

    /**
     * 附件：<b>只收类型、文件名、大小、抽取文本；本机路径（{@code uri}）永远丢掉。</b>
     *
     * <p>导出里本来就没有 uri（那是它故意省的），而这里再显式丢掉一次是防"别人手工
     * 塞一个 uri 进来"——那个 uri 指向的是他手机上的文件，在我们这台设备上打不开，
     * 留着只会让附件看起来是好的、点开却什么都没有。抽取出来的正文留着：那是内容本身。
     */
    @Nullable
    private static String attachmentsJson(@Nullable JsonArray array) {
        if (array == null || array.isEmpty()) {
            return null;
        }
        List<CanonicalMessage.Attachment> attachments = new ArrayList<>();
        for (JsonObject item : objects(array)) {
            CanonicalMessage.Attachment.Kind kind;
            try {
                kind = CanonicalMessage.Attachment.Kind.valueOf(
                        ImportValues.strOrEmpty(item, "kind"));
            } catch (IllegalArgumentException unknownKind) {
                // 认不出的类型跳过这一条，而不是让整条消息读不出来（和 AttachmentCodec 同口径）。
                continue;
            }
            attachments.add(new CanonicalMessage.Attachment(
                    kind,
                    ImportValues.strOrEmpty(item, "fileName"),
                    null,   // uri 故意为 null：见方法注释
                    ImportValues.number(item, "bytes", 0L),
                    ImportValues.str(item, "text")));
        }
        return AttachmentCodec.toJson(attachments);
    }

    private static List<MemoryEntity> memories(@Nullable JsonArray array, String chatId,
                                               Problems problems) {
        List<MemoryEntity> out = new ArrayList<>();
        for (JsonObject item : objects(array)) {
            String id = ImportValues.strOrEmpty(item, "id");
            if (id.isEmpty()) {
                problems.add("A summary in conversation " + shortId(chatId) + " had no id and was skipped.");
                continue;
            }
            MemoryEntity memory = new MemoryEntity();
            memory.id = id;
            memory.chatId = chatId;
            memory.fromMessageId = ImportValues.strOrEmpty(item, "fromMessageId");
            memory.toMessageId = ImportValues.strOrEmpty(item, "toMessageId");
            memory.summary = ImportValues.strOrEmpty(item, "summary");
            memory.madeByProvider = ImportValues.strOrEmpty(item, "madeByProvider");
            memory.madeByModel = ImportValues.strOrEmpty(item, "madeByModel");
            memory.tokensIn = ImportValues.number(item, "tokensIn", 0L);
            memory.tokensOut = ImportValues.number(item, "tokensOut", 0L);
            memory.editedByUser = ImportValues.bool(item, "editedByUser", false);
            memory.createdAtEpochMillis =
                    ImportValues.millis(item, "createdAtEpochMillis", "createdAt");
            out.add(memory);
        }
        return out;
    }

    // ==================== 用量 ====================

    private static List<UsageCall> usage(@Nullable JsonArray array, Problems problems) {
        List<UsageCall> out = new ArrayList<>();
        for (JsonObject item : objects(array)) {
            UsageCall call = new UsageCall();
            call.id = ImportValues.strOrEmpty(item, "id");
            call.provider = provider(ImportValues.str(item, "provider"));
            call.model = ImportValues.strOrEmpty(item, "model");
            call.startedAtEpochMillis =
                    ImportValues.millis(item, "startedAtEpochMillis", "startedAt");
            call.input = ImportValues.number(item, "input", 0L);
            call.cacheRead = ImportValues.number(item, "cacheRead", 0L);
            call.cacheWrite = ImportValues.number(item, "cacheWrite", 0L);
            call.output = ImportValues.number(item, "output", 0L);
            call.source = source(ImportValues.str(item, "source"));
            call.route = ImportValues.str(item, "route");
            call.kind = ImportValues.str(item, "kind");
            call.taskId = ImportValues.str(item, "taskId");
            call.chatId = ImportValues.str(item, "chatId");
            call.toolCalls = ImportValues.str(item, "toolCalls");
            call.reason = ImportValues.str(item, "reason");
            call.policy = ImportValues.str(item, "policy");
            call.rateVersion = ImportValues.str(item, "rateVersion");

            JsonObject cost = object(item, "cost");
            call.costMicros = ImportValues.nullableNumber(cost, "usdMicros");
            // 原始金额与币种要成对才有意义：导出里两者一起写，这里也一起读，
            // 缺一个就当没有（只有金额不知道币种，那个数没法解释）。
            Long original = ImportValues.nullableNumber(cost, "originalMicros");
            String currency = ImportValues.str(cost, "originalCurrency");
            if (original != null && currency != null && !currency.isEmpty()) {
                call.nativeCostMicros = original;
                call.costCurrency = currency;
            }
            // 缺 id / 模型 / 时间戳的记录不在这里丢：DataImporter 那边一并通过
            // UsageCallEntity.fromModel 处理，并把条数报进 problems（只有一处判断）。
            out.add(call);
        }
        if (out.isEmpty() && array != null && !array.isEmpty()) {
            problems.add("Usage records in this file could not be read.");
        }
        return out;
    }

    /**
     * 家：认不出的写成 {@code null}（{@link UsageCallEntity#fromModel} 会因此丢掉这一条），
     * <b>不退回某一个默认家</b>——把一次 Anthropic 的调用记成 OpenAI 的，
     * 比少一条记录严重得多：它会让按家的统计悄悄错掉。
     */
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

    /** 来源：只有一个认不出的情况——{@code APP} 与 {@code IMPORTED} 之外的值一律当导入的。 */
    private static UsageCall.Source source(@Nullable String raw) {
        if ("APP".equalsIgnoreCase(raw == null ? "" : raw.trim())) {
            return UsageCall.Source.APP;
        }
        return UsageCall.Source.IMPORTED;
    }

    // ==================== 小工具 ====================

    @Nullable
    private static JsonObject object(@Nullable JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    @Nullable
    private static JsonArray array(@Nullable JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    /** 只遍历数组里真正是对象的那种元素：一份被改坏的文件里可能混进数字或字符串。 */
    private static List<JsonObject> objects(@Nullable JsonArray array) {
        List<JsonObject> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (JsonElement element : array) {
            if (element != null && element.isJsonObject()) {
                out.add(element.getAsJsonObject());
            }
        }
        return out;
    }

    /** 报错时只带 id 的前 8 位：完整 id 是一长串 uuid，念出来没有任何帮助。 */
    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    /**
     * 坏行的收集处：<b>前若干条逐条记，之后只记总数</b>。
     *
     * <p>为什么要有上限：一份被改坏的文件可能有几千行读不动，逐条记会得到几千条
     * 一模一样的提示，界面上放不下、也没人看得完。而一条都不记更糟——用户会以为
     * 数据齐了。
     */
    static final class Problems {

        private final List<String> list = new ArrayList<>();
        private int dropped;

        void add(String problem) {
            if (list.size() < MAX_REPORTED_PROBLEMS) {
                list.add(problem);
            } else {
                dropped++;
            }
        }

        List<String> list() {
            if (dropped > 0) {
                List<String> out = new ArrayList<>(list);
                out.add("…and " + dropped + " more rows could not be read.");
                return out;
            }
            return list;
        }
    }
}
