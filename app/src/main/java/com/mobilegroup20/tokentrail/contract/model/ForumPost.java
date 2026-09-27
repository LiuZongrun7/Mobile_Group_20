package com.mobilegroup20.tokentrail.contract.model;

/**
 * 论坛帖子。官方帖和用户帖共用这一个结构，用 {@link #source} 区分。
 *
 * <p><b>论坛是跨账号的公共内容，用量记录是 per-uid 的私有内容</b>，这两类的
 * 服务端的可见性校验完全不同：帖子可以被任何登录用户读，用量只能被本人读。
 * 别把两者放进同一处，否则可见性校验没法写。
 */
public class ForumPost {

    /** Durable server images, in display order. Local content URIs never go on the wire. */
    public java.util.List<ForumImage> images = new java.util.ArrayList<>();

    public long likeCount;
    public boolean likedByMe;
    public long commentCount;

    public String id;

    public String title;

    public String body;

    public Source source;

    /**
     * 帖子关联的模型，如 "mimo-v2.6-pro"。没有明确指向某个模型时留 null。
     * agent 回答「换模型能省多少」时会按这个字段筛帖。
     */
    public String modelTag;

    /** 发帖时刻，UTC 毫秒。 */
    public long createdAtEpochMillis;

    public String authorName;

    /**
     * 发帖人的 uid。官方帖留 null 或固定值。
     *
     * <p>只有 {@code source == COMMUNITY} 且 {@code authorUid == 当前登录用户}
     * 的帖子才算「我的帖子」，见 {@code MyThreads} 工具。
     */
    public String authorUid;

    /** 被标记为有用的次数。排序的原料之一。 */
    public long helpfulCount;

    public long viewCount;

    /**
     * 热度分。由论坛那一侧算好写在这里，<b>客户端和 agent 都不要自己算</b>：
     * 排序公式只应该有一处实现，否则界面上一套顺序、agent 嘴里另一套。
     * 公式本身写在 {@code docs/CONTRACTS.md}。
     */
    public double rankScore;

    public ForumPost() {
    }

    /**
     * 官方帖还是社区帖。
     *
     * <p>agent 引用两类帖子的说法不一样：官方帖可以当作价格/版本的事实来源，
     * 社区帖只能当经验分享，回答里要分清楚（大纲 §5 的「答案来自证据」）。
     */
    public enum Source {
        OFFICIAL,
        COMMUNITY
    }
}
