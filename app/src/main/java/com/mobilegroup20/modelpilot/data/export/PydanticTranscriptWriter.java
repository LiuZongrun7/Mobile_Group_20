package com.mobilegroup20.modelpilot.data.export;

/* Java adaptation of Pydantic AI chat_app.py::to_chat_message and GET /chat/.
 * Copyright (c) Pydantic Services Inc. 2024 to present. MIT.
 * See third_party/pydantic-ai-chat/LICENSE and docs/OPEN_SOURCE_TECHNICAL_REPORT.md.
 */
import com.google.gson.JsonObject;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Text-only interchange for one conversation, never a full-fidelity backup. */
public final class PydanticTranscriptWriter {
    private PydanticTranscriptWriter() { }
    public static ExportArtifact write(String chatId, List<MessageEntity> messages) {
        if (chatId == null || chatId.isEmpty() || messages == null || messages.isEmpty())
            throw new IllegalArgumentException("There are no saved messages to export.");
        StringBuilder out = new StringBuilder();
        Set<String> identities = new HashSet<>();
        for (MessageEntity message : messages) {
            if (message == null || !chatId.equals(message.chatId))
                throw new IllegalArgumentException("Transcript must contain exactly one conversation.");
            String role;
            if ("USER".equals(message.role)) role = "user";
            else if ("ASSISTANT".equals(message.role)) role = "model";
            else throw new IllegalArgumentException("This transcript format supports text user and assistant turns only. Use full JSON export instead.");
            if (message.createdAtEpochMillis <= 0 || message.text == null)
                throw new IllegalArgumentException("A message is missing its text or timestamp. Use full JSON export instead.");
            // Upstream uses timestamp as identity. Room only stores milliseconds, so two
            // same-role messages at the same millisecond receive distinct sub-millisecond
            // interchange timestamps. Original Room timestamps remain unchanged.
            Instant stamp = Instant.ofEpochMilli(message.createdAtEpochMillis);
            int offset = 0;
            while (!identities.add(role + ":" + stamp)) {
                if (++offset >= 1_000_000) throw new IllegalArgumentException("Too many messages share a timestamp.");
                stamp = Instant.ofEpochMilli(message.createdAtEpochMillis).plusNanos(offset);
            }
            JsonObject row = new JsonObject();
            row.addProperty("role", role); row.addProperty("timestamp", stamp.toString());
            row.addProperty("content", message.text);
            out.append(row).append('\n');
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32 * 1024 * 1024)
            throw new IllegalArgumentException("Transcript exceeds the 32 MiB import limit. Use full JSON export instead.");
        // No chat title, project instructions, keys, endpoint URLs, local attachment paths,
        // model identifiers or ledger values are copied into this deliberately small format.
        return new ExportArtifact("modelpilot-chat.ndjson", bytes);
    }
}
