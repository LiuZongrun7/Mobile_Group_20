package com.mobilegroup20.modelpilot.data.remote;

import com.mobilegroup20.modelpilot.contract.model.*;
import com.mobilegroup20.modelpilot.contract.tool.*;
import java.util.List;
import okhttp3.MultipartBody;
import retrofit2.Call;
import retrofit2.http.*;

/** All routes are relative to the team's API base URL. */
public interface ForumApi {
    @GET("forum/posts") Call<ForumPage<ForumPost>> posts(@Header("Authorization") String auth, @Query("cursor") String cursor, @Query("limit") int limit, @Query("q") String query);
    @GET("forum/news") Call<ForumPage<NewsArticle>> news(@Header("Authorization") String auth, @Query("cursor") String cursor, @Query("limit") int limit, @Query("q") String query);
    @GET("forum/trending") Call<ForumTrending> trending(@Header("Authorization") String auth);
    @GET("forum/posts/{id}") Call<ForumPost> post(@Header("Authorization") String auth, @Path("id") String id);
    @GET("forum/posts/{id}/replies") Call<ForumPage<ForumReply>> replies(@Header("Authorization") String auth, @Path("id") String id, @Query("cursor") String cursor, @Query("limit") int limit);
    @Multipart @POST("forum/images") Call<ForumImage> upload(@Header("Authorization") String auth, @Part MultipartBody.Part image);
    @POST("forum/posts") Call<ForumPost> publish(@Header("Authorization") String auth, @Header("Idempotency-Key") String requestId, @Body PostDraft draft);
    @POST("forum/posts/{id}/replies") Call<ForumReply> reply(@Header("Authorization") String auth, @Path("id") String id, @Header("Idempotency-Key") String requestId, @Body ReplyDraft draft);
    @PUT("forum/posts/{id}/like") Call<ForumPost> like(@Header("Authorization") String auth, @Path("id") String id);
    @DELETE("forum/posts/{id}/like") Call<ForumPost> unlike(@Header("Authorization") String auth, @Path("id") String id);
    @GET("forum/official") Call<List<ForumPost>> official(@Header("Authorization") String auth, @Query("limit") int limit);
    @GET("forum/hot") Call<List<ForumPost>> hot(@Header("Authorization") String auth, @Query("since") String since, @Query("limit") int limit);
    @GET("forum/me/posts") Call<List<ForumPost>> mine(@Header("Authorization") String auth);
    @GET("forum/highlights") Call<ForumHighlights> highlights(@Header("Authorization") String auth, @Query("modelFilter") String filter, @Query("since") String since, @Query("limit") int limit);
    @GET("forum/me/threads") Call<MyThreads> threads(@Header("Authorization") String auth, @Query("since") String since, @Query("limit") int limit);
    final class ReplyDraft {
        public final String body;
        public ReplyDraft(String body) { this.body = body; }
    }
}
