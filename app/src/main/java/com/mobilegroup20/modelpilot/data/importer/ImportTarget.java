package com.mobilegroup20.modelpilot.data.importer;

import com.mobilegroup20.modelpilot.chat.local.ChatEntity;
import com.mobilegroup20.modelpilot.chat.local.MemoryEntity;
import com.mobilegroup20.modelpilot.chat.local.MessageEntity;
import com.mobilegroup20.modelpilot.chat.local.ProjectEntity;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.util.List;

/**
 * 导入要读/写的那几样东西，落在存储上的那一半。
 *
 * <p>和 {@code data/export} 里的 {@code ExportSource} 是同一套路数，理由也一样：
 * 合并逻辑里真正容易写错的部分（哪些对话该覆盖、缺字段该不该补空、同 id 的消息怎么办）
 * <b>全都不需要真数据库就能测</b>。夹一层接口之后，那些规矩可以用 JUnit 直接钉住，
 * 不用起模拟器，也不用为了测一个"重复 id 怎么办"去建 Room 库。
 *
 * <p>实现（{@link RoomImportTarget}）里<b>只许有查询和写入，不许有判断</b>：
 * 一旦"要不要写"这条判断漏进实现里，它就再也没法被单测覆盖到了。
 *
 * <p>全部方法<b>同步</b>，调用方负责放到后台线程上——理由同 {@code ExportSource}：
 * 一半同步一半异步只会让"导入到一半用户删了条对话会怎样"变成没人说得清的问题。
 */
public interface ImportTarget {

    /**
     * 本机已有的对话 id。<b>先一次性拿出来</b>，而不是每写一条问一次：
     * 合并要么全按同一份"本机现状"来判，要么就会在两批之间看到变化的现状。
     */
    List<String> existingChatIds();

    void writeProject(ProjectEntity project);

    /**
     * 写一条对话。实现用 REPLACE 语义（整行覆盖）：{@link ChatEntity} 是完整的一行，
     * 没有"只改其中一列"的写法。
     */
    void writeChat(ChatEntity chat);

    /** 写一条消息，冲突（同 id）时<b>忽略</b>——重复的那条不该覆盖本机已有的。 */
    void writeMessage(MessageEntity message);

    /** 写一条记忆摘要，冲突时忽略，同 {@link #writeMessage}。 */
    void writeMemory(MemoryEntity memory);

    /**
     * 写一批用量记录。
     *
     * <p>去重<b>完全靠主键</b>（{@code UsageCallEntity} 的 id 是由记录内容算出来的，
     * 见 {@code UsageCall#id}）：主键撞车的那些不写、也不改。这条路走的是
     * {@code RoomUsageRepository.importCalls}，它顺手把受影响那几天的日汇总重滚一遍。
     *
     * <p>返回真正写进去的条数（不含重复被跳过的那些）。重复多少条由仓库那边数，
     * 不在这里再数一遍。
     */
    int writeUsage(List<UsageCallEntity> calls);
}
