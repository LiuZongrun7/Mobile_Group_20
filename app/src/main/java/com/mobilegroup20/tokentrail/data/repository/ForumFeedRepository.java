package com.mobilegroup20.tokentrail.data.repository;

import androidx.lifecycle.LiveData;
import com.mobilegroup20.tokentrail.contract.model.*;

/** New UI contract; existing ForumRepository agent methods remain source compatible. */
public interface ForumFeedRepository {
    String sessionIdentity();
    LiveData<ForumResult<ForumPage<ForumPost>>> posts(String cursor);
    LiveData<ForumResult<ForumPage<NewsArticle>>> news(String cursor);
    LiveData<ForumResult<ForumPost>> post(String id);
    LiveData<ForumResult<ForumPage<ForumReply>>> replies(String id, String cursor);
    LiveData<ForumResult<ForumImage>> uploadImage(byte[] bytes, String mimeType);
    LiveData<ForumResult<ForumPost>> publish(PostDraft draft, String requestId);
    LiveData<ForumResult<ForumReply>> reply(String postId, String body, String requestId);
    LiveData<ForumResult<ForumPost>> like(String postId, boolean liked);
}
