package com.mobilegroup20.modelpilot.ui.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mobilegroup20.modelpilot.R;
import com.mobilegroup20.modelpilot.data.remote.AccountApi;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 账号接口的**线上契约**：请求体字段名、响应字段名、错误体的解析。
 *
 * <p>为什么值得单测：这套字段名（`identifier`、`emailVerified`、`code`）是和后端一起冻结的，
 * 拼错了不会编译报错，只会在真机上变成 400 —— 而 400 的界面提示是「服务器没接受这次请求」，
 * 从现象上完全看不出是字段名写错了。<b>这里是唯一能提前发现它的地方。</b>
 *
 * <p>最后一条用例把「服务端错误体 → 用户看到的话」整条路走通：错误码解析（{@link AccountApi}）
 * 和文案映射（{@link AccountInput}）分开测都对，接起来对不对是另一回事。
 */
public class AccountApiContractTest {

    private static AccountApi api(MockWebServer server) {
        return new Retrofit.Builder().baseUrl(server.url("/api/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(AccountApi.class);
    }

    /**
     * 请求体**只能读一次**（`RecordedRequest.getBody()` 是个会被消费掉的 Buffer），
     * 所以这里一次性解析成 JsonObject 交给断言用——分两次 `readUtf8()` 第二次会读到空串，
     * 表现是「字段没了」，很容易被误判成序列化写错了。
     */
    private static JsonObject body(RecordedRequest request) {
        return JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
    }

    private static String field(JsonObject body, String name) {
        return body.has(name) ? body.get(name).getAsString() : null;
    }

    @Test public void register_sends_email_username_and_password_and_parses_the_unverified_account()
            throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AccountApi api = api(server);
            server.enqueue(new MockResponse().setResponseCode(201).setBody(
                    "{\"userId\":\"u_1\",\"username\":\"nick\",\"email\":\"a@b.co\","
                            + "\"emailVerified\":false,\"createdAtEpochMillis\":1791000000000}"));
            Response<AccountApi.Account> response = api.register(
                    new AccountApi.Registration("a@b.co", "nick", "password1")).execute();
            RecordedRequest request = server.takeRequest();
            assertEquals("POST", request.getMethod());
            assertEquals("/api/account/register", request.getPath());
            JsonObject sent = body(request);
            // 三个字段缺一不可（服务端三个都必填），名字必须和契约一模一样。
            assertEquals("a@b.co", field(sent, "email"));
            assertEquals("nick", field(sent, "username"));
            assertEquals("password1", field(sent, "password"));
            AccountApi.Account account = response.body();
            assertTrue(response.isSuccessful());
            assertEquals("u_1", account.userId);
            assertEquals("a@b.co", account.email);
            assertFalse(account.emailVerified);
            assertEquals(1791000000000L, account.createdAtEpochMillis);
            // **注册不发 token**：服务端有意为之，本地不能假装拿到了会话。
            assertNull(account.token);
        }
    }

    @Test public void login_sends_identifier_not_username() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AccountApi api = api(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"userId\":\"u_1\",\"username\":\"nick\",\"email\":\"a@b.co\","
                            + "\"emailVerified\":true,\"token\":\"tt_app_x\",\"expiresAtEpochMillis\":1791000000000}"));
            Response<AccountApi.Account> response = api.login(
                    new AccountApi.Login("a@b.co", "password1")).execute();
            RecordedRequest request = server.takeRequest();
            assertEquals("/api/account/login", request.getPath());
            JsonObject sent = body(request);
            assertEquals("a@b.co", field(sent, "identifier"));
            assertEquals("password1", field(sent, "password"));
            // 老字段 `username` 服务端还认，但新代码发的是 `identifier`——两个都发等于
            // 让服务端去猜哪个才算数（它只认第一个解析到的），这种歧义不能出现在请求里。
            assertNull(field(sent, "username"));
            assertEquals("tt_app_x", response.body().token);
            assertTrue(response.body().emailVerified);
        }
    }

    @Test public void verify_and_resend_hit_the_frozen_paths() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AccountApi api = api(server);
            server.enqueue(new MockResponse().setBody(
                    "{\"emailVerified\":true,\"email\":\"a@b.co\",\"verifiedAtEpochMillis\":1791000000000}"));
            Response<AccountApi.Verified> verified = api.verify(
                    new AccountApi.Verification("a@b.co", "123456")).execute();
            RecordedRequest verify = server.takeRequest();
            assertEquals("/api/account/verify", verify.getPath());
            JsonObject code = body(verify);
            assertEquals("a@b.co", field(code, "email"));
            assertEquals("123456", field(code, "code"));
            assertTrue(verified.body().emailVerified);
            assertEquals("a@b.co", verified.body().email);

            server.enqueue(new MockResponse().setBody("{\"sent\":true}"));
            Response<AccountApi.Sent> sent = api.resend(new AccountApi.Resend("a@b.co")).execute();
            RecordedRequest resend = server.takeRequest();
            assertEquals("/api/account/verify/resend", resend.getPath());
            assertEquals("a@b.co", field(body(resend), "email"));
            assertTrue(sent.body().sent);
        }
    }

    @Test public void error_bodies_keep_their_code_and_become_the_right_sentence() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AccountApi api = api(server);
            // 邮箱被占：409 + EMAIL_TAKEN。这个 code 丢了的话界面只会说「出错了」，
            // 而用户完全不知道该换邮箱还是换用户名。
            server.enqueue(new MockResponse().setResponseCode(409)
                    .setBody("{\"code\":\"EMAIL_TAKEN\",\"message\":\"email already registered\"}"));
            Response<AccountApi.Account> taken = api.register(
                    new AccountApi.Registration("a@b.co", "nick", "password1")).execute();
            assertEquals("EMAIL_TAKEN", AccountApi.errorCode(taken));
            // **连读两次必须是同一个结果**：errorBody() 是只能读一次的流，早先的实现里
            // 第二次读会拿到空串，于是 code 变成 null，界面退化成笼统的「请求没成功」。
            // 这个断言就是那次退化的回归测试。
            assertEquals("EMAIL_TAKEN", AccountApi.errorCode(taken));
            assertEquals(R.string.account_email_taken,
                    AccountInput.failure(taken.code(), AccountApi.errorCode(taken)).message);

            // 服务端没配发信服务：账号根本没建成，这句话必须送到界面上。
            server.enqueue(new MockResponse().setResponseCode(503)
                    .setBody("{\"code\":\"MAIL_NOT_CONFIGURED\",\"message\":\"smtp not configured\"}"));
            Response<AccountApi.Account> mail = api.register(
                    new AccountApi.Registration("a@b.co", "nick", "password1")).execute();
            assertEquals("MAIL_NOT_CONFIGURED", AccountApi.errorCode(mail));
            assertEquals(R.string.account_mail_not_configured,
                    AccountInput.failure(mail.code(), AccountApi.errorCode(mail)).message);

            // 网关返回一坨 HTML 或者空体：解析失败要**返回 null 而不是抛异常**，
            // 让调用方按状态码兜底（否则一次 502 会把 App 打崩）。
            server.enqueue(new MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"));
            Response<AccountApi.Account> gateway = api.register(
                    new AccountApi.Registration("a@b.co", "nick", "password1")).execute();
            assertNull(AccountApi.errorCode(gateway));
            assertEquals(R.string.forum_request_error,
                    AccountInput.failure(gateway.code(), AccountApi.errorCode(gateway)).message);

            // 403 后面没有 code（老服务端）时，按契约里 403 唯一的含义处理：邮箱没验证，
            // 界面据此把用户送到验证码那一段。
            server.enqueue(new MockResponse().setResponseCode(403).setBody("{}"));
            Response<AccountApi.Account> unverified = api.login(
                    new AccountApi.Login("a@b.co", "password1")).execute();
            AccountInput.Failure failure = AccountInput.failure(unverified.code(), AccountApi.errorCode(unverified));
            assertEquals(R.string.account_unverified, failure.message);
            assertEquals(AccountInput.ACTION_VERIFY, failure.action);
        }
    }
}
