package com.mobilegroup20.modelpilot.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 规范化的一条消息——**这个对话唯一的事实来源**。
 *
 * <p>它故意长得"哪家都不像"：没有 OpenAI 的 `tool_calls` 数组形状，也没有
 * Anthropic 的 `tool_use` block。各家的形状由渲染器（{@link ContextRenderer}）
 * 生成，生成结果缓存在 {@link RenderedContext} 里，发送时直接用缓存。
 *
 * <p>为什么不直接存"某家的格式"：一次对话里用户会不断换模型（这正是本项目的卖点），
 * 只要有一家格式被当成事实来源，换到另一家就得反解析一次——那种"读回来再写出去"
 * 的地方，信息一定会悄悄丢（多模态附件、工具调用的 id、系统提示的位置最容易掉）。
 */
public final class CanonicalMessage {

    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    /** 规范化 id：客户端生成、上报到服务端、压缩时用它标边界。 */
    public final String id;
    public final Role role;
    public final String text;
    public final List<Attachment> attachments;
    public final List<ToolCall> toolCalls;
    /** role=TOOL 时：这是哪一次调用的结果。 */
    public final String toolCallId;
    public final long createdAt;
    /** 实际发出后看到用量时回填；没发过（或还没回填）是 0。 */
    public long tokensIn;
    public long tokensOut;
    /** 这条消息之前有内容被丢掉时，写明"从哪一条开始不在上下文里"（不许静默截断）。 */
    public final String truncatedFromId;

    public CanonicalMessage(String id, Role role, String text, List<Attachment> attachments,
                            List<ToolCall> toolCalls, String toolCallId, long createdAt) {
        this(id, role, text, attachments, toolCalls, toolCallId, createdAt, null);
    }

    public CanonicalMessage(String id, Role role, String text, List<Attachment> attachments,
                            List<ToolCall> toolCalls, String toolCallId, long createdAt,
                            String truncatedFromId) {
        this.id = id;
        this.role = role;
        this.text = text == null ? "" : text;
        this.attachments = attachments == null ? Collections.<Attachment>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(attachments));
        this.toolCalls = toolCalls == null ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(toolCalls));
        this.toolCallId = toolCallId;
        this.createdAt = createdAt;
        this.truncatedFromId = truncatedFromId;
    }

    public static CanonicalMessage user(String id, String text, long at) {
        return new CanonicalMessage(id, Role.USER, text, null, null, null, at);
    }

    public static CanonicalMessage assistant(String id, String text, long at) {
        return new CanonicalMessage(id, Role.ASSISTANT, text, null, null, null, at);
    }

    /** 附件：现在只有"要读的文件/图片"，内容放本机文件系统，这里只记引用。 */
    public static final class Attachment {
        public enum Kind { IMAGE, PDF, TEXT }
        public final Kind kind;
        public final String fileName;
        /** 本机路径或我们服务端的 url；**不上传第三方**。 */
        public final String uri;
        public final long bytes;
        /** 需要时附上的文本（例如 PDF 抽出来的正文）；不附就是 null。 */
        public final String extractedText;

        public Attachment(Kind kind, String fileName, String uri, long bytes, String extractedText) {
            this.kind = kind;
            this.fileName = fileName;
            this.uri = uri;
            this.bytes = bytes;
            this.extractedText = extractedText;
        }
    }

    /** 模型要求调用某个工具。 */
    public static final class ToolCall {
        public final String id;
        public final String name;
        public final String argumentsJson;

        public ToolCall(String id, String name, String argumentsJson) {
            this.id = id;
            this.name = name;
            this.argumentsJson = argumentsJson;
        }
    }
}
