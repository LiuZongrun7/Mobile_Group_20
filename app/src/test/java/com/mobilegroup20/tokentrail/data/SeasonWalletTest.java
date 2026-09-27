package com.mobilegroup20.tokentrail.data;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import com.mobilegroup20.tokentrail.contract.model.ResourceBalance;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import com.mobilegroup20.tokentrail.contract.model.SeasonState;
import com.mobilegroup20.tokentrail.data.repository.SeasonRepository;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 游戏钱包来源的决策测试。
 *
 * <p>这一层唯一的价值就是那个取舍：**有中转走服务端、没有才回落演示数据**。
 * 判据是「有没有配置中转」而**不是**「余额是不是 0」——后者会把
 * 「确实还没攒到资源」和「查不到」混成一种。
 */
public class SeasonWalletTest {

    @Rule public InstantTaskExecutorRule executor = new InstantTaskExecutorRule();

    private static final String UID = "a".repeat(64);

    private static final class Identity implements SeasonWallet.Identity {
        boolean configured; String uid;
        Identity(boolean configured, String uid) { this.configured = configured; this.uid = uid; }
        @Override public boolean configured() { return configured; }
        @Override public String uid() { return uid; }
    }

    /** 假赛季仓储：记下被问了什么，返回预设的状态。 */
    private static final class FakeSeasons implements SeasonRepository {
        final AtomicReference<String> askedFor = new AtomicReference<>();
        final AtomicInteger spendCalls = new AtomicInteger();
        LiveData<SeasonState> answer = new MutableLiveData<>(null);
        boolean spendSucceeds;

        @Override public LiveData<SeasonState> currentSeason(String uid) {
            askedFor.set(uid);
            return answer;
        }
        @Override public LiveData<SettlementResult> settleCompletedDays(String uid) {
            return new MutableLiveData<>(new SettlementResult());
        }
        @Override public LiveData<Boolean> spend(String uid, ResourceType type, long amount) {
            spendCalls.incrementAndGet();
            return new MutableLiveData<>(spendSucceeds);
        }
    }

    private static SeasonState state(long input, long cache, long output) {
        SeasonState value = new SeasonState();
        value.balance = new ResourceBalance(input, cache, output);
        return value;
    }

    /** 订阅后立刻取当前值。本类的 LiveData 都是同步给值的（假仓储不异步）。 */
    private static <T> T read(LiveData<T> source) {
        AtomicReference<T> latest = new AtomicReference<>();
        Observer<T> observer = latest::set;
        source.observeForever(observer);
        try {
            return latest.get();
        } finally {
            source.removeObserver(observer);
        }
    }

    private static ResourceBalance demo() {
        return new ResourceBalance(7, 8, 9);
    }

    // ---- 没启用中转 → 演示数据 ------------------------------------------

    @Test public void withoutARelayKeyTheWalletFallsBackToDemoData() {
        FakeSeasons seasons = new FakeSeasons();
        SeasonWallet wallet = new SeasonWallet(new Identity(false, null), seasons);

        SeasonWallet.Wallet value = read(wallet.balance(SeasonWalletTest::demo));

        assertEquals(SeasonWallet.Source.DEMO, value.source);
        assertTrue(value.isDemo());
        assertEquals(7, value.balance.input);
        // 没身份就**不该去问服务端**——那只会拿到 401。
        assertNull("must not query the server without an identity", seasons.askedFor.get());
    }

    @Test public void aConfiguredFlagWithoutAUidIsStillNotUsable() {
        // configured() 为真但 uid 为 null：半个身份。发出去只会 401，
        // 所以按「没有中转」处理。
        FakeSeasons seasons = new FakeSeasons();
        SeasonWallet wallet = new SeasonWallet(new Identity(true, null), seasons);

        assertEquals(SeasonWallet.Source.DEMO, read(wallet.balance(SeasonWalletTest::demo)).source);
        assertNull(seasons.askedFor.get());
    }

    // ---- 启用了中转 → 服务端 --------------------------------------------

    @Test public void withARelayKeyTheServerBalanceWins() {
        FakeSeasons seasons = new FakeSeasons();
        seasons.answer = new MutableLiveData<>(state(100, 200, 300));
        SeasonWallet wallet = new SeasonWallet(new Identity(true, UID), seasons);

        SeasonWallet.Wallet value = read(wallet.balance(SeasonWalletTest::demo));

        assertEquals(SeasonWallet.Source.SERVER, value.source);
        assertEquals(100, value.balance.input);
        assertEquals(200, value.balance.cache);
        assertEquals(300, value.balance.output);
        assertEquals(UID, seasons.askedFor.get());
    }

    @Test public void aGenuineZeroBalanceIsServerZeroNotDemoData() {
        /** 这条是整轮里最重要的断言。
         *
         *  一个启用了中转、但确实还没攒到资源的账号，服务端答的就是 0。
         *  如果按「余额为 0 就回落演示数据」来判，他会白拿到一笔
         *  按本月 token 现算的资源，而且屏幕上那行来源写着「演示估算」——
         *  演示时看起来像正常，其实他的真实余额被盖掉了。
         */
        FakeSeasons seasons = new FakeSeasons();
        seasons.answer = new MutableLiveData<>(state(0, 0, 0));
        SeasonWallet wallet = new SeasonWallet(new Identity(true, UID), seasons);

        SeasonWallet.Wallet value = read(wallet.balance(SeasonWalletTest::demo));

        assertEquals("a server answer of 0 is authoritative", SeasonWallet.Source.SERVER, value.source);
        assertEquals(0, value.balance.input);
    }

    @Test public void aMissingBalanceObjectDoesNotCrash() {
        FakeSeasons seasons = new FakeSeasons();
        SeasonState bare = new SeasonState();          // balance 是 null
        seasons.answer = new MutableLiveData<>(bare);
        SeasonWallet wallet = new SeasonWallet(new Identity(true, UID), seasons);

        SeasonWallet.Wallet value = read(wallet.balance(SeasonWalletTest::demo));
        assertEquals(SeasonWallet.Source.SERVER, value.source);
        assertEquals(0, value.balance.input);
    }

    @Test public void beforeTheServerAnswersTheSourceIsLoading() {
        FakeSeasons seasons = new FakeSeasons();
        seasons.answer = new MutableLiveData<>(null);
        SeasonWallet wallet = new SeasonWallet(new Identity(true, UID), seasons);

        SeasonWallet.Wallet value = read(wallet.balance(SeasonWalletTest::demo));
        // LOADING 而不是 DEMO：还没答，不能拿演示数字顶上（那会闪一下假的余额，
        // 然后在响应回来时跳变）。
        assertEquals(SeasonWallet.Source.LOADING, value.source);
        assertEquals(0, value.balance.input);
    }

    // ---- 消费同步 --------------------------------------------------------

    @Test public void spendingIsNotSentToTheServerWithoutAnIdentity() {
        FakeSeasons seasons = new FakeSeasons();
        SeasonWallet wallet = new SeasonWallet(new Identity(false, null), seasons);

        assertEquals(Boolean.FALSE, read(wallet.syncSpend(ResourceType.INPUT, 10)));
        assertEquals("demo mode must not hit the endpoint", 0, seasons.spendCalls.get());
    }

    @Test public void spendingIsForwardedWhenThereIsAnIdentity() {
        FakeSeasons seasons = new FakeSeasons();
        seasons.spendSucceeds = true;
        SeasonWallet wallet = new SeasonWallet(new Identity(true, UID), seasons);

        assertEquals(Boolean.TRUE, read(wallet.syncSpend(ResourceType.OUTPUT, 5)));
        assertEquals(1, seasons.spendCalls.get());
    }

    @Test public void aRejectedSpendIsReportedAsFalse() {
        FakeSeasons seasons = new FakeSeasons();
        seasons.spendSucceeds = false;
        SeasonWallet wallet = new SeasonWallet(new Identity(true, UID), seasons);
        // false 时界面应该**重新拉一次余额**而不是自己回滚——服务端可能已经扣了。
        assertEquals(Boolean.FALSE, read(wallet.syncSpend(ResourceType.CACHE, 5)));
    }

    @Test public void relayConfiguredMirrorsTheIdentity() {
        assertTrue(new SeasonWallet(new Identity(true, UID), new FakeSeasons()).relayConfigured());
        assertFalse(new SeasonWallet(new Identity(false, null), new FakeSeasons()).relayConfigured());
        assertFalse(new SeasonWallet(new Identity(true, null), new FakeSeasons()).relayConfigured());
    }
}
