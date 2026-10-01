package com.mobilegroup20.modelpilot.data.remote;

import retrofit2.Call;
import retrofit2.http.*;

/** Temporary identities accepted exclusively by the isolated forum test service. */
public interface ForumTestSessionApi {
    @POST("forum/test-session") Call<TestSession> enter();
    @DELETE("forum/test-session") Call<Void> leave(@Header("Authorization") String authorization);

    class TestSession {
        public String token;
        public String accountId;
        public String displayName;
        public long expiresAtEpochMillis;
    }
}
