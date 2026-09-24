package com.mobilegroup20.tokentrail.contract.model;

/**
 * 一次模型调用的原始记录。<b>整个系统唯一的事实来源，导入之后只读。</b>
 *
 * <p>为什么不直接存每天一个汇总行：费率会被纠正、要支持按 session 分析、agent 还要
 * 回答「为什么这周涨了」——这些都得下钻回单次调用。每日汇总是从这张表滚出来的
 * 派生数据，删了能重算；这张表删了就没了。
 *
 * <p>字段顺序和 Firestore 文档、Room 表列一一对应，改动要同步
 * {@code docs/CONTRACTS.md}。
 */
public class UsageCall {

    /**
     * 去重用的稳定 id。<b>必须能由日志内容本身算出来</b>（提供方 + 请求 id + 日期），
     * 不能是随机数或自增——否则同一份日志导入两次会变成两倍的用量。
     * 本地用唯一索引 + 插入冲突忽略来落地，见 {@code docs/CONTRACTS.md}。
     */
    public String id;

    /** 账号隔离用。所有查询都带这个条件，Firestore 安全规则也按它写。 */
    public String uid;

    public Provider provider;

    /** 模型名，如 "gpt-5"、"glm-4.6"、"deepseek-reasoner"。定价的最小单位。 */
    public String model;

    /** 调用发生的时刻，UTC 毫秒。不要只存「日期字符串」——日结算是按时区算出来的，
     *  存了时刻才能换时区、才能重算，见 {@link com.mobilegroup20.tokentrail.util.TimeUtils}。 */
    public long startedAtEpochMillis;

    public long input;
    public long cacheRead;
    public long cacheWrite;
    public long output;

    public Source source;

    public UsageCall() {
    }

    /**
     * 这条记录是真实导入的，还是演示用的样例数据。
     *
     * <p>大纲的 Alpha 验收项明确要求「把真实日志和样例数据分开」，所以这个标记
     * 必须落在记录上，不能靠界面临时判断。样例数据<b>不计入</b>游戏资源和预算。
     */
    public enum Source {
        IMPORTED,
        SAMPLE
    }
}
