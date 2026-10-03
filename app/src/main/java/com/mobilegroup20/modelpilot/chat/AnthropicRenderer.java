package com.mobilegroup20.modelpilot.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 的渲染器。**和 OpenAI 系只有三处不同**（见 {@link ContextRenderer}）：
 *
 * 1. system 是**顶层字段**，不是 messages 里的一条；多条 system 要拼成一段。
 * 2. 工具调用与结果是 content 数组里的 `tool_use` / `tool_result` block，
 *    而且 tool_result 挂在 **user** 消息里（不是单独的 role）。
 * 3. 图片是 `{"type":"image","source":{"type":"base64",...}}`，不用 data URL。
 *
 * <p>这一家目前是"留着备用"：设计稿里 Claude 出现过，但接不接取决于用户有没有 key。
 * 适配器先写好、单测钉住，有 key 就能开——这比"到时候再研究"便宜得多。
 */
public final class AnthropicRenderer implements ContextRenderer {

    @Override public ProviderSpec.Adapter adapter() {
        return ProviderSpec.Adapter.ANTHROPIC;
    }

    @Override public RenderedPayload render(List<CanonicalMessage> messages, List<Memory> memories) {
        StringBuilder system = new StringBuilder();
        for (Memory memory : memories) {
            append(system, "（以下是更早对话的摘要，不是用户的原话）\n" + memory.summary);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CanonicalMessage message : messages) {
            switch (message.role) {
                case SYSTEM:
                    append(system, message.text);
                    break;
                case USER:
                    out.add(userMessage(message));
                    break;
                case ASSISTANT:
                    out.add(assistantMessage(message));
                    break;
                case TOOL:
                    out.add(toolResult(message));
                    break;
                default:
                    break;
            }
        }
        return new RenderedPayload(out, system.length() == 0 ? null : system.toString());
    }

    private static void append(StringBuilder target, String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        if (target.length() > 0) {
            target.append("\n\n");
        }
        target.append(line);
    }

    private static Map<String, Object> userMessage(CanonicalMessage message) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (CanonicalMessage.Attachment attachment : message.attachments) {
            if (attachment.kind == CanonicalMessage.Attachment.Kind.IMAGE) {
                // Anthropic 要的是 base64 + media_type；`uri` 里存的是 data URL，
                // 这里把头部拆出来（不读文件，仍然是纯函数）。
                String uri = attachment.uri == null ? "" : attachment.uri;
                String media = "image/png";
                String data = uri;
                int marker = uri.indexOf(";base64,");
                if (uri.startsWith("data:") && marker > 5) {
                    media = uri.substring(5, marker);
                    data = uri.substring(marker + 8);
                }
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("type", "base64");
                source.put("media_type", media);
                source.put("data", data);
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "image");
                part.put("source", source);
                parts.add(part);
            } else if (attachment.extractedText != null) {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "text");
                part.put("text", "【附件 " + attachment.fileName + "】\n" + attachment.extractedText);
                parts.add(part);
            }
        }
        if (!message.text.isEmpty()) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "text");
            part.put("text", message.text);
            parts.add(part);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", "user");
        out.put("content", parts.isEmpty() ? "" : parts);
        return out;
    }

    private static Map<String, Object> assistantMessage(CanonicalMessage message) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (!message.text.isEmpty()) {
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("type", "text");
            text.put("text", message.text);
            parts.add(text);
        }
        for (CanonicalMessage.ToolCall call : message.toolCalls) {
            Map<String, Object> use = new LinkedHashMap<>();
            use.put("type", "tool_use");
            use.put("id", call.id);
            use.put("name", call.name);
            use.put("input", call.argumentsJson);
            parts.add(use);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", "assistant");
        out.put("content", parts);
        return out;
    }

    private static Map<String, Object> toolResult(CanonicalMessage message) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", message.toolCallId);
        block.put("content", message.text);
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(block);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", "user");
        out.put("content", parts);
        return out;
    }
}
