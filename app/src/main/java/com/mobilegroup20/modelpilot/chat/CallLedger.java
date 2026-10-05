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
     * @param taskId 这一轮提问的任务号：回答与压缩共用一个，用来算"一次提问的总成本"
     * @param kind 这次调用是干什么的（回答 / 压缩 / 工具）
     * @return 真写进去的那一行；去重命中或 provider 认不出来时返回 null
     */
    public UsageCall record(String providerId, String modelId, UsageRecorder.Route route,
                            String chatId, String taskId, UsageRecorder.Kind kind,
                            com.mobilegroup20.modelpilot.contract.model.TokenBundle tokens,
                            long startedAtEpochMillis) {
        return record(providerId, modelId, route, chatId, taskId, kind, tokens,
                startedAtEpochMillis, null);
    }

    /**
     * 同上，并带上 Auto 的理由与规则版本（手动选的传 null）。
     *
     * <p>存理由是大纲 §6 点名要的："save the selected route, policy version and reason"。
     * 只在界面上闪一下的话，重启之后就没人能回答"当时为什么挑了它"。
     */
    public UsageCall record(String providerId, String modelId, UsageRecorder.Route route,
                            String chatId, String taskId, UsageRecorder.Kind kind,
                            com.mobilegroup20.modelpilot.contract.model.TokenBundle tokens,
                            long startedAtEpochMillis, String reason) {
        if (tokens == null) {
            return null;
        }
        String day = com.mobilegroup20.modelpilot.util.TimeUtils.dayOf(startedAtEpochMillis);
        // **自定义端点记成 CUSTOM**：账本的 provider 列是有取值约束的枚举，
        // 而 `UsageRecorder` 对认不出的 provider 是"整行不记"——自定义端点的调用
        // 会因此凭空消失（六家里的 GLM/Kimi/Seed/Anthropic 也踩过同一个坑，
        // 见 `Provider` 枚举里那段注释）。
        String ledgerProviderId = isCustom(providerId)
                ? com.mobilegroup20.modelpilot.contract.model.Provider.CUSTOM.name() : providerId;
        UsageCall call = recorder.record(callId(providerId, startedAtEpochMillis), ledgerProviderId,
                modelId, route, chatId, taskId, kind, null, tokens, startedAtEpochMillis, day,
                reason, reason == null ? null : AutoRouter.POLICY_VERSION);
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
     * 自定义端点的 id 前缀（和 `ProviderKeys.saveCustom` 用的是同一个约定）。
     *
     * <p>为什么要靠前缀认：账本那一列是枚举，而枚举里只能有一个 `CUSTOM`；
     * 用户加了三个自定义端点时，只有前缀能告诉我们"这三个都不是内置那六家"。
     */
    public static final String CUSTOM_PREFIX = "custom-";

    /** 这个 providerId 是不是用户自己加的端点。 */
    public static boolean isCustom(String providerId) {
        return providerId != null && providerId.startsWith(CUSTOM_PREFIX);
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
