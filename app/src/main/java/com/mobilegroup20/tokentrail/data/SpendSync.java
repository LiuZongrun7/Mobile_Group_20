package com.mobilegroup20.tokentrail.data;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import java.util.ArrayList;
import java.util.List;

/**
 * 花钱时的「本地乐观扣 + 服务端对账」编排。<b>负责人：游戏侧（刘宗润）。</b>
 *
 * <p>从 `MainActivity` 里单独提出来，因为这里有一条**错了会静默白送资源**的规则：
 * 服务端没确认时**重新拉一次余额，而不是把资源加回去**。
 *
 * <p>为什么不能自己回滚：同步失败可能是「服务端拒了」（余额没动），
 * 也可能是「服务端扣了但响应丢了」。自己加回去，在后一种情况下就变成
 * **双重记账**——本地白拿一份，而且屏幕上看起来完全正常。
 *
 * <p>三种资源**每次都分开传**（{@code SeasonRepository.spend} 的签名就是一次一种）。
 * 一次买塔可能同时吃 CACHE 和 OUTPUT，所以这里按类型各发一次。
 */
public final class SpendSync {

    /** 服务端扣款的结果。抽成接口是为了能单测这条编排，不必拉起真的仓储。 */
    public interface Ledger {
        LiveData<Boolean> spend(String uid, ResourceType type, long amount);
        /** 重新拉一次服务端余额。**不对账就把资源加回去的那种做法在这里被排除掉了。** */
        void refresh();
    }

    private final Ledger ledger;

    public SpendSync(Ledger ledger) {
        this.ledger = ledger;
    }

    /** 一次消费里每种资源各扣多少。没用到的那些是 0。 */
    public static final class Charge {
        public final long[] amounts = new long[ResourceType.values().length];

        public Charge add(ResourceType type, long amount) {
            if (amount > 0) {
                amounts[type.ordinal()] += amount;
            }
            return this;
        }

        public long amountOf(ResourceType type) {
            return amounts[type.ordinal()];
        }

        public boolean empty() {
            for (long amount : amounts) {
                if (amount > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 把这一笔消费同步到服务端。返回的那些 {@code LiveData} 由调用方观察。
     *
     * <p>本地已经乐观扣过了，所以这里**不碰本地余额**——只在服务端说「没扣成」
     * 的时候重新拉一次真的。
     */
    public List<LiveData<Boolean>> sync(String uid, Charge charge) {
        List<LiveData<Boolean>> results = new ArrayList<>();
        for (ResourceType type : ResourceType.values()) {
            long amount = charge.amountOf(type);
            if (amount <= 0) {
                continue;
            }
            LiveData<Boolean> result = ledger.spend(uid, type, amount);
            results.add(result);
            result.observeForever(ok -> {
                if (Boolean.FALSE.equals(ok)) {
                    ledger.refresh();
                }
            });
        }
        return results;
    }
}
