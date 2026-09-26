package com.mobilegroup20.tokentrail.contract.model;

/**
 * 帖子下面的一条回复。
 *
 * <p>「我的帖子 + 相关回复」是论坛那一侧要提供的第三个桶（前两个是官方帖和热帖）。
 * 回复跟帖子分开存：一个帖子可能几十条回复，塞进帖子文档里会在服务端撞上
 * 单文档 1MB 的上限，而且每次读帖子都要把回复全拖下来。
 */
public class ForumReply {

    public String id;

    /** 所属帖子的 id。 */
    public String postId;

    public String body;

    public String authorName;

    /** 回复者的 uid，用于判断这条是不是自己回的。 */
    public String authorUid;

    /**
     * 这条回复是不是官方账号发的。
     *
     * <p>单独一个布尔值而不看 {@link #authorUid}：官方帖的「官方」是账号属性，
     * 判断逻辑放一处，agent 引用时才知道该说「官方回复」还是「网友回复」。
     */
    public boolean official;

    public long createdAtEpochMillis;

    /** 被标记为有用的次数。 */
    public long helpfulCount;

    public ForumReply() {
    }
}
