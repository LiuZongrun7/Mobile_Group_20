package com.mobilegroup20.modelpilot.chat;

import com.mobilegroup20.modelpilot.data.remote.ProviderClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上下文压缩：把一整段历史交给一个模型，换回一段**provider 无关**的摘要。
 *
 * <p>为什么摘要必须与 provider 无关（{@link Memory} 的类注释也说了）：这条记忆接下来要
 * 改写**六份**上下文。如果摘要里带着"我是 Claude 生成的"这种痕迹，换到别家就会出现
 * "模型在跟用户聊自己没做过的事"。
 *
 * <p><b>要让模型保住什么</b>（写进指令里的几条，都是踩过或缺了就会出问题的）：
 *
 * <ol>
 *   <li><b>用户说过的约束与偏好</b>——"不要用 emoji""按 IELTS 6.5 的标准改"。
 *       丢了这些，压缩之后模型会立刻违反用户明确提过的要求。</li>
 *   <li><b>已经定下来的结论与决定</b>，以及**被否掉的方案**（不然会再提一遍）。</li>
 *   <li><b>未完成的事</b>（待办、等用户确认的点）。</li>
 *   <li><b>专有名词与代码标识符照抄**，不要翻译、不要改写——
 *       它们要么是文件名要么是 API 名，改一个字就错了。</li>
 * </ol>
 *
 * <p>指令里明确**不要求**"写得漂亮"：摘要的读者是下一个模型，不是人。
 * 追求文采会把 token 花在形容词上，而那些形容词对"接下来怎么答"毫无帮助。
 */
public final class Summarizer {

    /** 压缩指令。**它就是压缩质量的全部**，改这句话等于改产品行为。 */
    public static final String INSTRUCTION =
            "You are compacting an earlier part of a conversation so another model can continue it.\n"
            + "Write a compact summary that keeps:\n"
            + "1) every instruction, constraint or preference the user stated, in their own wording;\n"
            + "2) decisions already made, and options already rejected (so they are not proposed again);\n"
            + "3) open questions and unfinished work;\n"
            + "4) identifiers, file names, API names, numbers and units exactly as written.\n"
            + "Do not add commentary, do not greet, do not ask questions. "
            + "Prefer short bullet points over prose. "
            + "If something is uncertain, say so instead of guessing.\n"
            + "Write the summary in the language the conversation is mostly in.";

    private Summarizer() {
    }

    /**
     * 压一段历史。
     *
     * <p><b>会阻塞当前线程</b>（走的是 {@link ProviderClient#complete}，非流式那一趟）。
     * 只能在后台线程上调。
     *
     * @return 摘要正文。**空串 = 这次压缩没成功**（上游给了空回答），调用方不要拿它
     *         建记忆——一条空记忆会让模型以为"前面什么都没发生过"。
     * @throws ProviderClient.ProviderException 上游拒绝或网络失败
     */
    public static String summarize(ProviderSpec provider, ModelSpec model, String apiKey,
                                   List<CanonicalMessage> segment)
            throws ProviderClient.ProviderException {
        if (segment.isEmpty()) {
            return "";
        }
        // 走**同一套渲染器**：压缩请求和正常请求的形状差异（system 位置、附件的编码）
        // 就不必在这里再实现一遍。多写一套"压缩专用的请求体"，等于给六家各加一处会走偏的地方。
        ContextRenderer renderer = provider.adapter == ProviderSpec.Adapter.ANTHROPIC
                ? new AnthropicRenderer() : new OpenAiCompatibleRenderer();

        List<CanonicalMessage> request = new ArrayList<>();
        request.add(new CanonicalMessage("compress-system", CanonicalMessage.Role.SYSTEM,
                INSTRUCTION, null, null, null, 0));
        request.addAll(segment);
        // 指令放在**最后**（而不是最前）：各家的 system 都可能被自己的护栏覆盖，
        // 而"紧挨着要生成的位置"的那句话是模型最当真的。
        request.add(new CanonicalMessage("compress-ask", CanonicalMessage.Role.USER,
                "Summarise the conversation above into one compact block following the rules. "
                        + "Output only the summary.",
                null, null, null, 0));

        ContextRenderer.RenderedPayload payload = renderer.render(request, Collections.emptyList());
        RenderedContext context = new RenderedContext(provider.providerId, model.modelId, payload,
                ContextEngine.estimate(payload), model.contextLimit, "compress-ask", null,
                /* memoryCount= */ 0, /* messageCount= */ request.size());
        String text = ProviderClient.complete(provider, model, apiKey, context);
        return text == null ? "" : text.trim();
    }
}
