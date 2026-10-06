package com.mobilegroup20.modelpilot.data.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次导入的真实结果。<b>只报真的写进去了什么</b>，不报"文件里有多少"——
 * 那两件事在 {@code ADD_ONLY} 策略下必然不同，而用户要的是前者。
 *
 * <p>分成「新增 / 覆盖 / 跳过」三档而不是一个总数：这三档对用户的意思完全不同——
 * 新增是"数据回来了"，覆盖是"本机那份被换掉了"（他可能刚刚还在改那条），
 * 跳过是"这条我已经有了"。合成一个数就没法解释「为什么我只看到 3 条」。
 */
public final class ImportSummary {

    /** 写进去的对话：新建的 / 覆盖的。 */
    public final int conversationsAdded;
    public final int conversationsReplaced;

    /**
     * 因为本机已有同 id 而<b>整条跳过</b>的对话（只可能在 {@link MergePolicy#ADD_ONLY} 下出现）。
     */
    public final int conversationsSkipped;

    /**
     * 交给存储的消息 / 记忆条数。
     *
     * <p>它可能<b>大于</b>最后真正新增的行数：冲突忽略的策略下，本机已有的同 id 消息
     * 写了也是白写（那正是我们要的——重复的那条不该盖掉本机的）。这里刻意不把它
     * 说成"新增了 N 条消息"：没有一次查询能便宜地告诉我们 N 到底是多少，
     * 而报一个我们没量过的数，比报一个偏大的数更糟。
     */
    public final int messagesWritten;
    public final int memoriesWritten;

    /** 用量：新写进去的 / 因为 id 相同被跳过的。 */
    public final int usageWritten;
    public final int usageSkippedDuplicates;

    /** 读文件时就被丢掉的行（解析不了、缺 id），一条一句人话。 */
    public final List<String> problems;

    /** 这次用的是哪条策略——结果里必须记着，否则事后没人能解释为什么本机的被换了。 */
    public final MergePolicy policy;

    public ImportSummary(MergePolicy policy, int conversationsAdded, int conversationsReplaced,
                         int conversationsSkipped, int messagesWritten, int memoriesWritten,
                         int usageWritten, int usageSkippedDuplicates, List<String> problems) {
        this.policy = policy;
        this.conversationsAdded = conversationsAdded;
        this.conversationsReplaced = conversationsReplaced;
        this.conversationsSkipped = conversationsSkipped;
        this.messagesWritten = messagesWritten;
        this.memoriesWritten = memoriesWritten;
        this.usageWritten = usageWritten;
        this.usageSkippedDuplicates = usageSkippedDuplicates;
        this.problems = problems == null ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new ArrayList<>(problems));
    }

    public int conversationsWritten() {
        return conversationsAdded + conversationsReplaced;
    }

    /** 什么都没写进去（文件是空的，或者本机全都有且选了「只加新的」）。 */
    public boolean wroteNothing() {
        return conversationsWritten() == 0 && messagesWritten == 0
                && memoriesWritten == 0 && usageWritten == 0;
    }

    /** 落盘之后弹给用户的那一句。 */
    public String describe() {
        if (wroteNothing()) {
            // 「一条都没写」和「跳过了 N 条」不是一回事：后者听起来像做成了什么。
            // 用户要的是"数据回来了没有"，所以这一句必须是那句老实话。
            return "Nothing new in this file";
        }
        List<String> parts = new ArrayList<>();
        if (conversationsAdded > 0) {
            parts.add("added " + conversationsAdded + " conversations");
        }
        if (conversationsReplaced > 0) {
            parts.add("replaced " + conversationsReplaced + " conversations");
        }
        if (conversationsSkipped > 0) {
            parts.add("kept " + conversationsSkipped + " already on this phone");
        }
        if (messagesWritten > 0) {
            parts.add(messagesWritten + " messages restored");
        }
        if (memoriesWritten > 0) {
            parts.add(memoriesWritten + " summaries");
        }
        if (usageWritten > 0) {
            parts.add("added " + usageWritten + " usage records");
        }
        if (usageSkippedDuplicates > 0) {
            parts.add(usageSkippedDuplicates + " usage records already recorded");
        }
        return String.join(" · ", parts);
    }
}
