package com.mobilegroup20.modelpilot.chat;

import java.util.ArrayList;
import java.util.List;

/** Requirements follow the payload: extracted PDFs are text; retained images require vision. */
public final class TaskRequirements {
    private TaskRequirements() { }
    public static TaskKind forAttachments(List<CanonicalMessage.Attachment> attachments) {
        boolean pdf = false;
        if (attachments != null) for (CanonicalMessage.Attachment a : attachments) {
            if (a.kind == CanonicalMessage.Attachment.Kind.IMAGE) return TaskKind.IMAGE;
            if (a.kind == CanonicalMessage.Attachment.Kind.PDF
                    && (a.extractedText == null || a.extractedText.trim().isEmpty())) pdf = true;
        }
        return pdf ? TaskKind.PDF : TaskKind.TEXT;
    }
    public static TaskKind forMessages(List<CanonicalMessage> messages) {
        TaskKind result = TaskKind.TEXT;
        for (CanonicalMessage message : messages) {
            TaskKind task = forAttachments(message.attachments);
            if (task == TaskKind.IMAGE) return task;
            if (task == TaskKind.PDF) result = task;
        }
        return result;
    }
    /** A memory only replaces its inclusive range when both boundaries still exist. */
    public static List<CanonicalMessage> retained(List<CanonicalMessage> messages, List<Memory> memories) {
        boolean[] covered = new boolean[messages.size()];
        for (Memory memory : memories) {
            int from = -1, to = -1;
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).id.equals(memory.fromMessageId)) from = i;
                if (messages.get(i).id.equals(memory.toMessageId)) to = i;
            }
            if (from >= 0 && to >= from) for (int i = from; i <= to; i++) covered[i] = true;
        }
        List<CanonicalMessage> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) if (!covered[i]) result.add(messages.get(i));
        return result;
    }
}
