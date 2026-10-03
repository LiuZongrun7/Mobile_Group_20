package com.mobilegroup20.modelpilot.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobilegroup20.modelpilot.chat.CanonicalMessage;
import com.mobilegroup20.modelpilot.chat.ContextEngine;
import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderRegistry;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.chat.RenderedContext;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 直连各家的流式客户端：**这套请求形状与错误分类的合同**。
 *
 * <p>用 MockWebServer 而不是"真的打一次 DeepSeek"：真的那一次要花用户的钱、要联网、
 * 还会因为对方改字段而随机红——而这几条要钉住的东西（`Authorization` 头、
 * `stream: true`、`data:` 分块的拼接、usage 从哪一块来、401 与 429 要分成两类）
 * 全都只取决于我们发出去什么、以及收到一串给定的字节之后怎么处理。
 *
 * <p>每个用例都用 **chunked** 发响应：真实的上游就是这么一片片推的，
 * 而 chunk 的边界可以落在任何地方（包括一行 JSON 的中间），按行解析必须扛得住。
 */
public class ProviderClientTest {

    /** 故意的"很像真 key"的串：401 那条用它验"上游回显了也不许带出来"。 */
    private static final String KEY = "sk-test-DO-NOT-LEAK-1234";
    private static final long T0 = 1_790_000_000_000L;

    private MockWebServer server;

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After public void tearDown() throws Exception {
        server.shutdown();
    }

    // ---- ① OpenAI 兼容家：请求形状 + 认证头 ---------------------------------

    @Test public void openAiCompatibleRequestCarriesModelMessagesStreamAndBearerKey() throws Exception {
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n"));
        Recorder recorder = new Recorder();

        ProviderClient.stream(spec(ProviderRegistry.DEEPSEEK), model(ProviderRegistry.DEEPSEEK, "deepseek-chat"),
                KEY, rendered(ProviderRegistry.DEEPSEEK, "deepseek-chat",
                        CanonicalMessage.user("u1", "你好", T0)), recorder);
        recorder.await();

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/chat/completions", request.getPath());
        assertEquals("Bearer " + KEY, request.getHeader("Authorization"));
        assertEquals("text/event-stream", request.getHeader("Accept"));

        JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals("deepseek-chat", body.get("model").getAsString());
        assertTrue("流式是这一版唯一的模式", body.get("stream").getAsBoolean());
        // 不带它 OpenAI 就不发 usage 块，账本只能记"未知"——所以它也是合同的一部分。
        assertTrue(body.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(1, messages.size());
        JsonObject message = messages.get(0).getAsJsonObject();
        assertEquals("user", message.get("role").getAsString());
        assertEquals("你好", message.get("content").getAsString());
        assertEquals("hi", recorder.text.toString());
        assertEquals(1, recorder.done.get());
        assertNull(recorder.failure.get());
    }

    // ---- ② 多块 data: 拼成完整文本，[DONE] 之后 onDone ------------------------

    @Test public void deltasAreConcatenatedAcrossChunksAndDoneFiresOnceAtTheMarker() throws Exception {
        // 中间那块故意是 SSE 的注释行（有的网关拿它保活），它不该被当成内容。
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                + ": keep-alive\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n"
                + "data: [DONE]\n\n"));
        Recorder recorder = new Recorder();

        ProviderClient.stream(spec(ProviderRegistry.DEEPSEEK), model(ProviderRegistry.DEEPSEEK, "deepseek-chat"),
                KEY, rendered(ProviderRegistry.DEEPSEEK, "deepseek-chat",
                        CanonicalMessage.user("u1", "打个招呼", T0)), recorder);
        recorder.await();

        assertEquals("你好，世界", recorder.text.toString());
        assertEquals(3, recorder.deltas.get());
        assertEquals("终态只能到一次", 1, recorder.done.get());
        assertNull(recorder.failure.get());
    }

    // ---- ③ usage 从最后一块里解析 -------------------------------------------

    @Test public void usageComesFromTheFinalChunkAndIsReportedBeforeDone() throws Exception {
        // 这正是 OpenAI 带 include_usage 时的真实形状：中间的块 `usage` 是显式 null，
        // 最后一块 choices 是空数组、只有 usage。少了那个 null 判断就会在中间就记一次账。
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"答\"}}],\"usage\":null}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":56}}\n\n"
                + "data: [DONE]\n\n"));
        Recorder recorder = new Recorder();

        ProviderClient.stream(spec(ProviderRegistry.DEEPSEEK), model(ProviderRegistry.DEEPSEEK, "deepseek-chat"),
                KEY, rendered(ProviderRegistry.DEEPSEEK, "deepseek-chat",
                        CanonicalMessage.user("u1", "问", T0)), recorder);
        recorder.await();

        assertEquals(1234, recorder.tokensIn.get());
        assertEquals(56, recorder.tokensOut.get());
        assertEquals(1, recorder.usageCalls.get());
        assertTrue("usage 必须在 onDone 之前报（账本要在收尾时就能记上）", recorder.usageBeforeDone);
        assertEquals(1, recorder.done.get());
    }

    // ---- ④ 401：单独一类，且异常里没有 key ----------------------------------

    @Test public void rejectedKeyIsItsOwnKindAndTheUpstreamEchoIsNotRepeated() throws Exception {
        // 上游的 401 正文故意把 key 回显出来（真事：OpenAI 系的报错会带 key 的前几位）。
        // 它可能进日志、进崩溃上报、进用户截图，所以我们**一个字符都不许往上传**。
        server.enqueue(new MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Incorrect API key provided: " + KEY + "\"}}"));
        Recorder recorder = new Recorder();

        ProviderClient.stream(spec(ProviderRegistry.DEEPSEEK), model(ProviderRegistry.DEEPSEEK, "deepseek-chat"),
                KEY, rendered(ProviderRegistry.DEEPSEEK, "deepseek-chat",
                        CanonicalMessage.user("u1", "问", T0)), recorder);
        recorder.await();

        ProviderClient.ProviderException failure = recorder.providerFailure();
        assertEquals(ProviderClient.Kind.INVALID_KEY, failure.kind);
        assertEquals(401, failure.statusCode);
        assertFalse("异常消息里不许有 key", failure.getMessage().contains(KEY));
        assertFalse("上游回显的正文也不许带出来", failure.getMessage().contains("Incorrect API key"));
        assertNull("连 cause 都不能牵着那个正文", failure.getCause());
        assertEquals("失败就不会再报完成", 0, recorder.done.get());
    }

    // ---- ⑤ Anthropic：另一套形状（system 顶层、max_tokens、/v1/messages）-----

    @Test public void anthropicRequestPutsSystemOnTopAndStreamsItsOwnEventShapes() throws Exception {
        server.enqueue(sse("event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\"\"}}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":34}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n"));
        Recorder recorder = new Recorder();

        ProviderClient.stream(spec(ProviderRegistry.ANTHROPIC), model(ProviderRegistry.ANTHROPIC, "claude-sonnet-4-6"),
                KEY, rendered(ProviderRegistry.ANTHROPIC, "claude-sonnet-4-6",
                        new CanonicalMessage("s1", CanonicalMessage.Role.SYSTEM, "你是助手",
                                null, null, null, T0),
                        CanonicalMessage.user("u1", "hi", T0 + 1)), recorder);
        recorder.await();

        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/messages", request.getPath());
        assertEquals("这一家用 x-api-key，不是 Bearer", KEY, request.getHeader("x-api-key"));
        assertNull(request.getHeader("Authorization"));
        assertNotNull("Anthropic 要求带版本头", request.getHeader("anthropic-version"));

        JsonObject body = JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
        assertEquals("system 是顶层字段", "你是助手", body.get("system").getAsString());
        assertTrue("max_tokens 在 Messages API 里是必填", body.get("max_tokens").getAsInt() > 0);
        assertTrue(body.get("stream").getAsBoolean());
        for (JsonElement message : body.getAsJsonArray("messages")) {
            assertFalse("messages 里不能再有 system 角色",
                    "system".equals(message.getAsJsonObject().get("role").getAsString()));
        }

        // 流的分块形状也一并验了：text_delta 派发，input_json_delta（工具参数）不派发，
        // 输入/输出 token 分在两条事件里、合成一次上报。
        assertEquals("你好", recorder.text.toString());
        assertEquals(12, recorder.tokensIn.get());
        assertEquals(34, recorder.tokensOut.get());
        assertEquals(1, recorder.usageCalls.get());
        assertEquals(1, recorder.done.get());
        assertNull(recorder.failure.get());
    }

    // ---- 另外两类失败：429 与其它非 200，必须和"key 无效"分开 ----------------

    @Test public void rateLimitCarriesRetryAfterAndOtherStatusesAreNotKeyErrors() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "30")
                .setBody("{\"error\":{\"message\":\"slow down\"}}"));
        Recorder limited = new Recorder();
        ProviderClient.stream(spec(ProviderRegistry.DEEPSEEK), model(ProviderRegistry.DEEPSEEK, "deepseek-chat"),
                KEY, rendered(ProviderRegistry.DEEPSEEK, "deepseek-chat",
                        CanonicalMessage.user("u1", "问", T0)), limited);
        limited.await();
        ProviderClient.ProviderException throttled = limited.providerFailure();
        assertEquals(ProviderClient.Kind.RATE_LIMITED, throttled.kind);
        assertEquals(Long.valueOf(30), throttled.retryAfterSeconds);

        // 400（模型名写错那种）仍然单独一类：它的正文**有用**，要带上，界面才能说清原因。
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"Model not found\"}}"));
        Recorder rejected = new Recorder();
        ProviderClient.stream(spec(ProviderRegistry.DEEPSEEK), model(ProviderRegistry.DEEPSEEK, "deepseek-chat"),
                KEY, rendered(ProviderRegistry.DEEPSEEK, "deepseek-chat",
                        CanonicalMessage.user("u2", "问", T0)), rejected);
        rejected.await();
        ProviderClient.ProviderException bad = rejected.providerFailure();
        assertEquals(ProviderClient.Kind.HTTP, bad.kind);
        assertEquals(400, bad.statusCode);
        assertTrue("非 401 的错误正文是排查线索", bad.getMessage().contains("Model not found"));

        // 200 但不是 SSE：反代把请求接走了。硬按 SSE 解析会变成"零个字、正常结束"。
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login</html>"));
        Recorder proxied = new Recorder();
        ProviderClient.stream(spec(ProviderRegistry.DEEPSEEK), model(ProviderRegistry.DEEPSEEK, "deepseek-chat"),
                KEY, rendered(ProviderRegistry.DEEPSEEK, "deepseek-chat",
                        CanonicalMessage.user("u3", "问", T0)), proxied);
        proxied.await();
        assertEquals(ProviderClient.Kind.HTTP, proxied.providerFailure().kind);
        assertEquals("空回答不能算成功", 0, proxied.done.get());
    }

    @Test public void unreachableHostIsANetworkFailureNotAKeyProblem() throws Exception {
        MockWebServer dead = new MockWebServer();
        dead.start();
        String baseUrl = dead.url("/").toString();
        dead.shutdown();                        // 端口关掉 = 连不上
        Recorder recorder = new Recorder();

        ProviderClient.stream(spec(ProviderRegistry.DEEPSEEK).withBaseUrl(baseUrl),
                model(ProviderRegistry.DEEPSEEK, "deepseek-chat"), KEY,
                rendered(ProviderRegistry.DEEPSEEK, "deepseek-chat",
                        CanonicalMessage.user("u1", "问", T0)), recorder);
        recorder.await();

        ProviderClient.ProviderException failure = recorder.providerFailure();
        assertEquals(ProviderClient.Kind.NETWORK, failure.kind);
        assertEquals(-1, failure.statusCode);
        assertFalse(failure.getMessage().contains(KEY));
        assertEquals(0, recorder.done.get());
    }

    // ---- 夹具 ---------------------------------------------------------------

    /**
     * 一个把回调收进字段的监听器：所有断言都在流结束之后做，
     * 而 `await()` 里那个 CountDownLatch 正好把"回调线程写、测试线程读"这件事同步好了
     * （没有它，测试线程可能读到一半状态的字段）。
     */
    private static final class Recorder implements ProviderClient.Listener {
        final StringBuilder text = new StringBuilder();
        final AtomicInteger deltas = new AtomicInteger();
        final AtomicInteger usageCalls = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicLong tokensIn = new AtomicLong(-1);
        final AtomicLong tokensOut = new AtomicLong(-1);
        final AtomicLong cacheRead = new AtomicLong(-1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch settled = new CountDownLatch(1);
        /** usage 是不是在 onDone 之前报的（账本在收尾时就要能记上）。 */
        volatile boolean usageBeforeDone;

        @Override public void onDelta(String piece) {
            text.append(piece);
            deltas.incrementAndGet();
        }

        @Override public void onUsage(com.mobilegroup20.modelpilot.contract.model.TokenBundle tokens) {
            usageCalls.incrementAndGet();
            // 收到的**是四个桶**：`input` 只装未命中缓存的部分（`CONTRACTS.md` §8），
            // 归一化由 `UsageSplitter` 做，这里如实带出来。
            tokensIn.set(tokens.input);
            tokensOut.set(tokens.output);
            cacheRead.set(tokens.cacheRead);
            usageBeforeDone = done.get() == 0;
        }

        @Override public void onDone() {
            done.incrementAndGet();
            settled.countDown();
        }

        @Override public void onError(Throwable error) {
            failure.set(error);
            settled.countDown();
        }

        void await() throws InterruptedException {
            assertTrue("流没有在 5 秒内结束", settled.await(5, TimeUnit.SECONDS));
        }

        ProviderClient.ProviderException providerFailure() {
            Throwable error = failure.get();
            assertNotNull("这次调用应该失败", error);
            assertTrue("失败必须是带 kind 的那一种，而不是任意异常：" + error,
                    error instanceof ProviderClient.ProviderException);
            return (ProviderClient.ProviderException) error;
        }
    }

    /** 服务端换成 MockWebServer 的地址（注册表里那几条是官方地址，测试不能真打过去）。 */
    private ProviderSpec spec(String providerId) {
        return ProviderRegistry.defaults().provider(providerId).withBaseUrl(server.url("/").toString());
    }

    private static ModelSpec model(String providerId, String modelId) {
        return ProviderRegistry.defaults().model(providerId, modelId);
    }

    /**
     * 拿一份**真渲染出来的**上下文，而不是手搓一个 RenderedContext。
     *
     * <p>它的构造器是包内可见的（渲染缓存的有效性判据不该让外面随便造），
     * 而这么写顺带验了那句承诺：渲染器给出的 `payload` 就是能直接发出去的形态——
     * 客户端一个字段都不用改。
     */
    private static RenderedContext rendered(String providerId, String modelId, CanonicalMessage... messages) {
        ContextEngine engine = new ContextEngine(ProviderRegistry.defaults(), "chat-test",
                Arrays.asList(providerId));
        for (CanonicalMessage message : messages) {
            engine.append(message);
        }
        return engine.render(providerId, modelId);
    }

    /** 一段 SSE 响应。chunked 是故意的：真实的流就是这个样子，chunk 边界会落在任意位置。 */
    private static MockResponse sse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body, 7);
    }
}
