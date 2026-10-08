package com.mobilegroup20.modelpilot.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 上下文引擎的测试：**这套设计的每一条承诺各有一条用例**。
 *
 * 承诺是四句：① 每个 provider 一份"直接能发"的上下文；
 * ② 换模型不需要转换、也不需要重压；③ 木桶效应——最短那块板决定何时压，
 * 而且**只压一次**；④ 压不成的时候必须说清楚丢了什么。
 */
public class ContextEngineTest {

    private static final long T0 = 1_790_000_000_000L;

    private static ProviderRegistry registry() {
        return ProviderRegistry.defaults();
    }

    private static ContextEngine engine(String... enabled) {
        return new ContextEngine(registry(), "chat-1", Arrays.asList(enabled));
    }

    private static void conversation(ContextEngine engine, int rounds) {
        for (int i = 0; i < rounds; i++) {
            engine.append(CanonicalMessage.user("u" + i, "问题 " + i, T0 + i * 1000));
            engine.append(CanonicalMessage.assistant("a" + i, "回答 " + i, T0 + i * 1000 + 500));
        }
    }

    // ---- ① 每个 provider 一份可直接发送的上下文 ---------------------------

    @Test public void every_provider_gets_its_own_sendable_payload() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK, ProviderRegistry.ANTHROPIC);
        engine.append(CanonicalMessage.user("u1", "你好", T0));

        Map<String, Object> openai = engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat")
                .payload.messages.get(0);
        Map<String, Object> claude = engine.render(ProviderRegistry.ANTHROPIC, "claude-sonnet-4-6")
                .payload.messages.get(0);

        // OpenAI 系：role + content（字符串）
        assertEquals("user", openai.get("role"));
        assertEquals("你好", openai.get("content"));
        // Anthropic：content 是 block 数组
        assertTrue(claude.get("content") instanceof List);
        assertEquals("text", ((Map<?, ?>) ((List<?>) claude.get("content")).get(0)).get("type"));
    }

    @Test public void system_goes_to_the_top_level_field_for_anthropic_only() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK, ProviderRegistry.ANTHROPIC);
        engine.append(new CanonicalMessage("s1", CanonicalMessage.Role.SYSTEM, "你是助手",
                null, null, null, T0));
        engine.append(CanonicalMessage.user("u1", "hi", T0 + 1));

        ContextRenderer.RenderedPayload openai =
                engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat").payload;
        ContextRenderer.RenderedPayload claude =
                engine.render(ProviderRegistry.ANTHROPIC, "claude-sonnet-4-6").payload;

        assertNull("OpenAI 系没有顶层 system", openai.system);
        assertEquals("system", openai.messages.get(0).get("role"));
        assertEquals("你是助手", claude.system);
        // Anthropic 的 messages 里**不能**再有 system 角色
        for (Map<String, Object> message : claude.messages) {
            assertFalse("system".equals(message.get("role")));
        }
    }

    @Test public void a_tool_result_sits_in_a_user_message_for_anthropic() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK, ProviderRegistry.ANTHROPIC);
        engine.append(new CanonicalMessage("a1", CanonicalMessage.Role.ASSISTANT, "",
                null, Collections.singletonList(new CanonicalMessage.ToolCall("c1", "getForumHighlights", "{}")),
                null, T0));
        engine.append(new CanonicalMessage("t1", CanonicalMessage.Role.TOOL, "{\"posts\":[]}",
                null, null, "c1", T0 + 1));

        ContextRenderer.RenderedPayload openai =
                engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat").payload;
        ContextRenderer.RenderedPayload claude =
                engine.render(ProviderRegistry.ANTHROPIC, "claude-sonnet-4-6").payload;

        // OpenAI：单独一条 role=tool，且带 tool_call_id
        assertEquals("tool", openai.messages.get(1).get("role"));
        assertEquals("c1", openai.messages.get(1).get("tool_call_id"));
        // Anthropic：挂在 user 消息里的 tool_result block
        Map<?, ?> result = (Map<?, ?>) ((List<?>) claude.messages.get(1).get("content")).get(0);
        assertEquals("tool_result", result.get("type"));
        assertEquals("c1", result.get("tool_use_id"));
    }

    // ---- ② 换模型：不转换、不重压 ----------------------------------------

    @Test public void switching_models_reuses_the_canonical_history_and_only_renders() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK, ProviderRegistry.ANTHROPIC);
        conversation(engine, 3);

        RenderedContext a = engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat");
        RenderedContext b = engine.render(ProviderRegistry.ANTHROPIC, "claude-sonnet-4-6");
        RenderedContext again = engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat");

        assertEquals(6, engine.messages().size());          // 历史没被转换掉
        assertTrue(engine.memories().isEmpty());            // 切模型不触发压缩
        assertTrue(a.payload.messages.size() == b.payload.messages.size());
        // 同一个 provider 第二次拿的是缓存（同一对象）
        assertSame(a, again);
    }

    private static void assertSame(Object expected, Object actual) {
        assertTrue("应当命中缓存（同一个对象）", expected == actual);
    }

    @Test public void append_invalidates_every_providers_cache() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK, ProviderRegistry.ANTHROPIC);
        engine.append(CanonicalMessage.user("u1", "hi", T0));
        RenderedContext before = engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat");
        engine.append(CanonicalMessage.assistant("a1", "hello", T0 + 1));
        RenderedContext after = engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat");

        assertFalse("新消息进来了，缓存必须失效", before == after);
        assertEquals("a1", after.renderedThroughMessageId);
        assertEquals(2, after.payload.messages.size());
    }

    @Test public void editing_a_memory_invalidates_the_cache() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK);
        conversation(engine, 6);
        engine.applyCompression("u0", "a3", "前四轮摘要", ProviderRegistry.DEEPSEEK,
                "deepseek-chat", 100, 10, T0);
        engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat");

        engine.editMemory(engine.memories().get(0).id, "用户改过的摘要");

        RenderedContext after = engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat");
        String first = String.valueOf(after.payload.messages.get(0).get("content"));
        assertTrue("改完必须重渲染，否则发出去的还是旧摘要：" + first,
                first.contains("用户改过的摘要"));
    }

    // ---- ③ 木桶效应与"只压一次" ------------------------------------------

    @Test public void the_threshold_follows_the_smallest_context_among_enabled_models() {
        // 只配 DeepSeek（128K）与配上 MiMo（也 128K）阈值相同；
        // 配上 Seed（256K）不该把阈值抬高——木桶看的是最短那块板。
        int onlySmall = engine(ProviderRegistry.DEEPSEEK).compressionThreshold();
        int withBigger = engine(ProviderRegistry.DEEPSEEK, ProviderRegistry.SEED)
                .compressionThreshold();
        assertEquals("更长上下文的模型不该抬高阈值", onlySmall, withBigger);

        int smallest = engine(ProviderRegistry.DEEPSEEK).usableTokens(128_000);
        assertEquals(smallest - ContextEngine.COMPRESS_HEADROOM, onlySmall);
    }

    @Test public void compression_triggers_before_the_smallest_model_overflows() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK);
        assertFalse(engine.needsCompression());
        // 塞到刚好超过阈值
        StringBuilder filler = new StringBuilder();
        while (filler.length() < 400_000) {
            filler.append("这是一段用来把上下文顶到阈值的长文本。");
        }
        engine.append(CanonicalMessage.user("big", filler.toString(), T0));
        assertTrue(engine.needsCompression());
    }

    @Test public void the_segment_to_compress_never_splits_a_question_from_its_answer() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK);
        conversation(engine, 8);
        List<CanonicalMessage> segment = engine.compressionSegment();

        assertFalse(segment.isEmpty());
        // 边界落在 user 之前
        assertEquals(CanonicalMessage.Role.USER, segment.get(0).role);
        assertEquals(CanonicalMessage.Role.ASSISTANT, segment.get(segment.size() - 1).role);
        assertTrue("至少要留下最后几轮原文", segment.size() < engine.messages().size());
    }

    @Test public void one_compression_rewrites_every_providers_context() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK, ProviderRegistry.ANTHROPIC,
                ProviderRegistry.OPENAI);
        conversation(engine, 8);
        List<CanonicalMessage> segment = engine.compressionSegment();
        engine.applyCompression(segment.get(0).id, segment.get(segment.size() - 1).id,
                "前几轮：用户在问用量怎么算，助手解释了四桶口径。",
                ProviderRegistry.DEEPSEEK, "deepseek-chat", 5_000, 300, T0);

        // 三家都拿到了同一条记忆——**只压了一次**
        for (String[] pair : new String[][]{{ProviderRegistry.DEEPSEEK, "deepseek-chat"},
                {ProviderRegistry.ANTHROPIC, "claude-sonnet-4-6"},
                {ProviderRegistry.OPENAI, "gpt-5.5"}}) {
            RenderedContext rendered = engine.render(pair[0], pair[1]);
            // Anthropic 的记忆在**顶层 system** 字段里，其余的在家里的 messages[0]。
            String where = rendered.payload.system != null
                    ? rendered.payload.system
                    : String.valueOf(rendered.payload.messages.get(0).get("content"));
            assertTrue(pair[0] + " 没拿到记忆", where.contains("四桶口径"));
            assertTrue(pair[0] + " 没说明这是摘要", where.contains("摘要"));
        }
        // 历史本身一条没删（用户还能翻回去看）
        assertEquals(16, engine.messages().size());
    }

    @Test public void dropping_a_memory_puts_that_history_back_into_context() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK);
        conversation(engine, 8);
        engine.applyCompression("u0", "a3", "摘要", ProviderRegistry.DEEPSEEK, "deepseek-chat", 1, 1, T0);
        int withMemory = engine.currentTokens();
        engine.dropMemory(engine.memories().get(0).id);
        assertTrue("删掉记忆后原始历史重新进上下文，token 应该变多",
                engine.currentTokens() > withMemory);
    }

    // ---- ④ 压不成就老实说 -------------------------------------------------

    @Test public void truncation_is_recorded_and_never_silent() {
        ContextEngine engine = engine(ProviderRegistry.SEED);   // 256K，装得下的阈值高
        StringBuilder filler = new StringBuilder();
        while (filler.length() < 900_000) {
            filler.append("很长的历史段落，用来把上下文撑爆。");
        }
        conversation(engine, 2);
        engine.append(CanonicalMessage.user("big", filler.toString(), T0 + 10));
        engine.append(CanonicalMessage.assistant("big-a", "收到", T0 + 11));

        RenderedContext full = engine.render(ProviderRegistry.SEED, "doubao-seed-1.6");
        assertFalse("这一份本身就已经超了", full.fits());

        RenderedContext fitted = engine.renderFitting(ProviderRegistry.SEED, "doubao-seed-1.6", true);
        assertNotNull("必须留下'从哪一条开始不在上下文里'", fitted.truncatedFromId);
        assertTrue("截断后要装得下", fitted.fits());
        assertTrue("截断后消息变少", fitted.payload.messages.size() < full.payload.messages.size());
    }

    @Test public void nothing_to_compress_is_reported_as_empty_not_faked() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK);
        conversation(engine, 2);          // 只有两轮，属于"要留的最近几轮"
        assertTrue("没有可压的段就该返回空，让调用方老实说装不下",
                engine.compressionSegment().isEmpty());
    }

    // ---- 附件的渲染 ------------------------------------------------------

    @Test public void a_pdf_attachment_goes_in_as_extracted_text() {
        ContextEngine engine = engine(ProviderRegistry.DEEPSEEK);
        engine.append(new CanonicalMessage("u1", CanonicalMessage.Role.USER, "总结一下",
                Collections.singletonList(new CanonicalMessage.Attachment(
                        CanonicalMessage.Attachment.Kind.PDF, "brief.pdf", "file:///tmp/brief.pdf",
                        1234, "这是 PDF 的正文")),
                null, null, T0));

        List<?> content = (List<?>) engine.render(ProviderRegistry.DEEPSEEK, "deepseek-chat")
                .payload.messages.get(0).get("content");
        String joined = String.valueOf(content);
        assertTrue("PDF 正文要进提示词：" + joined, joined.contains("这是 PDF 的正文"));
        assertTrue("要标出它来自哪个文件：" + joined, joined.contains("brief.pdf"));
    }

    // ---- Auto（第一版：能力满足 + 最便宜）--------------------------------

    @Test public void auto_picks_the_cheapest_configured_model_that_can_do_the_task() {
        AutoRouter router = new AutoRouter(registry());
        AutoRouter.Decision decision = router.choose(
                Arrays.asList(ProviderRegistry.DEEPSEEK, ProviderRegistry.OPENAI), TaskKind.TEXT);

        assertEquals(ProviderRegistry.DEEPSEEK, decision.providerId);
        assertTrue("理由要能被人读懂：" + decision.reason, decision.reason.contains("估算成本排序"));
        assertTrue(decision.reason.contains("文本任务"));
    }

    @Test public void auto_excludes_models_that_cannot_do_the_task_instead_of_failing() {
        AutoRouter router = new AutoRouter(registry());
        // 图片任务：DeepSeek 不支持 vision，只有 OpenAI 能满足
        AutoRouter.Decision decision = router.choose(
                Arrays.asList(ProviderRegistry.DEEPSEEK, ProviderRegistry.OPENAI), TaskKind.IMAGE);

        assertEquals(ProviderRegistry.OPENAI, decision.providerId);
        assertTrue("要说清为什么排除了它：" + decision.excluded,
                decision.excluded.toString().contains("不支持图片"));
    }

    @Test public void auto_says_what_to_do_when_nothing_is_configured() {
        AutoRouter.Decision decision = new AutoRouter(registry())
                .choose(Collections.<String>emptyList(), TaskKind.TEXT);
        assertFalse(decision.available());
        assertTrue("空候选要给下一步动作：" + decision.reason, decision.reason.contains("检查密钥"));
    }
}
