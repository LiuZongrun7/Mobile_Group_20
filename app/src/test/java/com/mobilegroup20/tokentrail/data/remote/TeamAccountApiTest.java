package com.mobilegroup20.tokentrail.data.remote;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import static org.junit.Assert.*;

public class TeamAccountApiTest {
    @Test public void existingLoginUsesFormAndCurrentUserVerifiesNumericUid() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            TeamAccountApi api = new Retrofit.Builder().baseUrl(server.url("/api/"))
                    .addConverterFactory(GsonConverterFactory.create()).build().create(TeamAccountApi.class);
            server.enqueue(new MockResponse().setBody("{\"access_token\":\"session\",\"token_type\":\"bearer\"}"));
            assertEquals("session", api.login("alice+test", "a&b=c").execute().body().accessToken);
            RecordedRequest login = server.takeRequest();
            assertEquals("/api/login", login.getPath());
            assertTrue(login.getHeader("Content-Type").startsWith("application/x-www-form-urlencoded"));
            assertEquals("username=alice%2Btest&password=a%26b%3Dc", login.getBody().readUtf8());
            server.enqueue(new MockResponse().setBody("{\"user_id\":42,\"username\":\"alice+test\"}"));
            assertEquals("42", api.me("Bearer session").execute().body().userId);
            RecordedRequest me = server.takeRequest();
            assertEquals("/api/users/me", me.getPath()); assertEquals("Bearer session", me.getHeader("Authorization"));
        }
    }
}
