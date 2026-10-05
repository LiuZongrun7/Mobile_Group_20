package com.mobilegroup20.modelpilot.contract.model;

/**
 * 一条用量记录。<b>整个系统唯一的事实来源，导入之后只读。</b>
 *
 * <p><b>粒度有两种</b>，取决于数据从哪来：<b>逐条调用</b>（解析本地日志得到，
 * 一次调用一行）和 <b>时间桶</b>（从 provider 的用量 API 拉取得到，一个桶一行——
 * 那些接口只给聚合值，不给请求 id）。两者的列完全一样，区别只在 {@link #id}
 * 怎么算出来，以及桶来源的行<b>没有会话信息、做不了按 session 分析</b>。
 * 详见 {@code docs/DATA_SOURCES.md}。
 *
 * <p>为什么不直接存每天一个汇总行：费率会被纠正、要支持按 session 分析、agent 还要
 * 回答「为什么这周涨了」——这些都得下钻回单次调用。每日汇总是从这张表滚出来的
 * 派生数据，删了能重算；这张表删了就没了。
 *
 * <p>字段顺序和服务端文档、Room 表列一一对应，改动要同步
 * {@code docs/CONTRACTS.md}。
 */
public class UsageCall {

    /**
     * 去重用的稳定 id。<b>必须能由记录内容本身算出来</b>，不能是随机数或自增——
     * 否则同一份数据进来两次会变成两倍的用量。两种粒度各有各的算法，<b>且必须带前缀</b>：
     * <pre>
     *   call:&lt;provider&gt;:&lt;请求 id&gt;:&lt;yyyy-MM-dd&gt;
     *   bucket:&lt;provider&gt;:&lt;桶起点毫秒&gt;:&lt;model&gt;:&lt;projectId&gt;
     * </pre>
     * 桶接口没有请求 id，但同一个桶重拉两次算出来的是同一个值，去重照样成立。
     *
     * <p><b>前缀是必需的，不是装饰</b>：本类没有别的字段能说明这行是桶还是调用，
     * 而「这行能不能做按会话分析」必须能算出来——带 {@code bucket:} 的不行。
     *
     * <p>本地用唯一索引 + 插入冲突忽略来落地，见 {@code docs/CONTRACTS.md}；
     * 前缀的来由见 {@code docs/DATA_SOURCES.md} §2。
     */
    public String id;

    /** 账号隔离用。所有查询都带这个条件，服务端每次读也按它过滤。 */
    public String uid;

    public Provider provider;

    /** 模型名，如 "gpt-5"、"mimo-v2.6-pro"、"deepseek-reasoner"。定价的最小单位。 */
    public String model;

    /** 调用发生的时刻，UTC 毫秒。不要只存「日期字符串」——日结算是按时区算出来的，
     *  存了时刻才能换时区、才能重算，见 {@link com.mobilegroup20.modelpilot.util.TimeUtils}。
     *  时间桶来源的行填<b>桶的起点</b>；桶不会跨天（最宽 1 天、最窄 1 分钟），日汇总不受影响。 */
    public long startedAtEpochMillis;

    public long input;
    public long cacheRead;
    public long cacheWrite;
    public long output;

    /**
     * 来源<b>直接给出</b>的成本，微美元（见 {@code data/Money}）。
     *
     * <p><b>为什么需要它。</b>有些来源自带金额，而且那个金额不是「单价 × 数量」
     * 还原得出来的。DeepSeek 的用量导出就是例子：同一天、同一个模型、同一种 token
     * 会按峰谷两个价分成两行，而我们按 {@code (天, 提供方, 模型)} 汇总，
     * 落进一行之后就没法还原了。与其去猜一个折算价，不如把来源给的数直接带下来。
     *
     * <p><b>null 表示来源没给</b>，这时才回落到按 {@link PricingRate} 现算。
     * 别用 0 表示「没有」——0 是一笔金额，会被当成真的花了 0 元加进汇总。
     */
    public Long costMicros;

    /**
     * 上面那笔钱的<b>原始币种</b>，如 {@code "CNY"}、{@code "USD"}；null 表示来源没给金额。
     *
     * <p>{@link #costMicros} 一律已经折成微美元，所以这个字段<b>不参与计算</b>，
     * 只用于在界面上说明「这个数是从人民币账单折过来的」。
     */
    public String costCurrency;

    /**
     * 原始金额，微单位，币种见 {@link #costCurrency}。<b>只为留痕，不参与计价。</b>
     *
     * <p>留着是为了能回答「为什么我们的数和账单差几分钱」——没有它就只能
     * 重新去导一次账单来对。折算用的汇率是个常量（见 {@code data/Money}），
     * 对不上账的时候，靠这个字段才分得清是汇率的问题还是解析的问题。
     */
    public Long nativeCostMicros;

    public Source source;

    /**
     * 这次调用是 **Auto 挑的**还是**用户手动指定**的（`"AUTO"` / `"MANUAL"`）。
     * 可空：导入的记录没有这个概念（null = 不知道，**不是 MANUAL**）。
     */
    public String route;

    /** 属于哪条对话；导入的记录是 null。 */
    public String chatId;

    /** 这次跑了哪些工具（逗号分隔）；没有是空串，导入的记录是 null。 */
    public String toolCalls;

    /** 算钱用的那一版费率；算不出价时是 null。 */
    public String rateVersion;

    /**
     * **一次用户提问的任务号**：这一轮里发生的所有调用（回答、压缩、将来的工具）
     * 共用同一个 `taskId`。
     *
     * <p>为什么需要它：大纲的"任务级成本"问的不是"这次调用花了多少"，
     * 而是"把这份 PDF 总结完一共花了多少"——那可能是一轮里的一次摘要 + 一次回答
     * （将来还有工具）。只有这一列能把它们串成一笔。
     *
     * <p>可空：导入的记录没有这个概念（null = 不知道）。
     */
    public String taskId;

    /**
     * Auto 挑这次模型时给出的那句理由（原样存下来）。
     *
     * <p>大纲 §6 要求"save the selected route, policy version and reason"：
     * 只在界面上闪一下的理由，重启就没了，事后没人能回答"当时为什么挑了它"。
     * 手动选的、导入的记录都是 null（没有理由可言）。
     */
    public String reason;

    /**
     * 做这次选择用的是哪一版路由规则（例如 `"capability-then-price-v1"`）。
     *
     * <p>规则改了之后，老记录要能看出"当时是按哪版规则选的"——否则
     * "Auto 怎么挑了这么个模型"这个问题永远只能靠猜。
     */
    public String policy;

    /**
     * 这次调用是干什么的（`"ANSWER"` / `"COMPRESS"` / `"TOOL"`）。
     *
     * <p>**和 {@link #route} 是两件事**：route 说的是"模型是谁挑的"，
     * kind 说的是"这次调用在任务里扮演什么角色"。Insights 要按 kind 拆开显示，
     * 否则用户会以为"压缩的钱"是回答花的。
     *
     * <p>可空：导入的记录分不出这些角色（null = 不知道），**不填 ANSWER**——
     * 那等于把不知道说成知道。
     */
    public String kind;

    public UsageCall() {
    }

    /**
     * 这条记录是真实导入的，还是演示用的样例数据。
     *
     * <p>大纲的 Alpha 验收项明确要求「把真实日志和样例数据分开」，所以这个标记
     * 必须落在记录上，不能靠界面临时判断。样例数据<b>不计入</b>游戏资源和预算。
     */
    public enum Source {
        /** **本 App 自己发出去的一次模型调用**（2026-09-30 加）。
         *
         * <p>这是新主线唯一的用量来源：用户填自己的 key、我们直连各家，
         * 每次调用落一行。设计稿里那个 "App 内 / 外部" 的区分就是它和 IMPORTED 的区别——
         * 一个是"我们看着发生的"，一个是"用户导进来的记录"，可信度与口径都不一样。 */
        APP,
        /** 用户导入的账单/日志（来自别的工具或平台导出）。 */
        IMPORTED,
        /** 演示用的样本数据，**永远不该出现在真实统计里**。 */
        SAMPLE
    }
}
