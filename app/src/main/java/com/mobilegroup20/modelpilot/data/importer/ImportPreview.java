package com.mobilegroup20.modelpilot.data.importer;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一份待导入文件的内容摘要，<b>还没写任何东西</b>。
 *
 * <p>导入是一次没有撤销键的写入，所以在它之前必须有一次「你将要拿到什么」的确认。
 * 这份预览就是那句话的全部依据：文件里有多少、本机已经有几条、按选的策略会写进去几条、
 * 以及读的时候丢了几行（{@link ImportBundle#problems}）。
 *
 * <p>它<b>只回答"会怎样"，不回答"要不要"</b>——策略是用户选的（{@link MergePolicy}），
 * 这里只按策略算数字。
 */
public final class ImportPreview {

    public final ImportBundle bundle;

    /** 本机已有的对话 id 与被算作"文件内重复"的 id。 */
    public final Set<String> existingChatIds;
    public final Set<String> duplicateChatIds;

    public ImportPreview(ImportBundle bundle, @Nullable List<String> existingChatIds,
                         @Nullable List<String> duplicateChatIds) {
        this.bundle = bundle;
        this.existingChatIds = immutable(existingChatIds);
        this.duplicateChatIds = immutable(duplicateChatIds);
    }

    /** 文件里带了多少条对话。 */
    public int conversations() {
        return bundle.conversations.size();
    }

    public int messages() {
        return bundle.messageCount();
    }

    public int memories() {
        return bundle.memoryCount();
    }

    public int usageCalls() {
        return bundle.usageCalls.size();
    }

    /** 这些对话里，本机已经有的有几条。 */
    public int alreadyOnDevice() {
        int count = 0;
        for (ImportBundle.Conversation conversation : bundle.conversations) {
            if (existingChatIds.contains(conversation.chat.id)) {
                count++;
            }
        }
        return count;
    }

    /** 按这个策略实际会写进去的对话条数（{@code ADD_ONLY} 时本机已有的不算）。 */
    public int conversationsToWrite(MergePolicy policy) {
        return policy.replacesExisting() ? conversations() : conversations() - alreadyOnDevice();
    }

    /** 会写进去的消息条数——只数那些真会被写的对话。 */
    public int messagesToWrite(MergePolicy policy) {
        int total = 0;
        for (ImportBundle.Conversation conversation : bundle.conversations) {
            if (policy.replacesExisting() || !existingChatIds.contains(conversation.chat.id)) {
                total += conversation.messages.size();
            }
        }
        return total;
    }

    /** 会写进去的摘要条数，算法同 {@link #messagesToWrite}。 */
    public int memoriesToWrite(MergePolicy policy) {
        int total = 0;
        for (ImportBundle.Conversation conversation : bundle.conversations) {
            if (policy.replacesExisting() || !existingChatIds.contains(conversation.chat.id)) {
                total += conversation.memories.size();
            }
        }
        return total;
    }

    /**
     * 这份文件里有没有"什么都不用写"的可能——{@code ADD_ONLY} 且一条新对话都没有。
     * 界面据此把按钮改成一句实话（"Nothing new in this file"），而不是让用户点一个空动作。
     */
    public boolean nothingToAdd() {
        return conversationsToWrite(MergePolicy.ADD_ONLY) == 0
                && messagesToWrite(MergePolicy.ADD_ONLY) == 0
                && usageCalls() == 0;
    }

    /** 文件内自带重复 id：不拦，但要说出来（说明这文件被手工改过或拼接过）。 */
    public boolean hasInternalDuplicates() {
        return !duplicateChatIds.isEmpty();
    }

    public List<String> problems() {
        return bundle.problems;
    }

    /** 预览里那一行「3 conversations · 26 messages · 13 calls」。 */
    public String describeCounts() {
        return bundle.describeCounts();
    }

    /**
     * 用量那一段覆盖的日期范围，给人看的；没有用量就是空串。
     *
     * <p>为什么值得显示：日期范围是这个文件最容易被误读的地方——一份"最近 7 天"的导出
     * 看起来和全量的一样，导进去之后用户才发现少了半年。
     */
    public String describeUsageRange() {
        String first = bundle.firstUsageDay();
        String last = bundle.lastUsageDay();
        if (first == null || last == null) {
            return "";
        }
        return first.equals(last) ? first : first + " → " + last;
    }

    private static Set<String> immutable(@Nullable List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isEmpty()) {
                    out.add(value);
                }
            }
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /** 给界面用的"前几条问题"，避免一屏塞进五十行。 */
    public List<String> firstProblems(int limit) {
        List<String> out = new ArrayList<>();
        for (String problem : problems()) {
            if (out.size() >= limit) {
                break;
            }
            out.add(problem);
        }
        return out;
    }
}
