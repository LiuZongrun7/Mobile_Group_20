package com.mobilegroup20.tokentrail.contract.tool;

import java.util.ArrayList;
import java.util.List;

import com.mobilegroup20.tokentrail.contract.model.ForumPost;
import com.mobilegroup20.tokentrail.contract.model.ForumReply;

/**
 * {@link AgentTool#GET_MY_THREADS} 的返回值：本人发过的帖子和收到的回复。
 *
 * <p>这是论坛的第三个桶，和前两个（官方帖、热帖）分开成两个工具，原因是<b>可见性
 * 不一样</b>：官方帖和热帖是跨账号的公共内容，谁都能读；这个只有本人能读。
 * 混在一个工具里，服务端就没法用一条规则把权限卡死。
 *
 * <p>uid 不从参数传，由服务端从会话 token 里取——工具签名里看不到 uid，
 * 也就没有「传别人的 uid」这条路。
 */
public class MyThreads {

    public final List<Thread> threads = new ArrayList<>();

    /** 查询区间，格式 {@code yyyy-MM-dd}。为 null 表示不限时间。 */
    public String since;

    public long asOfEpochMillis;

    public MyThreads() {
    }

    /** 一个帖子连同它下面的回复。 */
    public static class Thread {

        public ForumPost post;

        /** 回复按时间正序，方便 agent 叙述「后来有人说……」。 */
        public final List<ForumReply> replies = new ArrayList<>();

        /** 回复里有没有官方账号的。有的话 agent 应该优先引述。 */
        public boolean hasOfficialReply;

        public Thread() {
        }
    }
}
