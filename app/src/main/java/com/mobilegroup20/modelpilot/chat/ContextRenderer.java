package com.mobilegroup20.modelpilot.chat;

import java.util.List;
import java.util.Map;

/**
 * 把规范化消息渲染成"某家 API 能直接收的"那一份。
 *
 * <p>各家的差异其实只有三处，别在这里做别的事：
 *
 * 1. **system 放哪**：OpenAI 系放 messages 数组第一个；Anthropic 是顶层 `system` 字段。
 * 2. **工具怎么声明、调用与结果怎么表达**：`tools` + `tool_calls` 一套，
 *    Anthropic 是 `tools` + `tool_use` / `tool_result` block 另一套。
 * 3. **附件怎么编码**：OpenAI 用 `image_url` 的 data URL；Anthropic 用
 *    `source: {type: base64, media_type, data}`。
 *
 * <p><b>渲染必须是纯函数</b>（同样的消息 → 同样的字节）：它会被缓存，也会被单测
 * 逐字段比对。渲染里做时间、随机、网络之类的事，缓存就会悄悄失效。
 */
public interface ContextRenderer {

    /** 渲染出这次请求要发的 messages（以及 Anthropic 那种要单独放的 system）。 */
    RenderedPayload render(List<CanonicalMessage> messages, List<Memory> memories);

    /** 这套渲染对应哪种适配器。 */
    ProviderSpec.Adapter adapter();

    /** 渲染结果：`messages` 是数组，`system` 只有 Anthropic 用得上（否则 null）。 */
    final class RenderedPayload {
        public final List<Map<String, Object>> messages;
        public final String system;
        /** 附件的文本（PDF 抽出来的正文）已经并进消息，渲染器不再单独返回。 */
        public RenderedPayload(List<Map<String, Object>> messages, String system) {
            this.messages = messages;
            this.system = system;
        }
    }
}
