package com.mobilegroup20.tokentrail.data;

import static org.junit.Assert.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/**
 * 花钱编排的测试。
 *
 * <p>这里唯一重要的规则：**服务端没确认时重新拉余额，而不是把资源加回去**。
 * 自己加回去在「服务端扣了但响应丢了」的情况下会造成双重记账——
 * 本地白拿一份，而且屏幕上看起来完全正常。
 */
public class SpendSyncTest {

    @Rule public InstantTaskExecutorRule executor = new InstantTaskExecutorRule();

    private static final String UID = "a".repeat(64);

    /** 记账假的账本：记下被要求扣了什么，并可以按类型分别决定结果。 */
    private static final class FakeLedger implements SpendSync.Ledger {
        final List<String> calls = new ArrayList<>();
        int refreshes;
        boolean succeed = true;
        /** 逐类型覆盖结果；没列到的用 `succeed`。 */
        final List<ResourceType> failing = new ArrayList<>();
        /** 手动控制何时回应——用来测「还没回应时什么都不该发生」。 */
        boolean manual;
        final List<MutableLiveData<Boolean>> pending = new ArrayList<>();

        @Override public LiveData<Boolean> spend(String uid, ResourceType type, long amount) {
            calls.add(uid + ":" + type.name() + ":" + amount);
            MutableLiveData<Boolean> answer = new MutableLiveData<>(null);
            pending.add(answer);
            if (!manual) {
                answer.setValue(failing.contains(type) ? Boolean.FALSE : succeed);
            }
            return answer;
        }

        @Override public void refresh() {
            refreshes++;
        }
    }

    private static SpendSync.Charge chargeOf(ResourceType first, long one) {
        return new SpendSync.Charge().add(first, one);
    }

    // ---- 发几次、发什么 --------------------------------------------------

    @Test public void aSingleResourceIsSentOnce() {
        FakeLedger ledger = new FakeLedger();
        new SpendSync(ledger).sync(UID, chargeOf(ResourceType.CACHE, 25));

        assertEquals(1, ledger.calls.size());
        assertEquals(UID + ":CACHE:25", ledger.calls.get(0));
        assertEquals("a successful spend needs no refresh", 0, ledger.refreshes);
    }

    @Test public void buyingATowerSendsBothOfItsResourcesSeparately() {
        // 塔吃 CACHE + OUTPUT，而 `spend` 的签名是一次一种资源——
        // 合成一次调用的话服务端会按「一种资源要 25+10」来判余额够不够。
        FakeLedger ledger = new FakeLedger();
        new SpendSync(ledger).sync(UID, new SpendSync.Charge()
                .add(ResourceType.CACHE, 25).add(ResourceType.OUTPUT, 10));

        assertEquals(2, ledger.calls.size());
        assertTrue(ledger.calls.contains(UID + ":CACHE:25"));
        assertTrue(ledger.calls.contains(UID + ":OUTPUT:10"));
    }

    @Test public void freeThingsAreNotSentAtAll() {
        FakeLedger ledger = new FakeLedger();
        new SpendSync(ledger).sync(UID, new SpendSync.Charge().add(ResourceType.INPUT, 0));
        // 0 元的扣款不发请求：服务端会把它当成「什么都没扣」直接 400。
        assertTrue(ledger.calls.isEmpty());
    }

    @Test public void anEmptyChargeSendsNothing() {
        FakeLedger ledger = new FakeLedger();
        new SpendSync(ledger).sync(UID, new SpendSync.Charge());
        assertTrue(ledger.calls.isEmpty());
    }

    @Test public void theSameResourceAddedTwiceIsSummed() {
        FakeLedger ledger = new FakeLedger();
        new SpendSync(ledger).sync(UID, new SpendSync.Charge()
                .add(ResourceType.INPUT, 5).add(ResourceType.INPUT, 7));
        assertEquals(1, ledger.calls.size());
        assertEquals(UID + ":INPUT:12", ledger.calls.get(0));
    }

    // ---- 失败 → 刷新，不是回滚 -------------------------------------------

    @Test public void aRejectedSpendTriggersARefreshInsteadOfARollback() {
        FakeLedger ledger = new FakeLedger();
        ledger.failing.add(ResourceType.OUTPUT);
        new SpendSync(ledger).sync(UID, new SpendSync.Charge()
                .add(ResourceType.CACHE, 25).add(ResourceType.OUTPUT, 10));

        // 只有失败的那一种触发刷新；成功的那种不该多拉一次。
        assertEquals(1, ledger.refreshes);
    }

    @Test public void everyRejectedResourceTriggersItsOwnRefresh() {
        FakeLedger ledger = new FakeLedger();
        ledger.failing.add(ResourceType.CACHE);
        ledger.failing.add(ResourceType.OUTPUT);
        new SpendSync(ledger).sync(UID, new SpendSync.Charge()
                .add(ResourceType.CACHE, 25).add(ResourceType.OUTPUT, 10));
        // 两次失败就拉两次。多余的那次只是多一个请求，而**漏掉一次就会显示错余额**——
        // 宁可多拉。
        assertEquals(2, ledger.refreshes);
    }

    @Test public void aSuccessfulSpendNeverRefreshes() {
        FakeLedger ledger = new FakeLedger();
        new SpendSync(ledger).sync(UID, new SpendSync.Charge()
                .add(ResourceType.INPUT, 1).add(ResourceType.CACHE, 2).add(ResourceType.OUTPUT, 3));
        assertEquals("a clean success must not cost an extra round trip", 0, ledger.refreshes);
    }

    @Test public void aFalseAnswerTriggersARefresh() {
        FakeLedger ledger = new FakeLedger();
        ledger.manual = true;
        new SpendSync(ledger).sync(UID, chargeOf(ResourceType.INPUT, 9));

        assertEquals(1, ledger.calls.size());
        // 还没回应 → 不刷新。**乐观扣已经生效**，这里多拉一次只会闪一下旧余额。
        assertEquals(0, ledger.refreshes);

        // `HttpSeasonRepository.spend` 的两条路径都发**非 null** 的 Boolean
        // （成功 true，失败/409/网络错误都是 FALSE）——所以这里是真实的成功序列。
        ledger.pending.get(0).setValue(Boolean.TRUE);
        assertEquals("a confirmed spend needs no refresh", 0, ledger.refreshes);
        ledger.pending.get(0).setValue(Boolean.FALSE);
        assertEquals(1, ledger.refreshes);
    }
}
