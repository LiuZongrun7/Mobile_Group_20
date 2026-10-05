package com.mobilegroup20.modelpilot.data.export;

import androidx.annotation.Nullable;

import com.google.gson.GsonBuilder;
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
import com.mobilegroup20.modelpilot.contract.model.TokenBundle;
import com.mobilegroup20.modelpilot.data.FxRate;
import com.mobilegroup20.modelpilot.data.Money;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把本机库里的对话与用量写成文件（大纲 §4 的 export，设计稿第 28 张）。
 *
 * <p>这个类<b>只做纯计算</b>：数据从 {@link ExportSource} 进来，字节从 {@code export(...)}
 * 出去。不碰 Android、不碰存储、不知道文件最后被存到哪。所以它的每条规矩都能用 JUnit
 * 钉住，而界面那一层只负责「选什么」和「存到哪」。
 *
 * <h3>五条规矩，都是「宁可少写，不可编造」</h3>
 * <ol>
 *   <li><b>不知道就留空。</b>金额没算出来、路由理由没有、导入记录没有 kind——导出的
 *       那一格是空的，不是 0、不是 "unknown"、更不是 "MANUAL"。这条和界面上的
 *       「未知不显示成 0」是同一条规矩，只是换了个出口。</li>
 *   <li><b>不带密钥、也不带请求地址。</b>key 存在 Keystore 里（{@code data/ProviderKeys}），
 *       本来就不在这个库里；而用户自己填的请求地址<b>故意不导出</b>——地址常常长这样
 *       {@code https://relay.example/v1?key=sk-...}（把凭据塞在查询串里是常见做法），
 *       导出里带一行这样的 URL 就等于把 key 抄了一份出去。设计稿对这条的要求原话是
 *       「实际密钥不在日志或导出中暴露」（DESIGN.md §5）。</li>
 *   <li><b>附件的本机路径不带出去。</b>附件在库里存的是 {@code content://} uri，
 *       那是本机的、换台设备就没用的东西，而且它会暴露文件来自哪个云盘/应用。
 *       导出保留类型、文件名、大小和抽取出来的文本，路径丢掉。</li>
 *   <li><b>金额用账本原值。</b>账本里记的是微单位整数（美元，1e-6），导出就是那个整数，
 *       不在这里重新估值、也不按别的汇率重算；要给人看的十进制那几列由
 *       {@link Money} 现算，而且人民币那一列只在用户<b>自己填过汇率</b>时才出现——
 *       没有汇率就不换算（见 {@link Money} 的类注释）。</li>
 *   <li><b>范围是用户选的，就只读那一半。</b>只选用量时不去读聊天表：少一次全表读，
 *       也少一次「本来不该被读出来的东西被读进内存」的机会。</li>
 * </ol>
 */
public final class DataExporter {

    /** 文件名前缀。改成别的会让「导出」和「清理旧导出」两处对不上，所以只在这里写一次。 */
    public static final String STEM = "modelpilot-export";

    private static final String APP_NAME = "ModelPilot";

    private final ExportSource source;
    private final String uid;
    private final String appVersion;
    /** 界面上当前显示的币种（见 {@code Currency.code}）。 */
    private final String displayCurrency;
    /** 用户填的汇率；没填就是 {@link FxRate#UNSET}。 */
    private final FxRate fxRate;

    public DataExporter(ExportSource source, String uid, String appVersion,
                        String displayCurrency, FxRate fxRate) {
        this.source = source;
        this.uid = uid;
        this.appVersion = appVersion;
        this.displayCurrency = displayCurrency;
        this.fxRate = fxRate == null ? FxRate.UNSET : fxRate;
    }

    /**
     * 导一次。
     *
     * <p><b>同步执行</b>，要读几张表，调用方必须放到后台线程上（见 {@link ExportSource}）。
     */
    public ExportResult export(ExportRequest request) {
        Snapshot data = read(request);
        JsonObject meta = meta(request, data);
        List<ExportArtifact> artifacts;
        if (request.format == ExportFormat.JSON) {
            artifacts = Collections.singletonList(
                    new ExportArtifact(fileName(request, "json"), jsonBytes(meta, request, data)));
        } else {
            List<ExportArtifact> tables = new ArrayList<>();
            tables.add(new ExportArtifact("meta.json",
                    prettyJson(meta).getBytes(StandardCharsets.UTF_8)));
            tables.addAll(tables(request, data));
            artifacts = Collections.singletonList(zip(request, tables));
        }
        return new ExportResult(artifacts, data.projects.size(), data.chats.size(),
                data.messageCount(), data.memoryCount(), data.calls.size());
    }

    // ==================== 读 ====================

    private Snapshot read(ExportRequest request) {
        Snapshot data = new Snapshot();
        if (request.includes(ExportScope.CONVERSATIONS)) {
            data.projects = orEmpty(source.projects());
            data.chats = orEmpty(source.chats());
            for (ChatEntity chat : data.chats) {
                data.messages.put(chat.id, orEmpty(source.messages(chat.id)));
                data.memories.put(chat.id, orEmpty(source.memories(chat.id)));
            }
        }
        if (request.includes(ExportScope.USAGE)) {
            data.calls = orEmpty(source.usageCalls(request.fromDay, request.toDay));
        }
        return data;
    }

    /** 一次导出读到的全部东西。做成一个内部对象是因为 {@code meta} 里的条数要从它来。 */
    private static final class Snapshot {
        List<ProjectEntity> projects = Collections.emptyList();
        List<ChatEntity> chats = Collections.emptyList();
        final Map<String, List<MessageEntity>> messages = new LinkedHashMap<>();
        final Map<String, List<MemoryEntity>> memories = new LinkedHashMap<>();
        List<UsageCallEntity> calls = Collections.emptyList();

        int messageCount() {
            int total = 0;
            for (List<MessageEntity> rows : messages.values()) {
                total += rows.size();
            }
            return total;
        }

        int memoryCount() {
            int total = 0;
            for (List<MemoryEntity> rows : memories.values()) {
                total += rows.size();
            }
            return total;
        }
    }

    // ==================== meta ====================

    /**
     * 文件头。**这是导出文件的自述**：什么时候导的、导了哪一半、里面有多少条、
     * 以及「哪些东西被故意省掉了」。
     *
     * <p>为什么要把「省掉了什么」写进文件：这些文件会被发到别人手里（对账、求助、
     * 交作业）。省掉的东西如果只有我们自己知道，收到文件的人会把「空白」读成「0」，
     * 或者以为我们漏导了。所以规矩跟着文件走。
     */
    private JsonObject meta(ExportRequest request, Snapshot data) {
        JsonObject meta = new JsonObject();
        meta.addProperty("app", APP_NAME);
        meta.addProperty("appVersion", appVersion);
        meta.addProperty("generatedAt", ExportTime.iso(request.generatedAtMillis));
        meta.addProperty("generatedAtEpochMillis", request.generatedAtMillis);
        meta.addProperty("timeZone", com.mobilegroup20.modelpilot.util.TimeUtils.ZONE.getId());
        meta.addProperty("account", uid);

        JsonArray scopes = new JsonArray();
        for (ExportScope scope : ExportScope.values()) {
            if (request.includes(scope)) {
                scopes.add(scope.name());
            }
        }
        meta.add("scopes", scopes);
        meta.addProperty("format", request.format.name());

        JsonObject counts = new JsonObject();
        if (request.includes(ExportScope.CONVERSATIONS)) {
            counts.addProperty("projects", data.projects.size());
            counts.addProperty("conversations", data.chats.size());
            counts.addProperty("messages", data.messageCount());
            counts.addProperty("memory", data.memoryCount());
        }
        if (request.includes(ExportScope.USAGE)) {
            counts.addProperty("usageCalls", data.calls.size());
            JsonObject range = new JsonObject();
            // 不限就如实写 null，不写 "all" 之类的字符串：拿到文件的人要能用程序判断。
            range.add("from", request.fromDay == null ? null : jsonString(request.fromDay));
            range.add("to", request.toDay == null ? null : jsonString(request.toDay));
            meta.add("usageRange", range);
            meta.add("contains", contains(data.calls));
        }
        meta.add("counts", counts);

        meta.add("display", display());
        meta.add("notes", notes(request));
        return meta;
    }

    /** 当前显示币种与汇率（带来源与填写时间——「跨币种要记录汇率来源与日期」）。 */
    private JsonObject display() {
        JsonObject display = new JsonObject();
        display.addProperty("currency", displayCurrency);
        if (fxRate.isSet()) {
            display.addProperty("cnyPerUsdMicros", fxRate.cnyPerUsdMicros);
            display.addProperty("cnyPerUsd", FxRate.format(fxRate.cnyPerUsdMicros));
            if (fxRate.source != null) {
                display.addProperty("fxSource", fxRate.source);
            }
            if (fxRate.enteredAtMillis > 0) {
                display.addProperty("fxEnteredAt", ExportTime.iso(fxRate.enteredAtMillis));
            }
        }
        return display;
    }

    /** 这次导出里出现了哪些模型来源与数据来源（设计稿：导出包含模型来源与数据来源）。 */
    private static JsonObject contains(List<UsageCallEntity> calls) {
        Set<String> providers = new TreeSet<>();
        Set<String> models = new TreeSet<>();
        Set<String> sources = new TreeSet<>();
        for (UsageCallEntity call : calls) {
            providers.add(call.provider == null ? "" : call.provider.name());
            models.add(call.model == null ? "" : call.model);
            sources.add(call.source == null ? "" : call.source.name());
        }
        providers.remove("");
        models.remove("");
        sources.remove("");
        JsonObject contains = new JsonObject();
        contains.add("providers", strings(providers));
        contains.add("models", strings(models));
        contains.add("sources", strings(sources));
        return contains;
    }

    private JsonArray notes(ExportRequest request) {
        JsonArray notes = new JsonArray();
        notes.add("导出内容不含任何 API key、令牌或请求地址；这些数据不在导出范围内。");
        notes.add("以 = + - @ 开头的单元格前面加了一个单引号，避免表格软件把它当公式执行（只影响 CSV）。");
        notes.add("金额是账本里记的原始整数（微单位，1 微 = 1e-6），带 _amount 的列是给人看的换算结果。");
        if (fxRate.isSet()) {
            notes.add("人民币金额按用户自己填写的汇率换算，来源与填写时间见 display。");
        } else {
            notes.add("没有填写汇率，所以本文件里没有人民币金额；需要合计请自行按当日汇率换算。");
        }
        if (request.includes(ExportScope.CONVERSATIONS)) {
            notes.add("附件只导出类型、文件名、大小和抽取出的文本，不含本机文件路径。");
            notes.add("消息上没有 token 数表示当时没拿到上游用量，不是 0 个 token。");
        }
        if (request.includes(ExportScope.USAGE)) {
            notes.add("导入的记录若来源没自带金额，行上就没有金额（界面的日汇总会按当日费率估算，本导出不估算）。");
            notes.add("天数按北京时间切分（" + com.mobilegroup20.modelpilot.util.TimeUtils.ZONE.getId()
                    + "），与各家按 UTC 切天的账单可能差几个小时。");
        }
        return notes;
    }

    // ==================== JSON ====================

    private byte[] jsonBytes(JsonObject meta, ExportRequest request, Snapshot data) {
        JsonObject root = new JsonObject();
        root.add("meta", meta);
        if (request.includes(ExportScope.CONVERSATIONS)) {
            root.add("projects", projectsJson(data.projects));
            root.add("conversations", conversationsJson(data));
        }
        if (request.includes(ExportScope.USAGE)) {
            root.add("usage", usageJson(data.calls));
        }
        return prettyJson(root).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 缩进输出 + 不转义 HTML 字符。
     *
     * <p>第二条是必须的：Gson 默认会把 {@code < > & = '} 这些字符转成 Unicode 转义序列
     * （形如反斜杠 u 加四个十六进制位），中文不转、但代码片段和数学公式会被转得没法读——
     * 而对话正文里这两种东西最多。
     */
    private static String prettyJson(JsonElement element) {
        return new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .serializeNulls()
                .create()
                .toJson(element);
    }

    private static JsonArray projectsJson(List<ProjectEntity> projects) {
        JsonArray array = new JsonArray();
        for (ProjectEntity project : projects) {
            JsonObject item = new JsonObject();
            item.addProperty("id", project.id);
            item.addProperty("name", project.name);
            // 项目指令是用户写的、会进每一次请求的上下文，属于「用户自己写下的内容」，
            // 和对话正文同级，所以照原样导出。
            put(item, "instructions", project.instructions);
            item.addProperty("colorIndex", project.colorIndex);
            putTime(item, "createdAt", project.createdAtEpochMillis);
            putTime(item, "updatedAt", project.updatedAtEpochMillis);
            array.add(item);
        }
        return array;
    }

    private static JsonArray conversationsJson(Snapshot data) {
        JsonArray array = new JsonArray();
        for (ChatEntity chat : data.chats) {
            JsonObject item = new JsonObject();
            item.addProperty("id", chat.id);
            item.addProperty("title", chat.title);
            put(item, "projectId", chat.projectId);
            put(item, "lastProviderId", chat.lastProviderId);
            put(item, "lastModelId", chat.lastModelId);
            putTime(item, "createdAt", chat.createdAtEpochMillis);
            putTime(item, "updatedAt", chat.updatedAtEpochMillis);

            JsonArray messages = new JsonArray();
            for (MessageEntity message : orEmpty(data.messages.get(chat.id))) {
                messages.add(messageJson(message));
            }
            item.add("messages", messages);

            JsonArray memories = new JsonArray();
            for (MemoryEntity memory : orEmpty(data.memories.get(chat.id))) {
                memories.add(memoryJson(memory));
            }
            item.add("memory", memories);
            array.add(item);
        }
        return array;
    }

    private static JsonObject messageJson(MessageEntity message) {
        JsonObject item = new JsonObject();
        item.addProperty("id", message.id);
        item.addProperty("role", message.role);
        putTime(item, "createdAt", message.createdAtEpochMillis);
        // token 为 0 按库里的定义就是「还不知道」（见 MessageEntity#tokensIn），
        // 所以不写这个字段，而不是写一个 0。
        if (message.tokensIn > 0) {
            item.addProperty("tokensIn", message.tokensIn);
        }
        if (message.tokensOut > 0) {
            item.addProperty("tokensOut", message.tokensOut);
        }
        put(item, "providerId", message.providerId);
        put(item, "modelId", message.modelId);
        put(item, "route", message.route);
        item.addProperty("text", message.text == null ? "" : message.text);

        List<CanonicalMessage.Attachment> attachments =
                AttachmentCodec.fromJson(message.attachmentsJson);
        if (!attachments.isEmpty()) {
            JsonArray array = new JsonArray();
            for (CanonicalMessage.Attachment attachment : attachments) {
                JsonObject row = new JsonObject();
                row.addProperty("kind", attachment.kind == null ? "" : attachment.kind.name());
                put(row, "fileName", attachment.fileName);
                row.addProperty("bytes", attachment.bytes);
                // **uri 故意不写**：那是本机路径，换台设备无效，还会暴露文件来自哪个应用。
                if (attachment.extractedText != null) {
                    row.addProperty("text", attachment.extractedText);
                }
                array.add(row);
            }
            item.add("attachments", array);
        }
        if (message.toolCallsJson != null && !message.toolCallsJson.trim().isEmpty()) {
            item.add("toolCalls", jsonOrString(message.toolCallsJson));
        }
        put(item, "toolCallId", message.toolCallId);
        return item;
    }

    private static JsonObject memoryJson(MemoryEntity memory) {
        JsonObject item = new JsonObject();
        item.addProperty("id", memory.id);
        putTime(item, "createdAt", memory.createdAtEpochMillis);
        put(item, "fromMessageId", memory.fromMessageId);
        put(item, "toMessageId", memory.toMessageId);
        put(item, "madeByProvider", memory.madeByProvider);
        put(item, "madeByModel", memory.madeByModel);
        if (memory.tokensIn > 0) {
            item.addProperty("tokensIn", memory.tokensIn);
        }
        if (memory.tokensOut > 0) {
            item.addProperty("tokensOut", memory.tokensOut);
        }
        // 「用户改过这条摘要没有」是有意义的：改过 = 这段文字是用户写的，不是模型生成的。
        item.addProperty("editedByUser", memory.editedByUser);
        item.addProperty("summary", memory.summary == null ? "" : memory.summary);
        return item;
    }

    private JsonArray usageJson(List<UsageCallEntity> calls) {
        JsonArray array = new JsonArray();
        for (UsageCallEntity call : calls) {
            JsonObject item = new JsonObject();
            item.addProperty("id", call.id);
            item.addProperty("day", call.day);
            putTime(item, "startedAt", call.startedAtEpochMillis);
            item.addProperty("provider", call.provider == null ? "" : call.provider.name());
            item.addProperty("model", call.model);
            item.addProperty("source", call.source == null ? "" : call.source.name());
            put(item, "route", call.route);
            put(item, "kind", call.kind);
            put(item, "taskId", call.taskId);
            put(item, "chatId", call.chatId);
            put(item, "toolCalls", call.toolCalls);
            put(item, "reason", call.reason);
            put(item, "policy", call.policy);

            item.addProperty("input", call.input);
            item.addProperty("cacheRead", call.cacheRead);
            item.addProperty("cacheWrite", call.cacheWrite);
            item.addProperty("output", call.output);
            item.addProperty("total", tokens(call).total());

            item.add("cost", costJson(call));
            put(item, "rateVersion", call.rateVersion);
            array.add(item);
        }
        return array;
    }

    /**
     * 一条记录的钱：<b>原值 + 可读值 + 来源自带的原始金额</b>，三项分开写。
     *
     * <p>合成一个「cost」对象而不是平铺三个字段，是因为这三样东西的口径不一样，
     * 平铺之后拿到文件的人很容易把「原始金额」当成「美元金额」直接加总。
     */
    private JsonObject costJson(UsageCallEntity call) {
        JsonObject cost = new JsonObject();
        if (call.costMicros != null) {
            cost.addProperty("usdMicros", call.costMicros);
            cost.addProperty("usd", Money.formatUsd(call.costMicros));
            if (fxRate.isSet()) {
                cost.addProperty("cny", Money.formatCny(call.costMicros, fxRate.cnyPerUsdMicros));
            }
        }
        if (call.nativeCostMicros != null) {
            cost.addProperty("originalMicros", call.nativeCostMicros);
            put(cost, "originalCurrency", call.costCurrency);
        }
        return cost;
    }

    // ==================== CSV ====================

    private List<ExportArtifact> tables(ExportRequest request, Snapshot data) {
        List<ExportArtifact> files = new ArrayList<>();
        if (request.includes(ExportScope.CONVERSATIONS)) {
            files.add(new ExportArtifact("projects.csv", projectsCsv(data).toBytes()));
            files.add(new ExportArtifact("conversations.csv", conversationsCsv(data).toBytes()));
            files.add(new ExportArtifact("memory.csv", memoryCsv(data).toBytes()));
        }
        if (request.includes(ExportScope.USAGE)) {
            files.add(new ExportArtifact("usage.csv", usageCsv(data).toBytes()));
        }
        return files;
    }

    private static Csv projectsCsv(Snapshot data) {
        Csv csv = new Csv("id", "name", "instructions", "color_index",
                "created_at", "created_at_ms", "updated_at", "updated_at_ms");
        for (ProjectEntity project : data.projects) {
            csv.row(project.id, project.name, project.instructions,
                    Integer.toString(project.colorIndex),
                    ExportTime.iso(project.createdAtEpochMillis),
                    Long.toString(project.createdAtEpochMillis),
                    ExportTime.iso(project.updatedAtEpochMillis),
                    Long.toString(project.updatedAtEpochMillis));
        }
        return csv;
    }

    /**
     * 消息表。<b>每一行都带上它所属对话的信息</b>，因为 CSV 只能是一张表：
     * 分开两张（对话一张、消息一张）的话，用户拿到的文件必须先自己做一次 join 才能读，
     * 而「用 Excel 打开就能看懂」正是他要 CSV 的原因。
     */
    private static Csv conversationsCsv(Snapshot data) {
        Csv csv = new Csv("chat_id", "chat_title", "project_id", "project_name",
                "message_id", "role", "created_at", "created_at_ms",
                "provider_id", "model_id", "route", "tokens_in", "tokens_out",
                "attachment_count", "attachment_files", "text");
        Map<String, String> projectNames = projectNames(data.projects);
        for (ChatEntity chat : data.chats) {
            for (MessageEntity message : orEmpty(data.messages.get(chat.id))) {
                List<CanonicalMessage.Attachment> attachments =
                        AttachmentCodec.fromJson(message.attachmentsJson);
                csv.row(chat.id, chat.title, chat.projectId,
                        projectNames.get(chat.projectId),
                        message.id, message.role,
                        ExportTime.iso(message.createdAtEpochMillis),
                        Long.toString(message.createdAtEpochMillis),
                        message.providerId, message.modelId, message.route,
                        message.tokensIn > 0 ? Long.toString(message.tokensIn) : "",
                        message.tokensOut > 0 ? Long.toString(message.tokensOut) : "",
                        Integer.toString(attachments.size()),
                        attachmentNames(attachments),
                        message.text);
            }
        }
        return csv;
    }

    private static Csv memoryCsv(Snapshot data) {
        Csv csv = new Csv("chat_id", "chat_title", "memory_id", "created_at", "created_at_ms",
                "from_message_id", "to_message_id", "made_by_provider", "made_by_model",
                "tokens_in", "tokens_out", "edited_by_user", "summary");
        for (ChatEntity chat : data.chats) {
            for (MemoryEntity memory : orEmpty(data.memories.get(chat.id))) {
                csv.row(chat.id, chat.title, memory.id,
                        ExportTime.iso(memory.createdAtEpochMillis),
                        Long.toString(memory.createdAtEpochMillis),
                        memory.fromMessageId, memory.toMessageId,
                        memory.madeByProvider, memory.madeByModel,
                        memory.tokensIn > 0 ? Long.toString(memory.tokensIn) : "",
                        memory.tokensOut > 0 ? Long.toString(memory.tokensOut) : "",
                        Csv.bool(memory.editedByUser),
                        memory.summary);
            }
        }
        return csv;
    }

    private Csv usageCsv(Snapshot data) {
        Csv csv = new Csv("id", "day", "started_at", "started_at_ms",
                "provider", "model", "source", "route", "kind", "task_id", "chat_id",
                "tool_calls", "reason", "policy",
                "input_tokens", "cache_read_tokens", "cache_write_tokens", "output_tokens",
                "total_tokens",
                "cost_micros_usd", "cost_amount_usd", "cost_amount_cny",
                "original_micros", "original_currency", "rate_version");
        for (UsageCallEntity call : data.calls) {
            csv.row(call.id, call.day, ExportTime.iso(call.startedAtEpochMillis),
                    Long.toString(call.startedAtEpochMillis),
                    call.provider == null ? "" : call.provider.name(),
                    call.model,
                    call.source == null ? "" : call.source.name(),
                    call.route, call.kind, call.taskId, call.chatId,
                    call.toolCalls, call.reason, call.policy,
                    Long.toString(call.input), Long.toString(call.cacheRead),
                    Long.toString(call.cacheWrite), Long.toString(call.output),
                    Long.toString(tokens(call).total()),
                    Csv.number(call.costMicros),
                    call.costMicros == null ? "" : Money.formatUsd(call.costMicros),
                    // 没有汇率就是空：不按 1:1 蒙一个数，也不写 0。
                    call.costMicros == null || !fxRate.isSet()
                            ? "" : Money.formatCny(call.costMicros, fxRate.cnyPerUsdMicros),
                    Csv.number(call.nativeCostMicros),
                    call.nativeCostMicros == null ? "" : call.costCurrency,
                    call.rateVersion);
        }
        return csv;
    }

    // ==================== zip ====================

    /**
     * 打成 zip。条目时间统一用生成时刻，这样同一次导出的两份文件不会因为压缩时的
     * 系统时间不同而字节不同（可重现，也便于我们自己做「导出结果稳定」的测试）。
     */
    private static ExportArtifact zip(ExportRequest request, List<ExportArtifact> entries) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (ExportArtifact entry : entries) {
                ZipEntry item = new ZipEntry(entry.fileName);
                item.setTime(request.generatedAtMillis);
                zip.putNextEntry(item);
                zip.write(entry.bytes);
                zip.closeEntry();
            }
        } catch (IOException impossible) {
            // ByteArrayOutputStream 不抛 IOException；真抛了就是 JVM 出了别的问题，别吞。
            throw new IllegalStateException("打包导出文件失败", impossible);
        }
        return new ExportArtifact(fileName(request, "zip"), buffer.toByteArray());
    }

    private static String fileName(ExportRequest request, String extension) {
        return STEM + "-" + ExportTime.stamp(request.generatedAtMillis) + "." + extension;
    }

    // ==================== 小工具 ====================

    /**
     * 一条记录的总 token。
     *
     * <p>用 {@link TokenBundle#total()} 而不是在这儿写四个加法：那一处已经定义了
     * 「一共多少 token」的口径（四类相加，缓存读与写是分开的两类），再写一遍迟早会和它分叉。
     */
    private static TokenBundle tokens(UsageCallEntity call) {
        return new TokenBundle(call.input, call.cacheRead, call.cacheWrite, call.output);
    }

    private static Map<String, String> projectNames(List<ProjectEntity> projects) {
        Map<String, String> names = new LinkedHashMap<>();
        for (ProjectEntity project : projects) {
            names.put(project.id, project.name);
        }
        return names;
    }

    /** 附件文件名，用分号连起来（空名跳过）。路径不进这里，见类注释第 3 条。 */
    private static String attachmentNames(List<CanonicalMessage.Attachment> attachments) {
        StringBuilder out = new StringBuilder();
        for (CanonicalMessage.Attachment attachment : attachments) {
            if (attachment.fileName == null || attachment.fileName.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(attachment.fileName);
        }
        return out.toString();
    }

    private static void put(JsonObject target, String key, @Nullable String value) {
        if (value != null && !value.isEmpty()) {
            target.addProperty(key, value);
        }
    }

    /** 时间一律写成两份：给人看的 ISO（带 +08:00）和给程序用的毫秒。 */
    private static void putTime(JsonObject target, String key, long epochMillis) {
        target.addProperty(key, ExportTime.iso(epochMillis));
        target.addProperty(key + "EpochMillis", epochMillis);
    }

    private static JsonElement jsonString(String value) {
        return new com.google.gson.JsonPrimitive(value);
    }

    /** 解析得动就当 JSON 嵌进去（工具调用是结构化的），解析不动就当字符串放进去，不丢内容。 */
    private static JsonElement jsonOrString(String raw) {
        try {
            return JsonParser.parseString(raw);
        } catch (RuntimeException notJson) {
            return jsonString(raw);
        }
    }

    private static JsonArray strings(Set<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
