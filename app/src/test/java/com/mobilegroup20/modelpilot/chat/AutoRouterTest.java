package com.mobilegroup20.modelpilot.chat;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;

public class AutoRouterTest {
    private static ModelSpec model(String provider, String id, int limit, boolean image, Long in, Long out) {
        return new ModelSpec(provider, id, id, limit, true, image, false, false,
                in, null, null, out, null);
    }
    private static ProviderSpec provider(String id, ModelSpec... models) {
        return new ProviderSpec(id, id, "https://example.com", ProviderSpec.Adapter.OPENAI_COMPATIBLE,
                Arrays.asList(models));
    }
    private static AutoRouter router(ProviderSpec... providers) {
        return new AutoRouter(ProviderRegistry.defaults().withCustom(Arrays.asList(providers)));
    }
    private static AutoRouter.Request request(TaskKind task, long in, AutoRouter.Preference preference, String preferred) {
        return new AutoRouter.Request(task, in, 100, 4096, preference, preferred);
    }
    @Test public void pricesDependOnInputOutputMixRatherThanPriceSum() {
        AutoRouter r = router(provider("p", model("p", "cheap-input", 100000, false, 100L, 10000L),
                model("p", "cheap-output", 100000, false, 1000L, 1000L)));
        AutoRouter.Decision d = r.choose(Arrays.asList("p"), request(TaskKind.TEXT, 50000,
                AutoRouter.Preference.LOWEST_COST, null));
        assertEquals("cheap-input", d.modelId);
        assertEquals(Long.valueOf(6), d.estimatedCostMicros);
        assertEquals(2, d.candidates.size());
    }
    @Test public void imageExcludesCheaperTextModel() {
        AutoRouter r = router(provider("p", model("p", "text", 100000, false, 1L, 1L),
                model("p", "vision", 100000, true, 100L, 100L)));
        AutoRouter.Decision d = r.choose(Arrays.asList("p"), request(TaskKind.IMAGE, 100,
                AutoRouter.Preference.LOWEST_COST, null));
        assertEquals("vision", d.modelId);
        assertTrue(d.excluded.stream().anyMatch(s -> s.contains("不支持图片")));
    }
    @Test public void longInputExcludesModelWhoseWindowCannotFitOutputReserve() {
        AutoRouter r = router(provider("p", model("p", "small", 8000, false, 1L, 1L),
                model("p", "large", 100000, false, 100L, 100L)));
        AutoRouter.Decision d = r.choose(Arrays.asList("p"), request(TaskKind.TEXT, 3000,
                AutoRouter.Preference.LOWEST_COST, null));
        assertEquals("large", d.modelId);
        assertTrue(d.excluded.stream().anyMatch(s -> s.contains("容量不足")));
    }
    @Test public void preferenceNeverOverridesCapabilities() {
        AutoRouter r = router(provider("preferred", model("preferred", "text", 100000, false, 1L, 1L)),
                provider("other", model("other", "vision", 100000, true, 100L, 100L)));
        AutoRouter.Decision d = r.choose(Arrays.asList("preferred", "other"),
                request(TaskKind.IMAGE, 100, AutoRouter.Preference.PREFERRED_PROVIDER, "preferred"));
        assertEquals("other", d.providerId);
        assertTrue(d.reason.contains("回退"));
    }
    @Test public void compatiblePreferredProviderCanWinDespiteHigherCost() {
        AutoRouter r = router(provider("p", model("p", "m", 100000, false, 1000L, 1000L)),
                provider("q", model("q", "m", 100000, false, 1L, 1L)));
        assertEquals("p", r.choose(Arrays.asList("p", "q"), request(TaskKind.TEXT, 100,
                AutoRouter.Preference.PREFERRED_PROVIDER, "p")).providerId);
    }
    @Test public void largeContextModeDoesNotInventQualityRanking() {
        AutoRouter r = router(provider("p", model("p", "small", 10000, false, 1L, 1L),
                model("p", "large", 100000, false, 1000L, 1000L)));
        assertEquals("large", r.choose(Arrays.asList("p"), request(TaskKind.TEXT, 100,
                AutoRouter.Preference.LARGE_CONTEXT, null)).modelId);
    }
    @Test public void unknownPriceIsNotFreeButRemainsUsableAsFallback() {
        AutoRouter r = router(provider("p", model("p", "unknown", 200000, false, null, null),
                model("p", "known", 100000, false, 10000L, 10000L)));
        assertEquals("known", r.choose(Arrays.asList("p"), request(TaskKind.TEXT, 100,
                AutoRouter.Preference.LOWEST_COST, null)).modelId);
        AutoRouter.Decision d = r.choose(Arrays.asList("p"), request(TaskKind.TEXT, 100000,
                AutoRouter.Preference.LOWEST_COST, null));
        assertEquals("unknown", d.modelId); assertNull(d.estimatedCostMicros);
        assertTrue(d.reason.contains("价格未知"));
    }
    @Test public void renderedProviderSpecificInputCanExcludeASeeminglyCompatibleModel() {
        AutoRouter r = router(provider("p", model("p", "m", 100000, false, 100L, 100L)));
        AutoRouter.Decision d = r.choose(Arrays.asList("p"), request(TaskKind.TEXT, 1,
                AutoRouter.Preference.LOWEST_COST, null), new AutoRouter.ContextFit() {
                    public long inputTokens(ModelSpec m) { return 100; }
                    public long usableTokens(ModelSpec m) { return 99; }
                });
        assertFalse(d.available());
    }
    @Test public void noConfiguredProvidersGivesActionableFailure() {
        AutoRouter.Decision d = router().choose(Collections.emptyList(), TaskKind.TEXT);
        assertFalse(d.available()); assertFalse(d.excluded.isEmpty());
    }
    @Test public void positiveSubMicroEstimateRoundsUpAndOverflowStaysUnknown() {
        ModelSpec m = model("p", "m", 100000, false, 1L, 1L);
        assertEquals(Long.valueOf(1), AutoRouter.estimateCost(m, 1, 0));
        assertEquals(Long.valueOf(0), AutoRouter.estimateCost(m, 0, 0));
        assertNull(AutoRouter.estimateCost(model("p", "m", 100000, false, Long.MAX_VALUE, Long.MAX_VALUE),
                Long.MAX_VALUE, Long.MAX_VALUE));
    }
    @Test public void tieBreakIsStableAndCandidateListIsReadOnly() {
        AutoRouter r = router(provider("p", model("p", "b", 100000, false, 1L, 1L),
                model("p", "a", 100000, false, 1L, 1L)));
        AutoRouter.Decision d = r.choose(Arrays.asList("p"), TaskKind.TEXT);
        assertEquals("a", d.modelId);
        try { d.candidates.clear(); fail("mutable decision"); }
        catch (UnsupportedOperationException expected) { }
    }
    @Test(expected = IllegalArgumentException.class) public void negativeInputIsRejected() {
        request(TaskKind.TEXT, -1, AutoRouter.Preference.LOWEST_COST, null);
    }
}
