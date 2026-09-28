package com.mobilegroup20.tokentrail.ui.dashboard;

import static org.junit.Assert.assertEquals;

import com.mobilegroup20.tokentrail.contract.model.DailyUsage;
import com.mobilegroup20.tokentrail.contract.model.Provider;

import org.junit.Test;

import java.util.Arrays;

public class DashboardUsageTest {
    @Test
    public void aggregatesByDayAndProviderWithoutTreatingUnknownPriceAsFree() {
        DailyUsage openai = row("2026-09-26", Provider.OPENAI, 100, 20, 5, 30,
                1_000_000, "source");
        DailyUsage mimo = row("2026-09-26", Provider.MIMO, 40, 0, 0, 10,
                0, null);
        DailyUsage deepseek = row("2026-09-27", Provider.DEEPSEEK, 60, 0, 0, 20,
                500_000, "2026-09");
        DailyUsage outside = row("2026-08-31", Provider.OPENAI, 999, 0, 0, 0,
                0, "source");

        DashboardUsage.Summary result = DashboardUsage.calculate(
                Arrays.asList(openai, mimo, deepseek, outside), "2026-09-01", "2026-09-27");

        assertEquals(285, result.month.tokens);
        assertEquals(1_500_000, result.month.knownCostMicros);
        assertEquals(1, result.month.rowsWithoutPrice);
        assertEquals(155, result.providers.get(Provider.OPENAI).tokens);
        assertEquals(50, result.providers.get(Provider.MIMO).tokens);
        assertEquals(205L, result.tokensByDay.get("2026-09-26").longValue());
        assertEquals(80L, result.tokensByDay.get("2026-09-27").longValue());
        assertEquals(27, result.completedDays);
        assertEquals(2, result.daysWithRecords);
    }

    private static DailyUsage row(String day, Provider provider, long input,
                                  long cacheRead, long cacheWrite, long output,
                                  long costMicros, String rateVersion) {
        DailyUsage row = new DailyUsage();
        row.day = day;
        row.provider = provider;
        row.input = input;
        row.cacheRead = cacheRead;
        row.cacheWrite = cacheWrite;
        row.output = output;
        row.costMicros = costMicros;
        row.rateVersion = rateVersion;
        return row;
    }
}
