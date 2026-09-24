package com.mobilegroup20.tokentrail.contract.tool;

/**
 * 建议 agent 能调用的只读工具。五个，全部只读。
 *
 * <p><b>为什么是「只读」这件事本身要写进契约。</b>agent 能查用量、查预算、比价格、
 * 读论坛，但碰不到任何会花钱或会改动编码 agent 的操作。有了这个枚举，服务端在分发
 * 工具调用时就是白名单匹配——名单外的一律拒绝，不用靠提示词劝阻。
 *
 * <p><b>为什么不做「搜索用量」这种自由查询。</b>让模型自己捞原始记录再算数，它迟早
 * 算错还当成事实讲出来。所以数字类的问题一律走聚合工具：算好的数字 + 口径一起交给
 * 它，模型只负责解释。真正需要检索的只有论坛文本，那部分在
 * {@link #GET_FORUM_HIGHLIGHTS} 里。
 */
public enum AgentTool {

    /** 用量汇总：按天 / 模型 / 提供方分组。 */
    GET_USAGE_SUMMARY("getUsageSummary",
            "按给定日期区间和分组维度查用量汇总。返回四类 token 和成本，"
                    + "并说明区间内哪些天没有记录。"),

    /** 预算状态：上限、已花、还剩多少、有没有越过警告线。 */
    GET_BUDGET_STATUS("getBudgetStatus",
            "查某个月的预算上限、已花金额和是否越过警告线。"),

    /** 跨提供方 / 模型的成本对比。 */
    COMPARE_AGENT_COSTS("compareAgentCosts",
            "在同一区间内对比各提供方与模型的成本、token 用量和单次调用均价。"
                    + "只比成本和用量，不评价回答质量。"),

    /** 官方帖 + 热帖。跨账号的公共内容。 */
    GET_FORUM_HIGHLIGHTS("getForumHighlights",
            "读官方发布的模型与价格公告，以及社区里的热门帖子。"),

    /** 本人的帖子及回复。只有本人能查。 */
    GET_MY_THREADS("getMyThreads",
            "查当前用户自己发过的帖子以及收到的回复。");

    /**
     * 函数名，必须和模型 API 里注册的名字完全一致。
     * 用常量而不是让各处自己拼字符串，避免出现 getUsageSummary 和 get_usage_summary
     * 两个名字都「能跑」的情况。
     */
    public final String functionName;

    /**
     * 给模型看的工具说明，<b>就是 function calling 里的 description</b>。
     * 写在这里而不是散在服务端，是为了让「工具能做什么」这件事只有一份定义。
     */
    public final String description;

    AgentTool(String functionName, String description) {
        this.functionName = functionName;
        this.description = description;
    }

    /** 按函数名反查。服务端分发模型发来的工具调用时用。 */
    public static AgentTool fromFunctionName(String functionName) {
        for (AgentTool tool : values()) {
            if (tool.functionName.equals(functionName)) {
                return tool;
            }
        }
        return null;
    }
}
