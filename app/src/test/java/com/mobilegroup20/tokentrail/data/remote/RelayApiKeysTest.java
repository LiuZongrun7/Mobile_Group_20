package com.mobilegroup20.tokentrail.data.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * 「我名下所有的 key」这条接口的**接线**测试。2026-02 加。
 *
 * <p>为什么值得写：这个页面唯一的用处就是让用户回答「我到底录了什么」，
 * 而它挂掉的方式很安静——字段名对不上时 Gson 不报错，只是**留空**，
 * 界面上就成了「上游未知」「还没转发过」。写这个测试的时候正是这么栽的：
 * 服务端返回 `upstreamHost`，客户端模型里没这个字段，编译期才发现。
 *
 * <p>另外两条也在这里盯着：
 * <ul>
 *   <li>请求必须带**账号 token**，不是 relay key——relay key 只能看自己那一条；</li>
 *   <li>响应里**不能有 key 明文**（服务端只存 sha256），列表里也没有。</li>
 * </ul>
 */
public class RelayApiKeysTest {
    private MockWebServer server;
    private RelayApi api;

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Gson gson = new GsonBuilder().create();
        api = new Retrofit.Builder().baseUrl(server.url("/api/"))
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build().create(RelayApi.class);
    }

    @After public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test public void listingKeysParsesEveryFieldTheScreenNeeds() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"uid\":\"u_abc\",\"items\":["
                + "{\"uid\":\"<sha256 of key A>\",\"displayName\":\"家里的电脑\","
                + "\"upstreamUrl\":\"https://api.deepseek.com\",\"upstreamHost\":\"api.deepseek.com\","
                + "\"hasUpstreamSecret\":true,\"requestCount\":12,"
                + "\"createdAtEpochMillis\":1790500000000,\"lastUsedAtEpochMillis\":1790600000000,"
                + "\"disabled\":false},"
                + "{\"uid\":\"<sha256 of key B>\",\"displayName\":\"备用\","
                + "\"upstreamUrl\":null,\"upstreamHost\":null,\"hasUpstreamSecret\":false,"
                + "\"requestCount\":0,\"createdAtEpochMillis\":1790510000000,"
                + "\"lastUsedAtEpochMillis\":null,\"disabled\":true}]}"));

        retrofit2.Response<RelayApi.KeyList> response =
                api.keys("Bearer tt_app_token").execute();
        assertTrue(response.isSuccessful());
        RelayApi.KeyList body = response.body();
        assertEquals("u_abc", body.uid);
        assertEquals(2, body.items.size());

        RelayApi.Enrollment first = body.items.get(0);
        assertEquals("家里的电脑", first.displayName);
        assertEquals("api.deepseek.com", first.upstreamHost);
        assertEquals(12, first.requestCount);
        assertEquals(1790600000000L, (long) first.lastUsedAtEpochMillis);
        assertTrue(first.hasUpstreamSecret);

        RelayApi.Enrollment second = body.items.get(1);
        // 认不出来的上游必须是 **null**，不是空串——界面据此说「上游未知」。
        assertNull(second.upstreamHost);
        // 从没用过也是 null，和「用过 0 次」不是一回事。
        assertNull(second.lastUsedAtEpochMillis);
        assertTrue(second.disabled);

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/relay/keys/all", request.getPath());
        // **账号 token，不是 relay key**：relay key 打这个接口是 401（服务端有意）。
        assertEquals("Bearer tt_app_token", request.getHeader("Authorization"));
    }

    @Test public void aKeyCanBeDisabledByItsOwnUid() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"ok\"}"));
        assertTrue(api.revokeKey("Bearer tt_app_token", "<sha256>").execute().isSuccessful());
        RecordedRequest request = server.takeRequest();
        assertEquals("DELETE", request.getMethod());
        assertEquals("/api/relay/keys/%3Csha256%3E", request.getPath());
    }
}
