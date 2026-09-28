package com.mobilegroup20.tokentrail.ui.dashboard;

import com.mobilegroup20.tokentrail.contract.model.DailyUsage;
import com.mobilegroup20.tokentrail.contract.model.Provider;
import com.mobilegroup20.tokentrail.util.TimeUtils;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregates the same daily usage rows that back the rest of the app. */
public final class DashboardUsage {
    private DashboardUsage() {}

    public static final class Totals {
        public long tokens;
        public long knownCostMicros;
        public int rowsWithoutPrice;
    }

    public static final class Summary {
        public final Totals month = new Totals();
        public final Map<Provider, Totals> providers = new EnumMap<>(Provider.class);
        public final Map<String, Long> tokensByDay = new HashMap<>();
        public int completedDays;
        public int daysWithRecords;
    }

    public static Summary calculate(List<DailyUsage> rows, String from, String to) {
        Summary result = new Summary();
        if (from == null || to == null || from.compareTo(to) > 0) return result;
        result.completedDays = TimeUtils.daysBetween(from, to).size();
        Set<String> recordedDays = new HashSet<>();
        if (rows == null) return result;

        for (DailyUsage row : rows) {
            if (row == null || row.provider == null || row.day == null
                    || row.day.compareTo(from) < 0 || row.day.compareTo(to) > 0) continue;
            long tokens = row.input + row.cacheRead + row.cacheWrite + row.output;
            add(result.month, row, tokens);
            add(result.providers.computeIfAbsent(row.provider, key -> new Totals()), row, tokens);
            result.tokensByDay.merge(row.day, tokens, Long::sum);
            recordedDays.add(row.day);
        }
        result.daysWithRecords = recordedDays.size();
        return result;
    }

    private static void add(Totals totals, DailyUsage row, long tokens) {
        totals.tokens += tokens;
        if (row.rateVersion == null) totals.rowsWithoutPrice++;
        else totals.knownCostMicros += row.costMicros;
    }
}
