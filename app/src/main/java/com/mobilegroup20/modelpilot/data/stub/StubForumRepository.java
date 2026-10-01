package com.mobilegroup20.modelpilot.data.stub;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

import com.mobilegroup20.modelpilot.contract.model.ForumPost;
import com.mobilegroup20.modelpilot.contract.model.ForumReply;
import com.mobilegroup20.modelpilot.contract.tool.ForumHighlights;
import com.mobilegroup20.modelpilot.contract.tool.MyThreads;
import com.mobilegroup20.modelpilot.data.repository.ForumRepository;

/**
 * {@link ForumRepository} 的桩实现：几条假帖子和假回复。
 *
 * <p>三个桶（官方帖、热帖、我的帖子）都造了样例，这样 agent 的两个论坛工具
 * ——{@code getForumHighlights} 和 {@code getMyThreads}——在论坛侧完工之前就能调通。
 *
 * <p>注意这些<b>都不该入库</b>：桩只活在内存里，所以样例数据不会污染真实的用量统计。
 */
public class StubForumRepository implements ForumRepository {

    /** 当前用户在这个桩里的 uid，用来造「我的帖子」。真实现从登录态取。 */
    public static final String STUB_UID = "stub-user";

    @Override
    public LiveData<List<ForumPost>> officialPosts(int limit) {
        List<ForumPost> posts = new ArrayList<>();
        posts.add(official("MiMo v2.6 发布：缓存写入限时免费",
                "缓存写入限时免费，缓存读仍按低价计费。完整价目表见官方页面。", "mimo-v2.6-pro"));
        posts.add(official("OpenAI 调整推理模型计费口径",
                "推理 token 计入输出，账单上的输出量会明显变大。", "gpt-5"));
        return live(trim(posts, limit));
    }

    @Override
    public LiveData<List<ForumPost>> hotPosts(String since, int limit) {
        List<ForumPost> posts = new ArrayList<>();
        posts.add(community("把系统提示词放前面，缓存命中率能到七成",
                "同样的提示词前缀重复调用会命中缓存，实测输入成本降到三分之一。", "mimo-v2.6-pro", 214, 0.92));
        posts.add(community("长会话记得定期开新会话",
                "上下文越长，每一轮都要重新计费，单次成本会滚雪球。", null, 158, 0.81));
        posts.add(community("deepseek-reasoner 的思考 token 也在账单里",
                "看输出量比预期高的时候先查这个。", "deepseek-reasoner", 96, 0.66));
        return live(trim(posts, limit));
    }

    @Override
    public LiveData<List<ForumPost>> myPosts(String uid) {
        List<ForumPost> posts = new ArrayList<>();
        posts.add(sampleThread().post);
        return live(posts);
    }

    @Override
    public LiveData<ForumHighlights> highlights(String modelFilter, String since, int limit) {
        ForumHighlights out = new ForumHighlights();
        out.since = since;
        out.modelFilter = modelFilter;
        out.asOfEpochMillis = System.currentTimeMillis();

        List<ForumPost> all = new ArrayList<>();
        all.add(official("MiMo v2.6 发布：缓存写入限时免费",
                "缓存写入限时免费，缓存读仍按低价计费。完整价目表见官方页面。", "mimo-v2.6-pro"));
        all.add(community("把系统提示词放前面，缓存命中率能到七成",
                "同样的提示词前缀重复调用会命中缓存，实测输入成本降到三分之一。", "mimo-v2.6-pro", 214, 0.92));
        all.add(community("长会话记得定期开新会话",
                "上下文越长，每一轮都要重新计费，单次成本会滚雪球。", null, 158, 0.81));

        for (ForumPost post : all) {
            if (modelFilter != null && !modelFilter.equals(post.modelTag)) {
                continue;
            }
            out.posts.add(post);
        }
        while (out.posts.size() > limit) {
            out.posts.remove(out.posts.size() - 1);
        }
        return live(out);
    }

    @Override
    public LiveData<MyThreads> myThreads(String uid, String since, int limit) {
        MyThreads out = new MyThreads();
        out.since = since;
        out.asOfEpochMillis = System.currentTimeMillis();
        out.threads.add(sampleThread());
        return live(out);
    }

    @Override
    public LiveData<List<ForumReply>> repliesOf(String postId) {
        return live(sampleThread().replies);
    }

    @Override
    public LiveData<ForumPost> createPost(ForumPost draft) {
        // 桩不落库。作者字段本该由实现从登录态填，这里也照做，别让调用方以为可以自己指定。
        draft.authorUid = STUB_UID;
        draft.authorName = "我";
        draft.createdAtEpochMillis = System.currentTimeMillis();
        return live(draft);
    }

    @Override
    public LiveData<ForumReply> createReply(ForumReply draft) {
        draft.authorUid = STUB_UID;
        draft.authorName = "我";
        draft.createdAtEpochMillis = System.currentTimeMillis();
        return live(draft);
    }

    // ---------------------------------------------------------------- 造数据

    private static ForumPost official(String title, String body, String modelTag) {
        ForumPost post = new ForumPost();
        post.id = "official-" + Math.abs(title.hashCode() % 10_000);
        post.title = title;
        post.body = body;
        post.source = ForumPost.Source.OFFICIAL;
        post.modelTag = modelTag;
        post.authorName = "官方";
        post.authorUid = null;
        post.createdAtEpochMillis = System.currentTimeMillis() - 86_400_000L * 3;
        post.viewCount = 1_240;
        post.helpfulCount = 88;
        post.rankScore = 1.0;
        return post;
    }

    private static ForumPost community(String title, String body, String modelTag,
                                       long helpful, double score) {
        ForumPost post = new ForumPost();
        post.id = "post-" + Math.abs(title.hashCode() % 10_000);
        post.title = title;
        post.body = body;
        post.source = ForumPost.Source.COMMUNITY;
        post.modelTag = modelTag;
        post.authorName = "匿名用户";
        post.authorUid = "someone-else";
        post.createdAtEpochMillis = System.currentTimeMillis() - 86_400_000L;
        post.viewCount = 480;
        post.helpfulCount = helpful;
        post.rankScore = score;
        return post;
    }

    /** 一条「我的帖子 + 官方回复 + 网友回复」，用来演示第三个桶的样子。 */
    private static MyThreads.Thread sampleThread() {
        ForumPost post = new ForumPost();
        post.id = "my-post-1";
        post.title = "缓存写是不是只在第一次调用才产生？";
        post.body = "连着调了三次，只有第一次的缓存写不为零，是我理解错了吗？";
        post.source = ForumPost.Source.COMMUNITY;
        post.modelTag = "mimo-v2.6-pro";
        post.authorName = "我";
        post.authorUid = STUB_UID;
        post.createdAtEpochMillis = System.currentTimeMillis() - 86_400_000L * 2;
        post.helpfulCount = 5;
        post.rankScore = 0.4;

        MyThreads.Thread thread = new MyThreads.Thread();
        thread.post = post;

        ForumReply officialReply = new ForumReply();
        officialReply.id = "reply-1";
        officialReply.postId = post.id;
        officialReply.body = "是的，缓存写入只在建立缓存那一次计费，之后都是缓存读。";
        officialReply.authorName = "官方";
        officialReply.official = true;
        officialReply.createdAtEpochMillis = System.currentTimeMillis() - 86_400_000L * 2 + 3_600_000L;
        officialReply.helpfulCount = 12;
        thread.replies.add(officialReply);

        ForumReply peerReply = new ForumReply();
        peerReply.id = "reply-2";
        peerReply.postId = post.id;
        peerReply.body = "补充一点：缓存有存活时间，过期之后会再写一次。";
        peerReply.authorName = "匿名用户";
        peerReply.authorUid = "someone-else";
        peerReply.official = false;
        peerReply.createdAtEpochMillis = System.currentTimeMillis() - 86_400_000L;
        peerReply.helpfulCount = 3;
        thread.replies.add(peerReply);

        thread.hasOfficialReply = true;
        return thread;
    }

    private static List<ForumPost> trim(List<ForumPost> posts, int limit) {
        while (posts.size() > limit) {
            posts.remove(posts.size() - 1);
        }
        return posts;
    }

    private static <T> LiveData<T> live(T value) {
        MutableLiveData<T> data = new MutableLiveData<>();
        data.postValue(value);
        return data;
    }
}
