package com.mobilegroup20.modelpilot.data.repository;

import androidx.lifecycle.LiveData;

import java.util.List;

import com.mobilegroup20.modelpilot.contract.model.ForumPost;
import com.mobilegroup20.modelpilot.contract.model.ForumReply;
import com.mobilegroup20.modelpilot.contract.tool.ForumHighlights;
import com.mobilegroup20.modelpilot.contract.tool.MyThreads;

/**
 * 论坛数据的读写口。<b>论坛侧（汪庭栋）实现。</b>
 *
 * <p>这些是公共帖子池的查询视图，不是三个独立的可见性桶：
 * <ol>
 *   <li>官方帖 —— 公共可读，只有官方账号能发；</li>
 *   <li>热帖 —— 公共可读，按热度排序；</li>
 *   <li>我的帖子 —— 按当前会话作者筛选，帖子本身仍然公共可读，含收到的回复。</li>
 * </ol>
 * 前两个给 agent 的 {@code getForumHighlights}，第三个给 {@code getMyThreads}。
 * 这些方法为已有 agent 工具保留。新闻与新版社区 UI 见 ForumFeedRepository。
 */
public interface ForumRepository {

    /**
     * 官方帖，按时间倒序。用户看的是「官方最近说了什么」。
     */
    LiveData<List<ForumPost>> officialPosts(int limit);

    /**
     * 社区热帖，按 {@link ForumPost#rankScore} 从高到低。
     *
     * <p><b>排序在这个方法背后完成</b>，调用方不要拿回来自己再排一遍：
     * 排序公式只应该有一处实现，否则界面上一个顺序、agent 嘴里另一个顺序，
     * 用户会以为其中一个是错的。
     */
    LiveData<List<ForumPost>> hotPosts(String since, int limit);

    /** 本人发过的帖子，按时间倒序。 */
    LiveData<List<ForumPost>> myPosts(String uid);

    /** 给 agent 的 {@code getForumHighlights} 用：官方帖 + 热帖合在一起。 */
    LiveData<ForumHighlights> highlights(String modelFilter, String since, int limit);

    /** 给 agent 的 {@code getMyThreads} 用：本人的帖子连同回复。 */
    LiveData<MyThreads> myThreads(String uid, String since, int limit);

    /** 某个帖子下的回复，按时间正序。 */
    LiveData<List<ForumReply>> repliesOf(String postId);

    /**
     * 发帖。返回带 id 的完整帖子。
     *
     * <p>作者相关字段（{@code authorUid} 等）由实现从当前登录态填，
     * <b>不信任调用方传来的值</b>，否则用户能伪造官方帖。
     */
    LiveData<ForumPost> createPost(ForumPost draft);

    /** 回帖。作者字段同样由实现填。 */
    LiveData<ForumReply> createReply(ForumReply draft);
}
