package com.mobilegroup20.modelpilot.chat;

import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.PricingSource;
import com.mobilegroup20.modelpilot.data.local.UsageCallDao;
import com.mobilegroup20.modelpilot.data.local.UsageCallEntity;

import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

/**
 * 本机账本的写入端：**一次调用落一行**（`docs/CHAT_ENGINE.md` §4）。
 *
 * <p>它把三件事串起来：{@link UsageRecorder} 算金额与费率版本 →
 * {@link UsageCallEntity#fromModel} 变成表行 → DAO 插进去。
 * 界面侧（Insights）只读，永远不写。
 *
 * <p><b>`uid` 为什么是一个常量而不是登录账号的 id。</b>账本以前是"服务端结算给某个账号的用量"，
 * 所以每行都挂 uid。现在调用是这台手机自己发出去的，花的是这台手机上的 key——
 * 于是"花了多少"和"登录了没有"**没有任何关系**。用账号 id 的话会出现两种都不对的后果：
 * 没登录时记不下来（那正是最该记账的时候），登录之后同一笔消费换个 uid 又成了两笔。
 * 所以本地这一路统一用 {@link #LOCAL_UID}；导入进来的历史记录仍然用它们自己的 uid，
 * 两者靠 {@code source}（APP / IMPORTED）分得开。
 */
public final class CallLedger {

    /** 本机发起的调用统一挂这个 uid，理由见类注释。 */
    public static final String LOCAL_UID = "local";

    private final UsageRecorder recorder;
    private final UsageCallDao dao;

    public CallLedger(PricingSource pricing, UsageCallDao dao) {
        this.recorder = new UsageRecorder(pricing);
        this.dao = dao;
    }

    /**
     * 记一次成功的调用。
     *
     * <p><b>只在拿到真实 usage 时调用</b>：上游没给 usage 时我们不知道花了多少，
     * 记一行 0 会把"不知道"洗成"没花钱"（`CONTRACTS.md` §4）。少记一行，
     * Insights 上会少一次调用——那是**可见的**缺失，比一个错数字好。
     *
     * @param route Auto 挑的还是用户手动指定的（Insights 要按它算 Auto 占比）
     * @return 真写进去的那一行；去重命中或 provider 认不出来时返回 null
     */
    public UsageCall record(String providerId, String modelId, UsageRecorder.Route route,
                            String chatId, com.mobilegroup20.modelpilot.contract.model.TokenBundle tokens,
                            long startedAtEpochMillis) {
        if (tokens == null) {
            return null;
        }
        String day = com.mobilegroup20.modelpilot.util.TimeUtils.dayOf(startedAtEpochMillis);
        UsageCall call = recorder.record(callId(providerId, startedAtEpochMillis), providerId,
                modelId, route, chatId, null, tokens, startedAtEpochMillis, day);
        if (call == null) {
            return null;
        }
        UsageCallEntity entity = UsageCallEntity.fromModel(call, LOCAL_UID);
        if (entity == null) {
            return null;
        }
        dao.insertAll(Collections.singletonList(entity));
        return call;
    }

    /**
     * 去重键。形状照 `UsageCall.id` 的约定：
     * `call:<provider>:<毫秒>:<随机>`。
     *
     * <p>为什么每一截都要：provider 让人一眼看出是哪家的账；毫秒让同一秒内的多次调用
     * 也有序；随机那截防的是"同一毫秒里发了两次"（重试或连点）——没有它，
     * 第二次会被主键挡掉，而那次调用是真花了钱的。
     */
    private static String callId(String providerId, long at) {
        return String.format(Locale.US, "call:%s:%d:%s", providerId, at,
                UUID.randomUUID().toString().substring(0, 8));
    }
}
