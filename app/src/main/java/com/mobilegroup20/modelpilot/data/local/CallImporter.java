package com.mobilegroup20.modelpilot.data.local;

import androidx.annotation.Nullable;

import com.mobilegroup20.modelpilot.contract.model.DailyUsage;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;
import com.mobilegroup20.modelpilot.data.PricingSource;
import com.mobilegroup20.modelpilot.data.repository.UsageRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 往账本里写一批调用记录：<b>校验 → 主键去重 → 重滚受影响那几天的日汇总</b>。
 *
 * <p><b>同步、一个事务、纯数据层。</b>这段逻辑原先在 {@code RoomUsageRepository.importCalls}
 * 里，2026-10-06 抽出来是因为它有了第二个调用方：导入导出文件那一路
 * （{@code data/importer}）需要"写完了立刻知道写进去几条"，而原来那个方法的返回是
 * {@link androidx.lifecycle.LiveData}——异步的，导入那边拿到不了一个能写进结果里的数字。
 *
 * <p>抽出来之后，两条路走的是同一份代码，而三条规矩仍然只有一处：
 * <ol>
 *   <li><b>只有 {@code IMPORTED} 进汇总</b>（那是 {@link DailyRollup} 的判断）；</li>
 *   <li><b>已结算的行跳过不写</b>——{@code settled = true} 意味着那天的 token 已经换成
 *       游戏资源发下去了，改数字不会把资源收回来，只会让账和余额对不上；</li>
 *   <li><b>插入与重滚在同一个事务里</b>：中途失败会留下一批原始记录对应着过期的日汇总，
 *       而日汇总没有"待重算"标记，没人会知道要修。</li>
 * </ol>
 *
 * <p>调用方负责线程（{@code RoomUsageRepository} 用它的单线程执行器，
 * 导入那一路用它自己的后台线程）。这里不排队、不 post、不碰界面。
 */
public final class CallImporter {

    private final AppDatabase db;
    private final PricingSource pricing;

    public CallImporter(AppDatabase db, @Nullable PricingSource pricing) {
        this.db = db;
        this.pricing = pricing;
    }

    /**
     * 写一批记录。
     *
     * <p>三类计数怎么来的：
     * <ul>
     *   <li>{@code rejected} —— 转换时就退回来的（缺 id / 缺模型 / 时间戳非法），
     *       根本没进数据库；</li>
     *   <li>{@code skippedDuplicates} —— 进库了但主键撞车，Room 返回 -1；</li>
     *   <li>{@code inserted} —— 剩下的。只有这些的天需要重滚。</li>
     * </ul>
     */
    public UsageRepository.ImportResult importCalls(String uid, @Nullable List<UsageCall> calls) {
        List<UsageCall> incoming = calls == null ? java.util.Collections.<UsageCall>emptyList() : calls;

        List<UsageCallEntity> rows = new ArrayList<>(incoming.size());
        int rejected = 0;
        for (UsageCall call : incoming) {
            // uid 传参数的那个，不是 call.uid——日志/导出文件都是外部输入，
            // 信它等于允许一份文件把自己写成别人的用量。
            UsageCallEntity row = UsageCallEntity.fromModel(call, uid);
            if (row == null) {
                rejected++;
            } else {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return new UsageRepository.ImportResult(0, 0, rejected);
        }

        // runInTransaction 的入参是 Runnable，没有返回值，所以计数用数组带出来。
        // [0] = inserted，[1] = skippedDuplicates。
        int[] counts = new int[2];
        db.runInTransaction(() -> {
            List<Long> rowIds = db.usageCallDao().insertAll(rows);

            Set<String> affectedDays = new LinkedHashSet<>();
            for (int i = 0; i < rowIds.size(); i++) {
                Long rowId = rowIds.get(i);
                // -1 == 主键冲突被跳过，见 UsageCallDao.insertAll。
                if (rowId != null && rowId != -1L) {
                    counts[0]++;
                    affectedDays.add(rows.get(i).day);
                } else {
                    counts[1]++;
                }
            }

            // 只重滚真正新增了记录的天：撞车的那条早就在库里，它那天的汇总当时就算过了。
            rollupDays(uid, affectedDays);
        });

        return new UsageRepository.ImportResult(counts[0], counts[1], rejected);
    }

    /**
     * 重滚指定几天的日汇总。
     *
     * <p>调用方必须已经在事务里（{@link #importCalls} 就是这么调的）：这里先读后写，
     * 中间隔着一次计算，不在事务里的话并发导入会互相覆盖。
     */
    private void rollupDays(String uid, Set<String> days) {
        if (days == null || days.isEmpty()) {
            return;
        }
        for (String day : days) {
            // 同一天里哪些 (提供方, 模型) 已经结算过，这些不能碰。
            Set<String> frozen = new HashSet<>();
            for (DailyUsageEntity existing : db.dailyUsageDao().rowsOnDay(uid, day)) {
                if (existing.settled) {
                    frozen.add(keyOf(existing.provider, existing.model));
                }
            }

            List<UsageCall> dayCalls = new ArrayList<>();
            for (UsageCallEntity e : db.usageCallDao().callsOnDay(uid, day)) {
                dayCalls.add(e.toModel());
            }

            List<DailyUsageEntity> write = new ArrayList<>();
            for (DailyUsage merged : DailyRollup.rollup(uid, dayCalls, pricing)) {
                if (frozen.contains(keyOf(merged.provider, merged.model))) {
                    continue;
                }
                write.add(DailyUsageEntity.fromModel(merged));
            }
            if (!write.isEmpty()) {
                db.dailyUsageDao().upsertAll(write);
            }
        }
    }

    private static String keyOf(Provider provider, String model) {
        return provider.name() + '\u0000' + model;
    }
}
