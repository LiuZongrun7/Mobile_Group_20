package com.mobilegroup20.tokentrail.contract.tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次查询「实际看到了哪些天、缺了哪些天」。
 *
 * <p>这个类是让「agent 承认自己不知道」变成<b>契约保证</b>的关键，而不是靠提示词
 * 祈祷它老实。任何一个返回数字的工具都必须带上 Coverage，服务端在拼答案时**强制**
 * 把 {@link #daysMissing} 非空这件事写进回答的缺失数据里。
 *
 * <p>没有它会出现什么：用户 9 月 10 号以后没再导入日志，问「这周花了多少」，
 * agent 拿到的区间是 9/1–9/21，但只有 9/1–9/10 有数据。它把缺失当 0，
 * 于是报告「本周比上周降了 50%」。数字算得没错，结论完全是反的。
 */
public class Coverage {

    /** 查询区间的起止，格式 {@code yyyy-MM-dd}，含两端。 */
    public String from;
    public String to;

    /** 区间内实际有记录的天数。 */
    public int daysWithData;

    /**
     * 区间内<b>一条记录都没有</b>的日期，格式 {@code yyyy-MM-dd}。
     *
     * <p>注意区分「没有记录」和「用量为 0」：前者是不知道，后者是确定没花钱。
     * 只有前者才进这个列表。
     */
    public final List<String> daysMissing = new ArrayList<>();

    public Coverage() {
    }

    /** 区间天数是不是都对得上，对不上就说明这段结论要打折扣。 */
    public boolean complete() {
        return daysMissing.isEmpty();
    }
}
