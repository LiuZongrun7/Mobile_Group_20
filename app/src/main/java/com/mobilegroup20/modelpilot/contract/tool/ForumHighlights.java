package com.mobilegroup20.modelpilot.contract.tool;

import java.util.ArrayList;
import java.util.List;

import com.mobilegroup20.modelpilot.contract.model.ForumPost;

/**
 * 论坛聚合（`GET /forum/highlights`）的返回值：官方帖 + 社区热帖。
 *
 * <p>官方帖和社区帖混在一个列表里返回，但每条都带 {@link ForumPost#source}。
 * agent 引用时的措辞要跟着变：官方帖可以说「官方公告显示」，社区帖只能说
 * 「有用户反映」。把两类混着说，就等于把网友经验当成官方价格。
 */
public class ForumHighlights {

    /** 帖子按热度从高到低。排序由论坛那一侧算好，这里不重排。 */
    public final List<ForumPost> posts = new ArrayList<>();

    /** 热帖的时间窗，格式 {@code yyyy-MM-dd}。引用时说得出「最近 N 天的热帖」。 */
    public String since;

    /** 这批数据取到的时刻，UTC 毫秒。论坛一直在变，回答要说明是「截至什么时候」。 */
    public long asOfEpochMillis;

    /**
     * 过滤用的模型标签。用户在问某个具体模型时，只返回相关的帖。
     * 为 null 表示没有按模型筛。
     */
    public String modelFilter;

    public ForumHighlights() {
    }
}
