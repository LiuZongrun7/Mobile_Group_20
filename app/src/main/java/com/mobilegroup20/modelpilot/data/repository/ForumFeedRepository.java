package com.mobilegroup20.modelpilot.data.repository;

import androidx.lifecycle.LiveData;
import com.mobilegroup20.modelpilot.contract.model.*;

/** New UI contract; existing ForumRepository agent methods remain source compatible. */
public interface ForumFeedRepository {
    String sessionIdentity();
    default LiveData<ForumResult<ForumPage<ForumPost>>> posts(String cursor) { return posts(cursor, ""); }
    default LiveData<ForumResult<ForumPage<NewsArticle>>> news(String cursor) { return news(cursor, ""); }
    LiveData<ForumResult<ForumPage<ForumPost>>> posts(String cursor, String query);
    LiveData<ForumResult<ForumPage<NewsArticle>>> news(String cursor, String query);
    LiveData<ForumResult<ForumTrending>> trending();
    LiveData<ForumResult<ForumPost>> post(String id);
    LiveData<ForumResult<ForumPage<ForumReply>>> replies(String id, String cursor);
    LiveData<ForumResult<ForumImage>> uploadImage(byte[] bytes, String mimeType);
    LiveData<ForumResult<ForumPost>> publish(PostDraft draft, String requestId);
    LiveData<ForumResult<ForumReply>> reply(String postId, String body, String requestId);
    LiveData<ForumResult<ForumPost>> like(String postId, boolean liked);
}
