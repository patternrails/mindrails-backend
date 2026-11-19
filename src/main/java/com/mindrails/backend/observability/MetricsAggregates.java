package com.mindrails.backend.observability;

import com.mindrails.backend.metrics.MetricsSnapshot;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

public final class MetricsAggregates {
    private MetricsAggregates() {}
    public static MetricsQueryStore.Data from(List<MetricsSnapshot> values, ArchiveFilter filter) {
        var rows = values.stream().filter(run -> com.mindrails.backend.identity.ResourceAccess.visible(run.ownerId())).filter(run -> filter.matches(run.startedAt(), run.agentId(), run.sessionId(), run.status())).toList();
        var days = new TreeMap<LocalDate,List<MetricsSnapshot>>(); var models = new HashMap<String,List<MetricsSnapshot>>();
        for (var row : rows) {
            days.computeIfAbsent(row.startedAt().atOffset(ZoneOffset.UTC).toLocalDate(), ignored -> new ArrayList<>()).add(row);
            models.computeIfAbsent(row.modelName() == null ? "unknown" : row.modelName(), ignored -> new ArrayList<>()).add(row);
        }
        return new MetricsQueryStore.Data(totals(rows), days.entrySet().stream().map(e -> new MetricsQueryStore.Point(e.getKey(), totals(e.getValue()))).toList(),
                models.entrySet().stream().map(e -> new MetricsQueryStore.Model(e.getKey(), totals(e.getValue())))
                        .sorted(Comparator.comparingLong((MetricsQueryStore.Model m) -> m.totals().modelCalls()).reversed().thenComparing(MetricsQueryStore.Model::modelName)).limit(10).toList(),
                rows.stream().map(MetricsSnapshot::lastObservedAt).max(Comparator.naturalOrder()).orElse(null));
    }
    public static MetricsQueryStore.Totals empty() { return totals(List.of()); }
    private static MetricsQueryStore.Totals totals(List<MetricsSnapshot> rows) {
        long calls = rows.stream().mapToLong(MetricsSnapshot::modelCallCount).sum();
        long inKnown = rows.stream().mapToLong(MetricsSnapshot::inputKnownCallCount).sum(), outKnown = rows.stream().mapToLong(MetricsSnapshot::outputKnownCallCount).sum(), totalKnown = rows.stream().mapToLong(MetricsSnapshot::usageKnownCallCount).sum();
        return new MetricsQueryStore.Totals(rows.size(), state(rows,"SUCCEEDED"), state(rows,"FAILED"), state(rows,"CANCELLED"), state(rows,"RUNNING"), state(rows,"INTERRUPTED"), calls,
                rows.stream().mapToLong(MetricsSnapshot::functionCallCount).sum(), rows.stream().mapToLong(MetricsSnapshot::mcpToolCallCount).sum(), known(calls,inKnown,rows.stream().map(MetricsSnapshot::knownInputTokens).filter(Objects::nonNull).mapToLong(Long::longValue).sum()),
                known(calls,outKnown,rows.stream().map(MetricsSnapshot::knownOutputTokens).filter(Objects::nonNull).mapToLong(Long::longValue).sum()),
                known(calls,totalKnown,rows.stream().map(MetricsSnapshot::knownTotalTokens).filter(Objects::nonNull).mapToLong(Long::longValue).sum()), inKnown,outKnown,totalKnown, rows.stream().mapToLong(MetricsSnapshot::skillLoadCount).sum());
    }
    private static long state(List<MetricsSnapshot> rows, String state) { return rows.stream().filter(run -> run.status().equals(state)).count(); }
    static Long known(long calls, long known, long sum) { if (calls == 0) return 0L; if (known == 0) return null; return sum; }
}
