package com.mobilegroup20.tokentrail.data.remote;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import com.mobilegroup20.tokentrail.contract.model.ResourceType;
import com.mobilegroup20.tokentrail.contract.model.SeasonState;
import com.mobilegroup20.tokentrail.data.RelayCredentials;
import com.mobilegroup20.tokentrail.data.repository.SeasonRepository;
import com.mobilegroup20.tokentrail.data.repository.SessionProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 赛季仓储的 HTTP 契约测试。<b>负责人：游戏侧（刘宗润）。</b>
 *
 * <p>用 `MockWebServer` 而不是打真服务器：这里要验的是**请求长什么样**
 * （路径、认证头、请求体）和**响应怎么翻成契约类型**，不是服务端的结算逻辑
 * （那在 `backend/tests/test_seasons.py`）。两边各测各的，中间用这份契约对齐。
 */
public class HttpSeasonRepositoryTest {

    @Rule public InstantTaskExecutorRule executor = new InstantTaskExecutorRule();

    private static final String RELAY_KEY = "tt_test_relay_key_0123456789";
    private static final String UID = RelayCredentials.digestOf(RELAY_KEY);

    private MockWebServer server;
    private Session session;
    private HttpSeasonRepository repository;

    /** 身份就是 relay key 和它的 sha256——和服务端口径一致（见 `RelayCredentials.uid()`）。 */
    private static final class Session implements SessionProvider {
        volatile String token = RELAY_KEY, id = UID;
        public String token() { return token; }
        public String accountId() { return id; }
    }

    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start(); session = new Session();
        repository = new HttpSeasonRepository(server.url("/api/").toString(), session);
    }

    @After public void tearDown() throws Exception { server.shutdown(); }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    /**
     * 等结果稳定下来，返回**最后一次**发射的值。
     *
     * <p>为什么要「稳定」：`currentSeason` 走的是 `Transformations.map`，
     * 那个 `MediatorLiveData` 会先把源的当前值转发一次（源是
     * `new MutableLiveData<>(null)` 建的，所以第一次拿到的是 null），
     * **然后**才是响应。只等「第一次发射」会拿到初始状态——这个坑踩过，
     * 症状是断言里 `lastSettledDay` 是 null 而代码其实没问题。
     *
     * <p>而 `settle` / `spend` 的 LiveData 初值就是 null、只会发一次，
     * 所以不能死等第二次——用「最后一次非 null」的口径，两种都适用。
     */
    private <T> T await(LiveData<T> source) throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1);
        AtomicReference<T> latest = new AtomicReference<>();
        AtomicReference<T> stable = new AtomicReference<>();
        Observer<T> observer = result -> {
            if (result == null) return;              // 初始 null 不算结果
            latest.set(result);
            first.countDown();
        };
        source.observeForever(observer);
        try {
            assertTrue("Request timed out", first.await(5, TimeUnit.SECONDS));
            // 再等一小会，看还有没有后续发射（map 那条路会有第二次）。
            new Thread(() -> {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                stable.set(latest.get());
                settled.countDown();
            }).start();
            assertTrue(settled.await(5, TimeUnit.SECONDS));
            return stable.get();
        } finally {
            source.removeObserver(observer);
        }
    }

    @Test public void digestMatchesTheServerSideUidConvention() {
        // 服务端是 sha256(relay key) 的十六进制小写。对不上就所有请求都会 401 之外
        // 更糟的结果——uid 不匹配时仓储直接拒绝发请求，症状是「什么都没发生」。
        assertEquals(64, UID.length());
        assertEquals(UID.toLowerCase(), UID);
        assertEquals(UID, RelayCredentials.digestOf(RELAY_KEY));
    }

    @Test public void currentSeasonSendsRelayKeyAndMapsTheSnapshot() throws Exception {
        server.enqueue(json("{\"uid\":\"" + UID + "\",\"lastSettledDay\":\"2026-09-26\","
                + "\"balance\":{\"input\":100,\"cache\":75,\"output\":200},"
                + "\"updatedAtEpochMillis\":1790500000000,\"tokensPerUnit\":10000,"
                + "\"monthTokens\":{\"input\":1200,\"cacheRead\":900,\"cacheWrite\":0,"
                + "\"output\":340,\"calls\":1,\"isCurrentMonth\":true}}"));

        SeasonState state = await(repository.currentSeason(UID));

        assertEquals(UID, state.uid);
        assertEquals("2026-09-26", state.lastSettledDay);
        assertEquals(100, state.balance.input);
        assertEquals(75, state.balance.cache);
        assertEquals(200, state.balance.output);
        assertEquals(1790500000000L, state.updatedAtEpochMillis);
        // 赛季标识从**服务端的已结算日**推，不用本机时钟。
        assertEquals("2026-09", state.seasonId);
        assertTrue(state.isSettled("2026-09-20"));
        assertFalse(state.isSettled("2026-09-27"));

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/relay/season", request.getPath());
        // **认证用的是账号 token**（2026-02 改），不是 relay key。
        assertEquals("Bearer " + RELAY_KEY, request.getHeader("Authorization"));
    }

    @Test public void currentSeasonWithoutSettlementYetHasNoSeasonId() throws Exception {
        server.enqueue(json("{\"uid\":\"" + UID + "\",\"lastSettledDay\":null,"
                + "\"balance\":{\"input\":0,\"cache\":0,\"output\":0},\"tokensPerUnit\":10000}"));
        SeasonState state = await(repository.currentSeason(UID));
        assertNull(state.lastSettledDay);
        assertNull(state.seasonId);
        assertEquals(0, state.balance.input);
    }

    @Test public void settlePostsWithoutADayParameterAndReportsWhatItSettled() throws Exception {
        server.enqueue(json("{\"uid\":\"" + UID + "\",\"settledDays\":[\"2026-09-25\",\"2026-09-26\"],"
                + "\"gained\":{\"input\":100,\"cache\":100,\"output\":200},"
                + "\"tokensCounted\":{\"input\":1000000,\"cacheRead\":600000,"
                + "\"cacheWrite\":400000,\"output\":2000000},"
                + "\"balanceAfter\":{\"input\":100,\"cache\":100,\"output\":200},"
                + "\"lastSettledDay\":\"2026-09-26\"}"));

        SeasonRepository.SettlementResult result = await(repository.settleCompletedDays(UID));

        assertEquals(2, result.settledDays.size());
        assertEquals("2026-09-25", result.settledDays.get(0));
        assertEquals(100, result.gained.input);
        assertEquals(200, result.balanceAfter.output);
        assertEquals(1_000_000, result.tokensCounted.input);

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/relay/season/settle", request.getPath());
        // **请求体是空的**：接口故意没有「结算哪一天」这个参数。
        assertEquals("", request.getBody().readUtf8());
    }

    @Test public void settlingAgainWithNothingNewReturnsAnEmptyList() throws Exception {
        server.enqueue(json("{\"uid\":\"" + UID + "\",\"settledDays\":[],"
                + "\"gained\":{\"input\":0,\"cache\":0,\"output\":0},"
                + "\"balanceAfter\":{\"input\":100,\"cache\":100,\"output\":200},"
                + "\"lastSettledDay\":\"2026-09-26\"}"));
        SeasonRepository.SettlementResult result = await(repository.settleCompletedDays(UID));
        // 空列表就是幂等的判据：没有新的一天可结。
        assertTrue(result.settledDays.isEmpty());
        assertEquals(100, result.balanceAfter.input);
    }

    @Test public void spendSendsOnlyTheRequestedResourceType() throws Exception {
        server.enqueue(json("{\"uid\":\"" + UID + "\","
                + "\"balance\":{\"input\":60,\"cache\":75,\"output\":190}}"));

        Boolean ok = await(repository.spend(UID, ResourceType.INPUT, 40));

        assertTrue(ok);
        RecordedRequest request = server.takeRequest();
        assertEquals("/api/relay/season/spend", request.getPath());
        String body = request.getBody().readUtf8();
        // 三种资源分开传：买只吃 INPUT 的东西时另两个必须是 0，
        // 否则服务端会以为要同时扣三种，余额够不够的判断就错了。
        assertTrue(body, body.contains("\"input\":40"));
        assertTrue(body, body.contains("\"cache\":0"));
        assertTrue(body, body.contains("\"output\":0"));
    }

    @Test public void spendReturnsFalseWhenTheServerRefuses() throws Exception {
        // 409 = 余额不够。**不能当成成功**——当成成功会让玩家白拿一座塔。
        server.enqueue(new MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"HTTP_409\",\"message\":\"Not enough resources\"}"));
        assertEquals(Boolean.FALSE, await(repository.spend(UID, ResourceType.OUTPUT, 9999)));
    }

    @Test public void spendReturnsFalseWhenTheNetworkFails() throws Exception {
        server.shutdown();                       // 服务端没了
        assertEquals(Boolean.FALSE, await(repository.spend(UID, ResourceType.CACHE, 1)));
        server = new MockWebServer();            // 让 tearDown 有个对象可关
    }

    @Test public void aMismatchedUidNeverReachesTheNetwork() throws Exception {
        // 传进来的 uid 和当前身份不一致 → 直接拒绝，**一个请求都不发**。
        // 这是「每个方法都带 uid」的意义：拿错对象时不能读到别人的余额。
        SeasonState state = await(repository.currentSeason("someone-elses-uid"));
        assertEquals(0, state.balance.input);
        assertNull(state.lastSettledDay);
        assertEquals("no request should have been sent", 0, server.getRequestCount());
    }

    @Test public void anExpiredRelayKeyFallsBackToAnEmptySeason() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"HTTP_401\",\"message\":\"Relay key is not registered\"}"));
        SeasonState state = await(repository.currentSeason(UID));
        // 空状态而不是崩溃：游戏页要能打开并提示「还没启用中转」。
        assertEquals(0, state.balance.input);
        assertNull(state.lastSettledDay);
    }

    @Test public void aSignedOutSessionMakesNoRequest() throws Exception {
        session.token = null; session.id = null;
        SeasonState state = await(repository.currentSeason(UID));
        assertEquals(0, state.balance.input);
        assertEquals(0, server.getRequestCount());
    }

    @Test public void aResponseThatArrivesAfterAnAccountSwitchIsDiscarded() throws Exception {
        server.enqueue(json("{\"uid\":\"" + UID + "\",\"lastSettledDay\":\"2026-09-26\","
                + "\"balance\":{\"input\":100,\"cache\":0,\"output\":0},\"tokensPerUnit\":10000}"));
        // 请求发出去的那一刻是对的，但响应回来前换了账号（重装/重新启用中转）。
        // 旧账号的余额不能被显示成新账号的——所以这里必须回退成空状态。
        LiveData<SeasonState> pending = repository.currentSeason(UID);
        session.token = "tt_another_key_0123456789abcd";
        session.id = RelayCredentials.digestOf(session.token);
        SeasonState state = await(pending);
        assertEquals("stale balance must not be shown", 0, state.balance.input);
    }
}
