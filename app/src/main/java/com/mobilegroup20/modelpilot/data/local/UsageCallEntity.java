package com.mobilegroup20.modelpilot.data.local;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.util.TimeUtils;

/**
 * {@code usage_call} 表：导入的原始调用记录，只写一次之后只读。
 *
 * <p><b>去重完全靠主键。</b>{@code id} 是主键（在 SQLite 里就是一个唯一索引），
 * 插入用 {@code OnConflictStrategy.IGNORE}——冲突的那条不写、返回 -1。
 * 不要在这里另写「先查再插」的比对逻辑：那既要多一次查询，又会有竞态。
 * `id` 怎么算见 {@link UsageCall#id}。
 *
 * <p><b>{@link #day} 是冗余列，故意冗余的。</b>它是
 * {@code TimeUtils.dayOf(startedAtEpochMillis)} 的结果，理论上能算出来，但：
 * <ul>
 *   <li>时区换算（Asia/Shanghai）在 SQLite 里写不出来，写出来也没法用索引；</li>
 *   <li>「取某一天的全部调用」是滚汇总时最热的那条查询，没有这一列就得全表扫。</li>
 * </ul>
 * 它由 {@link #fromModel} 一次性填好，之后 {@code startedAtEpochMillis} 不会变，
 * 所以不存在两份数据对不上的问题。
 *
 * <p>索引只建 {@code (uid, day)} 一条，它服务于滚汇总时按天捞原始记录。
 * 文档里提到的 {@code (uid, provider, model, day)} 不需要单独建：
 * {@code daily_usage} 的主键就是那四个字段，按模型维度的查询走那张派生表，
 * 不会回来扫原始表。
 */
@Entity(tableName = "usage_call", indices = {@Index(value = {"uid", "day"})})
public class UsageCallEntity {

    /** 主键，就是 {@link UsageCall#id}。重复导入时靠它挡住。 */
    @PrimaryKey
    @NonNull
    public String id = "";

    @NonNull
    public String uid = "";

    /** 存 {@link Provider#name()}，靠 {@link LocalConverters} 转换。 */
    @NonNull
    public Provider provider = Provider.OPENAI;

    @NonNull
    public String model = "";

    public long startedAtEpochMillis;

    /** 冗余列，{@code TimeUtils.dayOf(startedAtEpochMillis)}，见类注释。 */
    @NonNull
    public String day = "";

    public long input;
    public long cacheRead;
    public long cacheWrite;
    public long output;

    /**
     * 来源直接给出的成本，微美元。{@code null} = 来源没给，滚汇总时回落到按费率算。
     *
     * <p>用包装类型 {@code Long} 而不是 {@code long}：这一列的可空性是<b>有意义的</b>，
     * 「来源没给金额」和「金额是 0」必须分得开。Room 会把 {@code Long} 映射成可空列。
     */
    public Long costMicros;

    /** 原始币种，如 "CNY"。只留痕，不参与计算。 */
    public String costCurrency;

    /** 原始金额，微单位（币种见 {@link #costCurrency}）。只留痕。 */
    public Long nativeCostMicros;

    /** 存 {@link UsageCall.Source#name()}。只有 {@code IMPORTED} 会滚进 daily_usage。 */
    @NonNull
    public UsageCall.Source source = UsageCall.Source.IMPORTED;

    /**
     * 这次调用是 **Auto 挑的**还是**用户手动指定**的（`"AUTO"` / `"MANUAL"`）。
     *
     * <p>设计稿的 Insights 要显示 "Auto 77%" 这个占比，靠的就是这一列。
     * 可空：导入的记录没有这个概念（`null` = 不知道），**不填 0 也不填 MANUAL**——
     * 那等于把"不知道"说成"用户手动选的"。
     */
    @ColumnInfo(name = "route")
    public String route;

    /** 属于哪条对话。导入的记录没有对话，是 null。 */
    @ColumnInfo(name = "chat_id")
    public String chatId;

    /** 这次调用跑了哪些工具（逗号分隔）。没有就是空串；导入的记录是 null（不知道）。 */
    @ColumnInfo(name = "tool_calls")
    public String toolCalls;

    /** 算钱用的是哪一版费率（价目表按生效日期分版）。算不出价时是 null。 */
    @ColumnInfo(name = "rate_version")
    public String rateVersion;

    /**
     * 这一轮提问的任务号：回答、压缩（将来还有工具）共用一个。
     *
     * <p>可空，导入的记录是 null。**加这一列就是为了"任务级成本"**——
     * 没有它，Insights 只能回答"这个月花了多少"，回答不了
     * "把这份 PDF 总结完花了多少"（那可能包含一次摘要 + 一次回答）。
     */
    @ColumnInfo(name = "task_id")
    public String taskId;

    /** `"ANSWER"` / `"COMPRESS"` / `"TOOL"`；导入的记录是 null（不知道）。 */
    @ColumnInfo(name = "kind")
    public String kind;

    /** Room 要一个无参构造。 */
    public UsageCallEntity() {
    }

    /**
     * 契约模型 → 表行。
     *
     * <p><b>账号从参数来，不从记录里来。</b>{@code call.uid} 是日志内容的一部分，
     * 而日志是外部输入——信它就等于允许一份日志把自己写成别人的用量。所以
     * uid 由调用方（拿到的是已经过认证的那个 uid）显式传进来，
     * 这个方法只认参数，{@code call.uid} 在这里被忽略。
     *
     * <p>返回 null 表示这条记录不该入库：uid / id / model 缺失或日期不合法。
     * <b>不要在这里抛异常</b>——调用方要把它计进 {@code ImportResult.rejected}
     * 并继续处理剩下的记录，一条脏数据不该让整批导入失败。
     */
    public static UsageCallEntity fromModel(UsageCall call, String uid) {
        if (uid == null || uid.isEmpty()) {
            return null;
        }
        if (call == null || call.id == null || call.id.isEmpty()) {
            return null;
        }
        if (call.model == null || call.model.isEmpty() || call.provider == null) {
            return null;
        }
        if (call.startedAtEpochMillis <= 0L) {
            return null;
        }
        String day = TimeUtils.dayOf(call.startedAtEpochMillis);
        if (!TimeUtils.isValidDay(day)) {
            return null;
        }

        UsageCallEntity e = new UsageCallEntity();
        e.id = call.id;
        e.uid = uid;
        e.provider = call.provider;
        e.model = call.model;
        e.startedAtEpochMillis = call.startedAtEpochMillis;
        e.day = day;
        e.input = call.input;
        e.cacheRead = call.cacheRead;
        e.cacheWrite = call.cacheWrite;
        e.output = call.output;
        e.costMicros = call.costMicros;
        e.costCurrency = call.costCurrency;
        e.nativeCostMicros = call.nativeCostMicros;
        e.source = call.source == null ? UsageCall.Source.IMPORTED : call.source;
        e.route = call.route;
        e.chatId = call.chatId;
        e.toolCalls = call.toolCalls;
        e.rateVersion = call.rateVersion;
        e.taskId = call.taskId;
        e.kind = call.kind;
        return e;
    }

    /** 表行 → 契约模型。 */
    public UsageCall toModel() {
        UsageCall call = new UsageCall();
        call.id = id;
        call.uid = uid;
        call.provider = provider;
        call.model = model;
        call.startedAtEpochMillis = startedAtEpochMillis;
        call.input = input;
        call.cacheRead = cacheRead;
        call.cacheWrite = cacheWrite;
        call.output = output;
        call.costMicros = costMicros;
        call.costCurrency = costCurrency;
        call.nativeCostMicros = nativeCostMicros;
        call.source = source;
        call.route = route;
        call.chatId = chatId;
        call.toolCalls = toolCalls;
        call.rateVersion = rateVersion;
        call.taskId = taskId;
        call.kind = kind;
        return call;
    }
}
