package com.mobilegroup20.modelpilot.data.remote;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import static org.junit.Assert.*;

public class ForumTestSessionApiTest {
    @Test public void enteringNeedsNoCredentialsAndLeavingUsesOnlyTheTestEndpoint() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            ForumTestSessionApi api = new Retrofit.Builder().baseUrl(server.url("/test-api/"))
                    .addConverterFactory(GsonConverterFactory.create()).build().create(ForumTestSessionApi.class);
            server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"token\":\"tt_test_session\",\"accountId\":\"test_user\",\"displayName\":\"Tester\",\"expiresAtEpochMillis\":1791000000000}"));
            assertEquals("test_user", api.enter().execute().body().accountId);
            RecordedRequest enter = server.takeRequest();
            assertEquals("POST", enter.getMethod()); assertEquals("/test-api/forum/test-session", enter.getPath());
            assertNull(enter.getHeader("Authorization")); assertEquals(0, enter.getBodySize());
            server.enqueue(new MockResponse().setBody("{\"status\":\"ok\"}"));
            assertTrue(api.leave("Bearer tt_test_session").execute().isSuccessful());
            RecordedRequest leave = server.takeRequest();
            assertEquals("DELETE", leave.getMethod()); assertEquals("/test-api/forum/test-session", leave.getPath());
        }
    }
}
