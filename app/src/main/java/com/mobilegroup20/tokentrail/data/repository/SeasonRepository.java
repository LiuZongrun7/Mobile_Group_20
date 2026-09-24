package com.mobilegroup20.tokentrail.data.repository;

import androidx.lifecycle.LiveData;

import java.util.ArrayList;
import java.util.List;

import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import com.mobilegroup20.tokentrail.contract.model.SeasonState;
import com.mobilegroup20.tokentrail.contract.model.TokenBundle;

/**
 * 赛季与结算。<b>游戏侧（刘宗润）实现，游戏和 dashboard 都读它。</b>
 *
 * <p>结算的四条规则写在 {@link SeasonState} 的类注释里，这个接口的形状就是为了让
 * 它们没法被绕过：
 * <ul>
 *   <li>只有 {@link #settleCompletedDays} 能改余额，别处改不了；</li>
 *   <li>它不接受「结算哪一天」这个参数——调用方说不了算，能算哪些天只由
 *       {@link SeasonState#lastSettledDay} 和数据本身决定；</li>
 *   <li>重复调用是幂等的，返回值里写明这次实际结算了哪几天，
 *       所以「补了三天」和「早就结完了」看得出来。</li>
 * </ul>
 */
public interface SeasonRepository {

    /** 当前赛季的状态。没结算过时 {@code lastSettledDay} 为 null。 */
    LiveData<SeasonState> currentSeason(String uid);

    /**
     * 把「已经过完但还没结算」的日子全部结算掉。
     *
     * <p>调用时机：应用每次走到前台、以及 WorkManager 的每日任务。两个入口调同一个
     * 方法，因为它幂等——多调几次不会多发资源，反而能兜住「定时任务没跑成」。
     *
     * <p>今天<b>不在</b>结算范围内，当天还没结束。
     */
    LiveData<SettlementResult> settleCompletedDays(String uid);

    /**
     * 建造或升级防御塔、墙，扣掉对应资源。
     *
     * <p>带类型参数是因为三种资源不互通：塔需要哪种就扣哪种，不够就失败，
     * 不会拿别的资源顶上。返回 false 表示资源不足，界面据此提示。
     */
    LiveData<Boolean> spend(String uid, ResourceType type, long amount);

    /** 一次结算的结果。 */
    class SettlementResult {

        /** 这次真正结算了哪几天，格式 {@code yyyy-MM-dd}。空表示没有新的一天可结。 */
        public final List<String> settledDays = new ArrayList<>();

        /** 这次一共换出多少资源，按类型分。三天并作一次补的时候这里会比较大。 */
        public ResourceBalance gained = new ResourceBalance();

        /** 参与换算的 token 总量，按四类分。用来向用户解释「资源是哪儿来的」。 */
        public TokenBundle tokensCounted = new TokenBundle();

        /** 结算后的余额，直接拿来刷新界面，省一次查询。 */
        public ResourceBalance balanceAfter = new ResourceBalance();

        public SettlementResult() {
        }
    }
}
