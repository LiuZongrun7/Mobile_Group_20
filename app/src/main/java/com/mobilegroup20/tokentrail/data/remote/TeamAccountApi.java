package com.mobilegroup20.tokentrail.data.remote;

import com.google.gson.annotations.SerializedName;
import retrofit2.Call;
import retrofit2.http.*;

/** Existing aibox_backend account endpoints; the forum does not create accounts. */
public interface TeamAccountApi {
    @FormUrlEncoded @POST("login")
    Call<LoginResponse> login(@Field("username") String username, @Field("password") String password);
    @GET("users/me") Call<AccountResponse> me(@Header("Authorization") String authorization);
    @POST("logout") Call<Void> logout(@Header("Authorization") String authorization);

    class LoginResponse {
        @SerializedName("access_token") public String accessToken;
        @SerializedName("token_type") public String tokenType;
    }
    class AccountResponse {
        @SerializedName("user_id") public String userId;
        public String username;
    }
}
