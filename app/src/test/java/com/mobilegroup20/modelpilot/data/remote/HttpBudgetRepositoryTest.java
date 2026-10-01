package com.mobilegroup20.modelpilot.data.remote;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import com.mobilegroup20.modelpilot.contract.model.Budget;
import com.mobilegroup20.modelpilot.contract.tool.BudgetStatus;
import com.mobilegroup20.modelpilot.data.RelayCredentials;
import com.mobilegroup20.modelpilot.data.repository.SessionProvider;
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
 * 预算仓储的 HTTP 契约测试。
 *
 * <p>重点是两个容易错成静默 bug 的映射：**`configured=false` + `capMicros=null`
 * 不能被读成「预算 0 元」**，以及 **`spentMicros=0` 配 `pricingAvailable=false`
 * 不能被读成「花了 0 元」**。
 */
public class HttpBudgetRepositoryTest {

    @Rule public InstantTaskExecutorRule executor = new InstantTaskExecutorRule();

    private static final String RELAY_KEY = "tt_test_budget_key_0123456789";
    private static final String UID = RelayCredentials.digestOf(RELAY_KEY);
    private static final String MONTH = "2026-09";

    private MockWebServer server;
    private Session session;
    private HttpBudgetRepository repository;

    private static final class Session implements SessionProvider {
        volatile String token = RELAY_KEY, id = UID;
        public String token() { return token; }
        public String accountId() { return id; }
    }

    @Before public void setUp() throws Exception {
        server = new MockWebServer(); server.start(); session = new Session();
        repository = new HttpBudgetRepository(server.url("/api/").toString(), session);
    }

    @After public void tearDown() throws Exception { server.shutdown(); }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    /**
     * 订阅、等一小会、返回**最后拿到的值**——允许结果本身就是 null。
     *
     * <p>不用 `CountDownLatch` 计数，因为「发射几次」在本类里是不定的：
     * 有的路径同步返回（只发一次，而且那个值可能就是 null：`uid` 不匹配、
     * 没登录、`budget == null` 都是**不发请求**就直接给结果）；
     * 有的路径走 Retrofit 回调（初始 null 之后再来一次真结果）。
     * 用「等固定一小段时间取最后值」对两种情况都成立，而且不依赖时序假设。
     */
    private <T> T await(LiveData<T> source) throws Exception {
        AtomicReference<T> latest = new AtomicReference<>();
        AtomicReference<Boolean> any = new AtomicReference<>(false);
        Observer<T> observer = result -> { latest.set(result); any.set(true); };
        source.observeForever(observer);
        try {
            Thread.sleep(400);
            assertTrue("LiveData never emitted", any.get());
            return latest.get();
        } finally {
            source.removeObserver(observer);
        }
    }

    private String statusBody(boolean configured, Long cap, Double ratio, long spent) {
        return "{\"uid\":\"" + UID + "\",\"month\":\"" + MONTH + "\","
                + "\"configured\":" + configured + ","
                + "\"capMicros\":" + (cap == null ? "null" : cap) + ","
                + "\"warnAtRatio\":" + (ratio == null ? "null" : ratio) + ","
                + "\"spentMicros\":" + spent + ",\"pricingAvailable\":false,"
                + "\"coverage\":{\"from\":\"2026-09-01\",\"to\":\"2026-09-27\","
                + "\"daysWithData\":2,\"daysMissing\":[\"2026-09-01\",\"2026-09-02\"]}}";
    }

    // ---- 没设 vs 设了 0（最重要的一条）---------------------------------

    @Test public void anUnsetBudgetIsNullNotAZeroCap() throws Exception {
        server.enqueue(json(statusBody(false, null, null, 0)));
        Budget budget = await(repository.budgetOf(UID, MONTH));
        // 接口注释：没设过返回 null，界面据此显示「还没设预算」的引导。
        // 把 null 读成 0 的话，界面会显示「你设了 0 元预算且已超支」。
        assertNull("unset budget must be null, not a 0 cap", budget);

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/relay/budgets/" + MONTH, request.getPath());
        assertEquals("Bearer " + RELAY_KEY, request.getHeader("Authorization"));
    }

    @Test public void aZeroCapIsReturnedAsAConfiguredBudget() throws Exception {
        server.enqueue(json(statusBody(true, 0L, 0.8, 0)));
        Budget budget = await(repository.budgetOf(UID, MONTH));
        assertNotNull("a deliberately set 0 cap is configured", budget);
        assertEquals(0, budget.capMicros);
        assertEquals(MONTH, budget.month);
    }

    @Test public void aNormalBudgetCarriesItsCapAndRatio() throws Exception {
        server.enqueue(json(statusBody(true, 20_000_000L, 0.5, 0)));
        Budget budget = await(repository.budgetOf(UID, MONTH));
        assertEquals(20_000_000, budget.capMicros);
        assertEquals(0.5, budget.warnAtRatio, 1e-9);
        assertEquals(UID, budget.uid);
    }

    // ---- status：花销算不出来这件事必须留着 ----------------------------

    @Test public void statusKeepsTheUnavailablePricingSignal() throws Exception {
        server.enqueue(json(statusBody(true, 20_000_000L, 0.5, 0)));
        BudgetStatus status = await(repository.status(UID, MONTH));
        assertTrue(status.configured);
        assertEquals(20_000_000, status.capMicros);
        // **service 说 0 就是 0，这一层不改写。** 「算不出来」由 coverage 和
        // 服务端的 pricingAvailable 表达；改写成别的值会让界面失去判断依据。
        assertEquals(0, status.spentMicros);
        assertEquals(2, status.coverage.daysWithData);
        assertEquals(2, status.coverage.daysMissing.size());
        assertFalse(status.coverage.complete());
    }

    @Test public void statusForAnUnsetBudgetIsNotConfigured() throws Exception {
        server.enqueue(json(statusBody(false, null, null, 0)));
        BudgetStatus status = await(repository.status(UID, MONTH));
        // configured=false 而不是「有预算但花销 0」——后者会被 warnThresholdCrossed
        // 当成超支来提示。
        assertFalse(status.configured);
        assertEquals(MONTH, status.month);
    }

    @Test public void aFailedStatusIsNotConfiguredRatherThanZeroSpend() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json").setBody("{}"));
        BudgetStatus status = await(repository.status(UID, MONTH));
        // 失败时**不能**返回「configured=true 且 spent=0」——那会让界面显示
        // 「你还有全部预算没用」，而实际上什么都没查到。
        assertFalse(status.configured);
        assertNotNull(status.coverage);
    }

    @Test public void coverageIsCopiedNotShared() throws Exception {
        server.enqueue(json(statusBody(true, 1L, 0.8, 0)));
        BudgetStatus first = await(repository.status(UID, MONTH));
        first.coverage.daysMissing.add("2026-09-30");     // 调用方改了它
        server.enqueue(json(statusBody(true, 1L, 0.8, 0)));
        BudgetStatus second = await(repository.status(UID, MONTH));
        // 每次都要是新的 Coverage，不能是同一个对象——否则一处的改动会串到另一处。
        assertEquals(2, second.coverage.daysMissing.size());
    }

    // ---- saveBudget ------------------------------------------------------

    @Test public void saveBudgetPutsTheCapAndReturnsWhatTheServerStored() throws Exception {
        server.enqueue(json("{\"uid\":\"" + UID + "\",\"month\":\"" + MONTH + "\","
                + "\"capMicros\":20000000,\"warnAtRatio\":0.5,\"updatedAtEpochMillis\":1790500000000}"));
        Budget saved = await(repository.saveBudget(new Budget(UID, MONTH, 20_000_000, 0.5)));

        assertNotNull(saved);
        assertEquals(20_000_000, saved.capMicros);

        RecordedRequest request = server.takeRequest();
        assertEquals("PUT", request.getMethod());
        assertEquals("/api/relay/budgets/" + MONTH, request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body, body.contains("\"capMicros\":20000000"));
        assertTrue(body, body.contains("\"warnAtRatio\":0.5"));
    }

    @Test public void aFailedSaveReturnsNullInsteadOfEchoingTheInput() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"INVALID_INPUT\",\"message\":\"bad\"}"));
        Budget saved = await(repository.saveBudget(new Budget(UID, MONTH, 20_000_000, 0.5)));
        // **回传入参会让界面以为存上了。** 失败必须是 null。
        assertNull("a failed save must not look like a successful one", saved);
    }

    // ---- 身份与边界 ------------------------------------------------------

    @Test public void aMismatchedUidNeverReachesTheNetwork() throws Exception {
        assertNull(await(repository.budgetOf("someone-elses-uid", MONTH)));
        BudgetStatus status = await(repository.status("someone-elses-uid", MONTH));
        assertFalse(status.configured);
        assertEquals("no request should have been sent", 0, server.getRequestCount());
    }

    @Test public void aSignedOutSessionMakesNoRequest() throws Exception {
        session.token = null; session.id = null;
        assertNull(await(repository.budgetOf(UID, MONTH)));
        assertEquals(0, server.getRequestCount());
    }

    @Test public void nullBudgetIsHandledWithoutCrashing() throws Exception {
        assertNull(await(repository.saveBudget(null)));
        assertEquals(0, server.getRequestCount());
    }
}
