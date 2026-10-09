package com.mobilegroup20.modelpilot.chat.local;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Database snapshot for the upstream-style load-history -> run -> persist workflow.
 * Structure adapted from Pydantic AI chat_app.py Database.get_messages; typed Room
 * entities, project scoping and memory are ModelPilot extensions. No Python runtime.
 * Copyright (c) Pydantic Services Inc. 2024 to present. MIT; see third_party/.
 */
public final class ChatHistorySnapshot {
    public final ChatEntity chat;
    public final ProjectEntity project;
    public final List<MessageEntity> messages;
    public final List<MemoryEntity> memories;
    public ChatHistorySnapshot(ChatEntity chat, ProjectEntity project,
                               List<MessageEntity> messages, List<MemoryEntity> memories) {
        this.chat = chat; this.project = project;
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        this.memories = Collections.unmodifiableList(new ArrayList<>(memories));
    }
}
