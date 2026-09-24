package com.mobilegroup20.tokentrail.contract.model;

import java.util.ArrayList;
import java.util.List;

import com.mobilegroup20.tokentrail.contract.tool.AgentTool;

/**
 * 建议 agent 的一次回答，连同它凭什么这么答。
 *
 * <p>这个结构是项目「答案来自证据、不编造用量」这句承诺的落点：回答不是一个
 * 字符串，而是「正文 + 证据 + 调了哪些工具 + 哪些数据没有」。界面上要求
 * {@link #evidence} 和 {@link #missingData} 必须能展开看到——藏起来就等于没有。
 */
public class AdviceAnswer {

    /** 给用户看的正文。 */
    public String text;

    /** 支撑这次回答的具体数字和它们的口径。 */
    public final List<EvidenceItem> evidence = new ArrayList<>();

    /** 这次回答实际调用了哪些工具、参数是什么、成没成功。 */
    public final List<ToolCallRecord> toolCalls = new ArrayList<>();

    /**
     * <b>缺什么数据</b>，比如「9 月 12 日没有记录」「o3-mini 查不到费率」。
     *
     * <p>这是硬性要求，不是可选项：某个区间没有记录时，回答必须说明这一点，
     * 而不是把缺失当成 0。留着这个字段，才有可能用一条测试去卡住它——
     * 大纲的验收项里就写了「agent 承认数据缺失」。
     */
    public final List<String> missingData = new ArrayList<>();

    /** 回答自己这次调用花掉的钱，微美元。agent 自身的开销和编码 agent 的用量分开统计。 */
    public long ownCostMicros;

    /** 服务端这次用的模型名。 */
    public String model;

    public long createdAtEpochMillis;

    public AdviceAnswer() {
    }

    /** 一条证据：一个数字，加上它是怎么算出来的。 */
    public static class EvidenceItem {

        /** 数字本身的可读写法，如 "612k tokens" 或 "¥4.21"。 */
        public String display;

        /** 口径说明，如 "9 月 1–21 日，仅计入导入的记录"。 */
        public String basis;

        /** 数据来源，如 "getUsageSummary"。 */
        public String fromTool;

        public EvidenceItem() {
        }

        public EvidenceItem(String display, String basis, String fromTool) {
            this.display = display;
            this.basis = basis;
            this.fromTool = fromTool;
        }
    }

    /** 一次工具调用的流水。agent 面板里可以展开给老师看 function calling 确实发生了。 */
    public static class ToolCallRecord {

        public AgentTool tool;

        /** 调用参数，已经是 JSON 字符串（工具还没定型之前调试用）。 */
        public String argumentsJson;

        /** 结果的简短描述，不存完整结果——完整结果可能很大，也不该进对话历史。 */
        public String resultSummary;

        public boolean ok;

        /** 失败原因，成功时为 null。失败也要记下来，让模型知道这条路走不通。 */
        public String errorMessage;

        public ToolCallRecord() {
        }
    }
}
