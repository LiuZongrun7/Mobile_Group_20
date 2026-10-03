package com.mobilegroup20.modelpilot.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的渲染器：**六家里的五家共用它**（DeepSeek / OpenAI / GLM / Kimi / MiMo / Seed）。
 *
 * <p>差别只在 base URL 与模型清单，所以它们不需要六个类——这正是"一套适配器 + 一张
 * 配置表"能接六家的原因。哪天真遇到一家在这套格式上加了私有字段，再给它单独开一个。
 *
 * <p>三处细节：
 *
 * 1. **system 放 messages[0]**（OpenAI 系都这样）。
 * 2. **工具结果**：`role: "tool"` + `tool_call_id`，且**必须紧跟**发起调用的那条
 *    assistant 消息——顺序错了 OpenAI 会直接 400。
 * 3. **图片**：`{"type":"image_url","image_url":{"url":"data:<mime>;base64,..."}}`；
 *    这里只传 data URL 的头部（真正的字节由调用方在发送前贴进来，见 `Attachment.uri`），
 *    渲染器不读文件——**渲染必须是纯函数**。
 */
public final class OpenAiCompatibleRenderer implements ContextRenderer {

    @Override public ProviderSpec.Adapter adapter() {
        return ProviderSpec.Adapter.OPENAI_COMPATIBLE;
    }

    @Override public RenderedPayload render(List<CanonicalMessage> messages, List<Memory> memories) {
        List<Map<String, Object>> out = new ArrayList<>();

        // 记忆是"被压缩掉的那段历史"的替身：以 system 的形式放在最前面，
        // 并**明确说这是摘要**——否则模型会把摘要当成用户的原话。
        for (Memory memory : memories) {
            out.add(text("system", "（以下是更早对话的摘要，不是用户的原话）\n" + memory.summary));
        }

        for (CanonicalMessage message : messages) {
            switch (message.role) {
                case SYSTEM:
                    out.add(text("system", message.text));
                    break;
                case USER:
                    out.add(userMessage(message));
                    break;
                case ASSISTANT:
                    out.add(assistantMessage(message));
                    break;
                case TOOL:
                    Map<String, Object> tool = text("tool", message.text);
                    tool.put("tool_call_id", message.toolCallId);
                    out.add(tool);
                    break;
                default:
                    break;
            }
        }
        return new RenderedPayload(out, null);
    }

    private static Map<String, Object> text(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> userMessage(CanonicalMessage message) {
        if (message.attachments.isEmpty()) {
            return text("user", message.text);
        }
        // 有附件时 content 要变成数组（纯文本时是字符串，这是 OpenAI 的两种形状）。
        List<Map<String, Object>> parts = new ArrayList<>();
        if (!message.text.isEmpty()) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "text");
            part.put("text", message.text);
            parts.add(part);
        }
        for (CanonicalMessage.Attachment attachment : message.attachments) {
            if (attachment.kind == CanonicalMessage.Attachment.Kind.IMAGE) {
                Map<String, Object> url = new LinkedHashMap<>();
                // `uri` 里带的已经是 data URL（发送前由调用方贴好），这里只搬。
                url.put("url", attachment.uri);
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "image_url");
                part.put("image_url", url);
                parts.add(part);
            } else if (attachment.extractedText != null) {
                // PDF / 文本：**抽出来的正文进提示词**（大纲：core 是 text-assisted PDF
                // extraction）。抽不出正文的（扫描件）由上层拒绝并说明，不在这里假装。
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("type", "text");
                part.put("text", "\n\n【附件 " + attachment.fileName + "】\n" + attachment.extractedText);
                parts.add(part);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", "user");
        out.put("content", parts);
        return out;
    }

    private static Map<String, Object> assistantMessage(CanonicalMessage message) {
        Map<String, Object> out = text("assistant", message.text);
        if (!message.toolCalls.isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (CanonicalMessage.ToolCall call : message.toolCalls) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name);
                function.put("arguments", call.argumentsJson);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", call.id);
                entry.put("type", "function");
                entry.put("function", function);
                calls.add(entry);
            }
            out.put("tool_calls", calls);
        }
        return out;
    }
}
