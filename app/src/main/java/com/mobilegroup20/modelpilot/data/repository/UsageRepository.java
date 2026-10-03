package com.mobilegroup20.modelpilot.data.repository;

import androidx.lifecycle.LiveData;

import java.util.List;

import com.mobilegroup20.modelpilot.contract.model.DailyUsage;
import com.mobilegroup20.modelpilot.contract.model.PricingRate;
import com.mobilegroup20.modelpilot.contract.model.Provider;
import com.mobilegroup20.modelpilot.contract.model.TokenBundle;
import com.mobilegroup20.modelpilot.contract.model.UsageCall;

/**
 * 用量数据的读写口。<b>数据侧（张莉）实现，游戏和 agent 只依赖这个接口。</b>
 *
 * <p>接口里没有一个方法提到 Room、服务端存储或 Retrofit。实现者用哪种存储、
 * 几张表、怎么建索引，都是接口背后的事；换成别的存储，这个文件一行不用改。
 *
 * <p>所有方法都带 {@code uid} 参数而不是从全局拿：账号隔离是每个查询的性质，
 * 不是某个模块的开关。实现里每一个查询都必须落到 uid 上，漏一个就是越权。
 *
 * <p>返回 {@link LiveData} 是为了配合大纲 §8 里写的 ViewModel/LiveData 那套状态管理：
 * 界面订阅一次就够，数据变了自动刷新，不用手动回调。
 */
public interface UsageRepository {

    /**
     * 原始调用记录只写一次，之后只读。导入时按 {@link UsageCall#id} 去重。
     *
     * <p>为什么要返回统计而不返回 void：去重是这个项目要验证的行为之一，
     * 「这次导入进去几条、跳过几条重复」必须能显示给用户看，也方便测试断言。
     */
    LiveData<ImportResult> importCalls(String uid, List<UsageCall> calls);

    /**
     * 取某一天的按模型汇总。游戏结算读它——把返回的每条 {@link DailyUsage} 的
     * 四类 token 加起来，就是这一天要换算成资源的总量。
     *
     * @param day 格式 {@code yyyy-MM-dd}，Asia/Shanghai
     */
    LiveData<List<DailyUsage>> dailyUsageFor(String uid, String day);

    /**
     * 取一个区间的按天明细。Dashboard 的趋势图和热力图用它。
     *
     * @param from 起始日，含
     * @param to   结束日，含
     */
    LiveData<List<DailyUsage>> dailyUsageIn(String uid, String from, String to);

    /**
     * 某个月的 token 合计，就是游戏里「本月已花 token」那个数。
     *
     * <p>单独一个方法而不是让调用方拿区间明细自己加：这个数每次打开游戏都要读，
     * 实现方可以为此做一份缓存或预聚合，而调用方不需要知道。
     *
     * @param month 格式 {@code yyyy-MM}
     */
    LiveData<TokenBundle> monthTokens(String uid, String month);

    /** 某天某个模型当前生效的费率。查不到返回 null——「查不到」和「免费」是两回事。 */
    LiveData<PricingRate> rateFor(Provider provider, String model, String day);

    /** 一次导入的结果。 */
    class ImportResult {

        /** 真正新增的记录数。 */
        public int inserted;

        /** 因为 id 重复被跳过的记录数。这个数大说明日志被重复导入了，属于正常情况。 */
        public int skippedDuplicates;

        /** 解析失败、字段缺失之类被丢弃的记录数。这个数不该长期大于 0。 */
        public int rejected;

        public ImportResult() {
        }

        public ImportResult(int inserted, int skippedDuplicates, int rejected) {
            this.inserted = inserted;
            this.skippedDuplicates = skippedDuplicates;
            this.rejected = rejected;
        }
    }
}
