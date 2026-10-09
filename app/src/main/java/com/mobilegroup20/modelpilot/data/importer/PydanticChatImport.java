package com.mobilegroup20.modelpilot.data.importer;

/* Adapted from Pydantic AI chat_app.ts addMessages and chat_app.py to_chat_message.
 * Copyright (c) Pydantic Services Inc. 2024 to present. MIT licensed.
 * Source, pinned upstream files, full license and modifications:
 * third_party/pydantic-ai-chat/ and docs/PYDANTIC_REUSE.md.
 * Android adaptations and validation: ModelPilot contributors, 2026.
 */

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Imports the selected example's GET /chat/ NDJSON, not raw Pydantic model-history JSON. */
public final class PydanticChatImport {
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private PydanticChatImport() { }

    static boolean recognizes(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return false;
        try {
            String text = decode(bytes);
            for (String line : text.split("\\r?\\n")) {
                if (line.trim().isEmpty()) continue;
                JsonElement element = JsonParser.parseString(line);
                if (!element.isJsonObject()) return false;
                JsonObject object = element.getAsJsonObject();
                return object.has("role") && object.has("timestamp") && object.has("content")
                        && !object.has("meta");
            }
        } catch (RuntimeException | CharacterCodingException invalid) { return false; }
        return false;
    }

    public static ImportBundle parse(byte[] bytes) throws ImportFileException {
        if (bytes == null || bytes.length == 0) throw error(ImportFileException.Reason.EMPTY, "The transcript is empty.");
        if (bytes.length > MAX_BYTES) throw error(ImportFileException.Reason.UNREADABLE, "Transcript exceeds 32 MiB.");
        String text;
        try { text = decode(bytes); }
        catch (CharacterCodingException invalid) {
            throw error(ImportFileException.Reason.UNREADABLE, "Transcript must be UTF-8 text.");
        }
        // Port of upstream addMessages: NDJSON lines, stable message identity, latest snapshot wins.
        // Pair role with canonical timestamp so a user and model may legitimately share a timestamp.
        Map<String, MessageEntity> rows = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : text.split("\\r?\\n")) {
            lineNumber++;
            if (line.trim().isEmpty()) continue;
            try {
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                String role = string(object, "role");
                String content = string(object, "content");
                Instant timestamp = Instant.parse(string(object, "timestamp"));
                if (!"user".equals(role) && !"model".equals(role)) throw new IllegalArgumentException();
                if (timestamp.toEpochMilli() <= 0) throw new IllegalArgumentException();
                String key = role + ":" + timestamp;
                MessageEntity row = new MessageEntity();
                row.id = key; // converted into a namespaced ID after the conversation anchor is known
                row.role = "user".equals(role) ? "USER" : "ASSISTANT";
                row.text = content;
                row.createdAtEpochMillis = timestamp.toEpochMilli();
                rows.put(key, row);
            } catch (RuntimeException invalid) {
                // Fail before any writes: an omitted message can change the meaning of the whole task.
                // Never include the source content or parser exception in a UI/log error.
                throw error(ImportFileException.Reason.UNREADABLE,
                        "Invalid Pydantic chat message on line " + lineNumber + ". No data was imported.");
            }
        }
        if (rows.isEmpty()) throw error(ImportFileException.Reason.NO_CONTENT, "No chat messages found.");
        MessageEntity first = rows.values().iterator().next();
        MessageEntity anchor = first;
        for (MessageEntity row : rows.values()) if ("USER".equals(row.role)) { anchor = row; break; }
        String namespace = hash(anchor.id + "\n" + ("USER".equals(anchor.role) ? anchor.text : ""));
        ChatEntity chat = new ChatEntity();
        chat.id = "pydantic-chat:" + namespace;
        chat.title = "Pydantic · " + (anchor.text.isEmpty() ? "Imported conversation"
                : anchor.text.replace('\n', ' ').substring(0, Math.min(anchor.text.length(), 48)));
        List<MessageEntity> messages = new ArrayList<>();
        long earliest = Long.MAX_VALUE, latest = 0;
        for (MessageEntity row : rows.values()) {
            row.id = "pydantic-message:" + namespace + ":" + hash(row.id);
            row.chatId = chat.id;
            messages.add(row);
            earliest = Math.min(earliest, row.createdAtEpochMillis);
            latest = Math.max(latest, row.createdAtEpochMillis);
        }
        chat.createdAtEpochMillis = earliest;
        chat.updatedAtEpochMillis = latest;
        ImportFacts facts = new ImportFacts("Pydantic AI chat example", "", 0, "",
                Collections.singletonList("CONVERSATIONS"), Arrays.asList(
                "Imported text history from the Pydantic AI chat example's GET /chat/ NDJSON.",
                "Repeated role/timestamp snapshots are collapsed to the last version.",
                "Model identity, tokens, prices, attachments and tool calls are not provided by this format.",
                "No usage or cost records are fabricated; confirm import before continuing the chat."),
                true, false, false);
        return new ImportBundle("Pydantic NDJSON", facts, ImportBundle.Coverage.TABULAR,
                Collections.emptyList(), Collections.singletonList(new ImportBundle.Conversation(
                chat, messages, Collections.emptyList())), Collections.emptyList(), Collections.emptyList());
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException();
        }
        return value.getAsString();
    }
    private static String decode(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static ImportFileException error(ImportFileException.Reason reason, String text) {
        return new ImportFileException(reason, text);
    }
}
