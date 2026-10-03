package com.mobilegroup20.modelpilot.data.remote;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobilegroup20.modelpilot.chat.ContextEngine;
import com.mobilegroup20.modelpilot.chat.ModelSpec;
import com.mobilegroup20.modelpilot.chat.ProviderSpec;
import com.mobilegroup20.modelpilot.chat.RenderedContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 直连各家的流式对话：把 {@link RenderedContext} 里那份"已经能直接发出去"的上下文 POST 过去，
 * 逐段把回答吐给 {@link Listener}。<b>负责人：刘宗润。</b>
 *
 * <p><b>它在整个架构里的位置</b>：服务端不参与对话（`docs/CHAT_ENGINE.md` §1）——
 * 用户的 key 在手机 Keystore 里（{@code data/ProviderKeys}），请求从这台手机直接打到
 * 各家官方 API 或用户自己的反代。**这个类不经手任何别人的 key，也不上报任何东西**；
 * 出错的异常里也不会带 key（见 {@link ProviderException}）。
 *
 * <p><b>为什么手工解析 SSE，而不是引 okhttp-sse</b>：我们要的东西只有两样——
 * `data:` 那一行的 JSON，和 OpenAI 的 `[DONE]`。EventSource 那套类型化的事件对象
 * 反而要再学一遍它的抽象；而 SSE 本身简单到"逐行读、前缀判断"就够了（见 {@link #readStream}）。
 * 少一个依赖，就少一处将来要跟着升的东西。
 *
 * <p><b>回调在 OkHttp 的调度线程上</b>，不是主线程。UI 层要自己 post 回去
 * （`view.post(...)` / `runOnUiThread(...)`）；这里不替它决定，因为把线程切换藏进
 * 数据层会让"这段代码到底跑在哪个线程"变得说不清。
 *
 * <p><b>终态保证</b>：一次调用一定以 {@code onDone()} 或 {@code onError(...)} 之一结束，
 * 不会两个都到、也不会一个都不来（连"上游在 200 的流里报错"和"读流时我们自己出了 bug"
 * 都被收成 onError，见 {@link Streaming#handle}）。唯一的例外是调用方自己
 * `call.cancel()`：那时**一个回调都不会再来**，因为取消是他自己按的。
 */
public final class ProviderClient {

    /** Anthropic 要求的版本头，不带会被拒。它是 Messages API 的稳定版本号，不是"今天的日期"。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    /** 出错时最多把上游正文带多少字符进异常：够看出"模型名写错了"，又不会把一页 HTML 拖进来。 */
    private static final int DETAIL_LIMIT = 400;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * 全 App 共用一个：OkHttp 里面有自己的连接池与线程池，每个请求 new 一个比共享更贵。
     *
     * <p><b>readTimeout 必须是 0（不超时）</b>：这是流式接口，模型"想"几十秒是常事，
     * 设一个 30 秒的读超时会在它还在生成的时候把响应掐断，而报出来的错看起来像"网络故障"，
     * 排查时完全指不到真凶。callTimeout 同理（它管的是整个调用，包含读流）。
     * 连接与写入仍然留着超时：连不上要在半分钟内知道，而不是让用户对着转圈等。
     *
     * <p><b>不自动重试</b>：这是 POST + 流式，重试可能让同一句话在用户账上扣两次钱
     * （而且第一次可能已经吐了半句）。宁可让他手动重发。
     */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();

    /** 纯静态：共享的只有那一个 OkHttpClient，没有别的实例状态。 */
    private ProviderClient() {
    }

    /**
     * 流式回调。**UI 要的"逐字显示"就是靠 {@code onDelta} 一条条拼出来的**。
     *
     * <p>线程：都在 OkHttp 的调度线程上（见类注释）。
     *
     * <p>回调里**不要往外抛异常**：它跑在 OkHttp 的调度线程上，抛出去会把那个线程打死，
     * 而且再没有人收到终态——调用方的 bug 不该表现成"界面永远停在正在输入"。
     */
    public interface Listener {

        /** 回答的下一段文本（可能是一个字，也可能是一句）。**空串不会派发。** */
        void onDelta(String text);

        /**
         * 这次调用真实花了多少 token（来自上游流里的 usage 块）。
         *
         * <p>**四个桶，不是一个总数**：账本按四桶分别乘价（读写缓存差十几倍），
         * 而且 `input` 只装未命中缓存的那段（`CONTRACTS.md` §8）。归一化在
         * {@link com.mobilegroup20.modelpilot.chat.UsageSplitter} 里做，
         * 那边认得 OpenAI 与 Anthropic 两种字段形状。
         *
         * <p>**上游没给就不会调用**：不报一个全 0 的 bundle——"不知道花了多少"和
         * "没花钱"必须能分开（`CONTRACTS.md` §4 那条底线在新账本里继续有效）。
         * 一次调用最多报一次，且在 {@code onDone()} 之前。
         */
        void onUsage(com.mobilegroup20.modelpilot.contract.model.TokenBundle tokens);

        /** 流正常结束。走到这里说明回答是完整的（已派发的 delta 就是全文）。 */
        void onDone();

        /** 失败。`failure` 一定是 {@link ProviderException}，按它的 {@code kind} 分叉提示用户。 */
        void onError(Throwable failure);
    }

    /** 出错的四类。**调用方按这个分叉，不要去解析 message**（message 是给日志看的）。 */
    public enum Kind {
        /** 401/403：key 被上游拒了 → 提示"去设置里检查这一家的 key"。 */
        INVALID_KEY,
        /** 429：限流 → `retryAfterSeconds` 有值就照它说的时间提示，否则只说"稍后重试"。 */
        RATE_LIMITED,
        /** 其它非 2xx（模型名写错、余额不足、5xx…），以及 200 的流里上游自己报的错。 */
        HTTP,
        /** 连不上、DNS 解析不了、流读到一半断了。这一类重试有意义。 */
        NETWORK
    }

    /**
     * 一次调用失败。**故意不是"一锅 onError(Exception)"**：这几类要提示给用户的话完全不同——
     * "key 无效，去设置""稍后重试""检查网络"是三个不同的动作，而 HTTP 那一类还要把
     * 上游给的原因（模型名写错了之类）显示出来。
     *
     * <p><b>异常里绝不会出现 API key。</b>两条措施：① 401/403 的响应正文我们**连读都不读**
     * （上游的 401 经常把 key 回显一段出来，比如 "Incorrect API key provided: sk-abc..."）；
     * ② 这个异常只存状态码，**不存 OkHttp 的 {@code Response}**——Response 牵着 Request，
     * Request 的头里就有 key，存了它等于给崩溃上报开了一条暗道。
     */
    public static final class ProviderException extends IOException {
        private static final long serialVersionUID = 1L;

        public final Kind kind;
        /** HTTP 状态码；-1 = 根本没收到响应（网络层就失败了）。 */
        public final int statusCode;
        /** 429 时上游给的 `Retry-After` 秒数；没给、或不是秒数形式，就是 null。 */
        public final Long retryAfterSeconds;

        private ProviderException(Kind kind, int statusCode, String message,
                                  Long retryAfterSeconds, Throwable cause) {
            super(message, cause);
            this.kind = kind;
            this.statusCode = statusCode;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        static ProviderException invalidKey(int status) {
            return new ProviderException(Kind.INVALID_KEY, status,
                    "Provider rejected the API key (HTTP " + status + ")", null, null);
        }

        static ProviderException rateLimited(int status, Long retryAfterSeconds) {
            return new ProviderException(Kind.RATE_LIMITED, status,
                    retryAfterSeconds == null
                            ? "Provider is rate limiting (HTTP " + status + ")"
                            : "Provider is rate limiting (HTTP " + status
                                    + "), retry after " + retryAfterSeconds + "s",
                    retryAfterSeconds, null);
        }

        static ProviderException http(int status, String detail) {
            return new ProviderException(Kind.HTTP, status,
                    "Provider returned HTTP " + status + suffix(detail), null, null);
        }

        /** 200 的流里上游自己报的错（Anthropic 的 `event: error`）。 */
        static ProviderException streamError(int status, String detail) {
            return new ProviderException(Kind.HTTP, status,
                    "Provider reported an error inside the stream" + suffix(detail), null, null);
        }

        static ProviderException network(String detail, IOException cause) {
            return new ProviderException(Kind.NETWORK, -1, detail, null, cause);
        }

        private static String suffix(String detail) {
            return detail == null || detail.isEmpty() ? "" : ": " + detail;
        }
    }

    /**
     * 发一次流式请求。**立刻返回**，结果走 {@code listener}。
     *
     * @param provider 这一家（含 base URL；用户在设置里改过就用他改的，见 {@code ProviderKeys.baseUrl}）
     * @param model    要用的模型（必须属于 provider）
     * @param apiKey   用户的 key。**必须是这一家的**，跨家发过去只会得到 401
     * @param context  上下文引擎渲染出来的那一份（`payload.messages` / `payload.system` 原样发送）
     * @param listener 回调；见 {@link Listener} 的线程说明
     * @return 这次调用。**要取消就用它**（用户返回上一页时 `call.cancel()`），取消之后不再有回调
     */
    public static Call stream(ProviderSpec provider, ModelSpec model, String apiKey,
                              RenderedContext context, Listener listener) {
        // 空 key 说明"这家还没配"，那就不该走到发送这一步（调用方应该先看
        // ProviderKeys.configuredProviders()）。在这里拦住比让上游回一个 401 更早、
        // 也更好解释——空的 Authorization 头在有些家会被当成匿名请求，错误更莫名其妙。
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing API key for " + provider.providerId);
        }
        // 下面两条是"防串味"检查：把 A 家的渲染结果发到 B 家的地址上，
        // 上游只会回一个看不懂的 400，而真正的原因在我们这边（比如拿 OpenAI 的 payload
        // 去调 Anthropic 的 block 格式）。多花两行，省一次线上排查。
        if (!provider.providerId.equals(model.providerId)) {
            throw new IllegalArgumentException(
                    "Model " + model.modelId + " does not belong to " + provider.providerId);
        }
        if (!provider.providerId.equals(context.providerId)) {
            throw new IllegalArgumentException("Rendered context belongs to " + context.providerId
                    + ", not " + provider.providerId);
        }

        boolean anthropic = provider.adapter == ProviderSpec.Adapter.ANTHROPIC;
        Request.Builder request = new Request.Builder()
                .url(endpoint(provider))
                // 有的网关按 Accept 决定要不要走 SSE；OpenAI 与 Anthropic 的流式接口都认它。
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(new Gson().toJson(requestBody(provider, model, context)), JSON));
        if (anthropic) {
            // Anthropic 用 `x-api-key`，不是 `Authorization: Bearer`。
            request.header("x-api-key", apiKey.trim());
            request.header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            request.header("Authorization", "Bearer " + apiKey.trim());
        }
        Call call = CLIENT.newCall(request.build());
        call.enqueue(new Streaming(call, anthropic, listener));
        return call;
    }

    /**
     * 请求体。各家的差别只有三处（system 位置、工具格式、附件编码），前两处渲染器已经处理好了
     * （后一处也在 payload 里），所以这里只补"发送方式"这一层。
     */
    private static JsonObject requestBody(ProviderSpec provider, ModelSpec model, RenderedContext context) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.modelId);
        // messages **原样**搬过去：RenderedContext 里那份 payload 就是"已经能直接发出去"的形态
        // （`docs/CHAT_ENGINE.md` §2.1 的承诺）。在这里动任何一个字段都等于绕过上下文引擎，
        // 而且"发送时零转换"一旦破例，渲染缓存的有效性判断也就跟着不可信了。
        body.add("messages", new Gson().toJsonTree(context.payload.messages));
        body.addProperty("stream", true);
        if (provider.adapter == ProviderSpec.Adapter.ANTHROPIC) {
            // Anthropic 的 system 是**顶层字段**（OpenAI 系那条 system 由渲染器放进 messages[0]）。
            if (context.payload.system != null) {
                body.addProperty("system", context.payload.system);
            }
            // `max_tokens` 在 Messages API 里**是必填**，漏了直接 400。
            // 用上下文引擎"给回答留的位置"那个数：两边不一致的话，木桶的账就不对了。
            body.addProperty("max_tokens", ContextEngine.RESERVE_FOR_OUTPUT);
        } else {
            // 不带这一行，OpenAI 在流式响应里**根本不发 usage 块**（usage 只在最后一块给，
            // 而且要你主动要），账本就只能记"未知"。
            // **按家开关**（`ProviderSpec.streamUsage`）：带了而这家不认这个字段会 400，
            // 所以只有实测支持的那几家打开；其余等验证——**不要为了"统一"给六家都塞上**，
            // 那会让某几家直接调不通，而症状是"这家挂了"，很难查回这一行。
            if (provider.streamUsage) {
                JsonObject streamOptions = new JsonObject();
                streamOptions.addProperty("include_usage", true);
                body.add("stream_options", streamOptions);
            }
        }
        // temperature / top_p 之类**不传**：不传就是用各家自己的默认值，比我们替六家各拍一个数
        // 更不容易错（真要暴露，也得等设置页有地方解释它们）。
        return body;
    }

    /**
     * 往哪儿发：`<baseUrl>/chat/completions`（OpenAI 兼容六家）或 `<baseUrl>/v1/messages`（Anthropic）。
     *
     * <p>所以 baseUrl 的语义是**"到资源路径之前"那一段**。这条口径对六家里的三家有影响：
     * OpenAI 官方端点应写 `https://api.openai.com/v1`、Kimi 写 `https://api.moonshot.cn/v1`
     * （它们把 `/v1` 算在 base 里），而 DeepSeek 的 `https://api.deepseek.com` 与 GLM 的
     * `https://open.bigmodel.cn/api/paas/v4` 本来就是对的。用户自建反代时也按这个口径填。
     */
    private static String endpoint(ProviderSpec provider) {
        String base = provider.baseUrl == null ? "" : provider.baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String scheme = base.toLowerCase(Locale.ROOT);
        if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "Provider " + provider.providerId + " has no usable base URL");
        }
        return base + (provider.adapter == ProviderSpec.Adapter.ANTHROPIC
                ? "/v1/messages" : "/chat/completions");
    }

    /** 一次流的状态与分发。 */
    private static final class Streaming implements Callback {

        private final Call call;
        private final boolean anthropic;
        private final Listener listener;
        /** 终态只能有一个：onDone 与 onError 谁先到谁算数，后来的丢掉。 */
        private final AtomicBoolean settled = new AtomicBoolean();
        /** 归一化后的四个桶；上游没给 usage 时就一直是 null。 */
        private com.mobilegroup20.modelpilot.contract.model.TokenBundle tokens;
        private boolean sawUsage;
        private boolean sawChunk;

        Streaming(Call call, boolean anthropic, Listener listener) {
            this.call = call;
            this.anthropic = anthropic;
            this.listener = listener;
        }

        @Override public void onResponse(Call call, Response response) {
            // try-with-resources：Response.close() 会把 body 关掉。**401 也必须在关之前不读它**
            // （见 failureOf），但一定要关——不关就是连接泄漏，后面的请求会排队卡住。
            try (Response ignored = response) {
                if (!response.isSuccessful()) {
                    fail(failureOf(response));
                    return;
                }
                ResponseBody body = response.body();
                if (body == null) {
                    fail(ProviderException.network("Provider returned an empty response body", null));
                    return;
                }
                MediaType type = body.contentType();
                if (type != null && !type.toString().toLowerCase(Locale.ROOT).contains("event-stream")) {
                    // 200 但不是 SSE：多半是反代/网关把请求接走了（登录页、错误页、限流页）。
                    // 硬按 SSE 解析的话会得到"零个字、正常结束"，用户看到一条空回答而没有任何提示——
                    // 那正是这个项目最不想留的那种静默失败。
                    fail(ProviderException.http(response.code(),
                            "expected text/event-stream but got " + type + ": " + readSome(body)));
                    return;
                }
                try {
                    readStream(body);
                } catch (ProviderException failure) {
                    fail(failure);
                    return;
                } catch (IOException broken) {
                    // 读到一半断了：**已经派发出去的 delta 不撤回**（用户看到的字是真的），
                    // 但要告诉他这句回答不完整——这一条也是"绝不静默截断"。
                    fail(ProviderException.network(
                            "Stream interrupted: " + broken.getMessage(), broken));
                    return;
                }
                if (!sawChunk) {
                    // 连接开着、一块数据都没有：同样是"看起来成功、实际什么都没发生"。
                    fail(ProviderException.http(response.code(), "upstream returned an empty event stream"));
                    return;
                }
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                if (sawUsage) {
                    // **只在结束时报一次**：Anthropic 把输入与输出 token 分在两条事件里，
                    // 中间报一次会让账本把一次调用记成两笔。
                    listener.onUsage(tokens);
                }
                listener.onDone();
            }
        }

        @Override public void onFailure(Call call, IOException failure) {
            if (call.isCanceled()) {
                // 取消是调用方自己按的（用户返回了上一页），不是上游出错。
                // 再回调一个 onError，界面会弹一句"网络错误"——而他只是离开了这一页。
                return;
            }
            fail(ProviderException.network("Request failed: " + failure.getMessage(), failure));
        }

        private void fail(ProviderException failure) {
            if (settled.compareAndSet(false, true)) {
                listener.onError(failure);
            }
        }

        /**
         * 逐行读 SSE。
         *
         * <p>**用 UTF-8 显式解码，不用 {@code body.charStream()}**：后者听上游 Content-Type 里的
         * charset，而有些网关会把它写错（或干脆不写），中文就会变乱码——这种错看起来像"上游
         * 乱码"，排查方向完全错了。SSE 规范本来就规定 UTF-8。
         */
        private void readStream(ResponseBody body) throws IOException {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                // 空行是事件之间的分隔；`:` 开头是注释/心跳（有的网关拿它保活）。都不用管。
                if (line.isEmpty() || line.startsWith(":")) {
                    continue;
                }
                // `event:` / `id:` / `retry:` 都不读：我们要的类型在 JSON 自己的 `type` 字段里，
                // 而 OpenAI 系连 event 行都没有。
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    // OpenAI 系的结束标记。到这里就**不再等服务器关连接**：有的家会把这个连接
                    // 挂着复用，而我们的 readTimeout 是 0（永不超时），等它等于把界面挂住。
                    return;
                }
                sawChunk = true;
                if (handle(data)) {
                    return;
                }
            }
        }

        /** 处理一个 `data:` 分块；返回 true 表示上游说了"流到此为止"（Anthropic 的 message_stop）。 */
        private boolean handle(String data) throws ProviderException {
            try {
                JsonObject chunk = JsonParser.parseString(data).getAsJsonObject();
                return anthropic ? handleAnthropic(chunk) : handleOpenAi(chunk);
            } catch (RuntimeException unreadable) {
                // 上游给了一块我们读不懂的东西（不是 JSON，或者形状不是我们认识的那个）。
                // **必须在这里拦住**：这段代码跑在 OkHttp 的调度线程上，逃出去的运行时异常会把
                // 那个线程打死，监听方永远等不到 onDone/onError——界面就停在"正在输入"。
                // （顺带也兜住监听方自己抛的运行时异常：回调抛异常是调用方的 bug，
                // 但代价不该是"谁都收不到终态"。）
                throw ProviderException.http(200,
                        "unreadable stream chunk: " + truncate(data, DETAIL_LIMIT));
            }
        }

        private boolean handleOpenAi(JsonObject chunk) {
            JsonObject usage = object(chunk, "usage");
            if (usage != null) {
                // 带上 `stream_options.include_usage` 之后，**最后一块**长这样：
                // `{"choices":[],"usage":{"prompt_tokens":…,"completion_tokens":…}}`；
                // 中间那些块的 usage 是显式 null，所以这个 null 判断不能省。
                readUsage(usage);
            }
            JsonArray choices = array(chunk, "choices");
            if (choices == null || choices.size() == 0) {
                return false;
            }
            JsonObject delta = object(choices.get(0).getAsJsonObject(), "delta");
            String text = string(delta, "content");
            if (text != null && !text.isEmpty()) {
                listener.onDelta(text);
            }
            // 说明：DeepSeek Reasoner 之类的思考过程在 `delta.reasoning_content` 里，
            // **故意不派发**——设计稿的对话页没有思考区，混进正文会污染存下来的那条消息。
            // 将来要展示思考过程，就再开一个回调，别走 onDelta。
            return false;
        }

        private boolean handleAnthropic(JsonObject chunk) throws ProviderException {
            String type = string(chunk, "type");
            if (type == null) {
                return false;
            }
            switch (type) {
                case "message_start": {
                    // 输入 token 只有这里给；输出的最终值在后面的 `message_delta` 里
                    // （这里带的是个占位值，所以待会儿会被覆盖）。
                    readUsage(object(object(chunk, "message"), "usage"));
                    return false;
                }
                case "content_block_delta": {
                    JsonObject delta = object(chunk, "delta");
                    // 只认 `text_delta`：`input_json_delta`（工具参数的流式 JSON 片段）拼起来才有意义，
                    // 逐段派发会让界面上出现半截 JSON。
                    if ("text_delta".equals(string(delta, "type"))) {
                        String text = string(delta, "text");
                        if (text != null && !text.isEmpty()) {
                            listener.onDelta(text);
                        }
                    }
                    return false;
                }
                case "message_delta": {
                    // 输出的**最终** token 数在这里。**只合并 output 一桶**：
                    // `message_start` 已经给过输入的四个桶了，整体覆盖会把 input 抹成 0。
                    Long out = number(object(chunk, "usage"), "output_tokens");
                    // **只在 `message_start` 已经给过输入时才合并**：没给过的话，
                    // 单独造一个 bundle 就等于把输入编成 0（"不知道"变"没有"）。
                    if (out != null && tokens != null) {
                        tokens.output = out;
                        sawUsage = true;
                    }
                    return false;
                }
                case "message_stop":
                    // Anthropic 的结束标记（它没有 `[DONE]`）。
                    return true;
                case "error": {
                    // 200 的流里报错：Anthropic 用它说"限流了""内容被拦了"。
                    // 归到 HTTP 这一类（上游拒绝了这次调用），不是本地网络问题。
                    throw ProviderException.streamError(200,
                            string(object(chunk, "error"), "message"));
                }
                default:
                    // ping / content_block_start / content_block_stop 都不需要我们做什么。
                    return false;
            }
        }

        /**
         * 把上游给的 usage 归一成四个桶。
         *
         * <p>两种字段形状（OpenAI 的 `prompt_tokens` 是输入总量、Anthropic 的
         * `input_tokens` 已经是未命中部分）由 {@code UsageSplitter} 分辨——这里只负责
         * 把 Gson 对象递过去。**认不出来就保持 null**，不报一个全 0 的 bundle：
         * "未知"被洗成"花了 0"是账本上最难发现的一类错（`CONTRACTS.md` §4）。
         */
        private void readUsage(JsonObject usage) {
            if (usage == null) {
                return;
            }
            TOKEN_SPLIT: {
                com.mobilegroup20.modelpilot.contract.model.TokenBundle split =
                        com.mobilegroup20.modelpilot.chat.UsageSplitter.split(
                                new com.mobilegroup20.modelpilot.chat.UsageSplitter.UsageFields() {
                                    @Override public Long number(String field) {
                                        return ProviderClient.number(usage, field);
                                    }
                                    @Override public Long nested(String object, String field) {
                                        JsonObject inner = ProviderClient.object(usage, object);
                                        return inner == null ? null
                                                : ProviderClient.number(inner, field);
                                    }
                                });
                if (split == null) {
                    break TOKEN_SPLIT;
                }
                tokens = split;
                sawUsage = true;
            }
        }
    }

    /**
     * 非 2xx 的失败。三类的分法就是这里定的：
     * 401/403 一个类（key 的问题）、429 一个类（等一会儿就好）、其余一个类（看正文才知道）。
     */
    private static ProviderException failureOf(Response response) {
        int status = response.code();
        if (status == 401 || status == 403) {
            // **不读它的正文。** 上游的 401 正文经常把 key 回显一段
            // （"Incorrect API key provided: sk-abc..."），而我们承诺 key 不进日志、
            // 不进崩溃上报、不进用户截图——最省事的做法就是根本不让那串字进来。
            // 调用方靠 kind 就知道该提示"这一家的 key 无效，去设置里改"。
            return ProviderException.invalidKey(status);
        }
        if (status == 429) {
            return ProviderException.rateLimited(status, retryAfterSeconds(response));
        }
        ResponseBody body = response.body();
        return ProviderException.http(status, body == null ? "" : readSome(body));
    }

    private static Long retryAfterSeconds(Response response) {
        String value = response.header("Retry-After");
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : seconds;
        } catch (NumberFormatException notSeconds) {
            // `Retry-After` 还允许 HTTP-date 形式。**不去解析它**：把日期换算成"还有几秒"
            // 要依赖设备时钟，报一个错的等待时间比只说"稍后重试"更糟。
            return null;
        }
    }

    /** 读一点点正文当线索（**限长**：别把一个 HTML 错误页整个拖进内存与异常消息里）。 */
    private static String readSome(ResponseBody body) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));
            char[] buffer = new char[DETAIL_LIMIT];
            int read = 0;
            while (read < buffer.length) {
                int count = reader.read(buffer, read, buffer.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            return truncate(new String(buffer, 0, read), DETAIL_LIMIT);
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement child = parent.get(name);
        // `!isJsonObject()` 已经涵盖了"成员不存在"和"成员是 null"（JsonNull）两种情况。
        return child == null || !child.isJsonObject() ? null : child.getAsJsonObject();
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement child = parent.get(name);
        return child == null || !child.isJsonArray() ? null : child.getAsJsonArray();
    }

    private static String string(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement child = parent.get(name);
        // 只认标量：`"content": null` 在流里很常见（工具调用那几块、usage 那一块），
        // 而 `getAsString()` 对 JsonNull 会抛——那会把一条完全正常的流判成"读不懂"。
        return child == null || !child.isJsonPrimitive() ? null : child.getAsString();
    }

    private static Long number(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement child = parent.get(name);
        if (child == null || !child.isJsonPrimitive()) {
            return null;
        }
        try {
            // 有的家把 token 数写成字符串（`"12"`）；解析不出来就当没给，**不编 0**。
            return child.getAsLong();
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').replace('\r', ' ').trim();
        return single.length() <= limit ? single : single.substring(0, limit) + "…";
    }
}
