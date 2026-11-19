package com.mindrails.backend.observability;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface MetricsQueryStore {
    record Totals(long runs, long succeededRuns, long failedRuns, long cancelledRuns, long runningRuns, long interruptedRuns,
            long modelCalls, long functionCalls, long mcpToolCalls, Long inputTokens, Long outputTokens, Long totalTokens,
            long inputKnownCalls, long outputKnownCalls, long usageKnownCalls, long skillLoads) {}
    record Point(LocalDate day, Totals totals) {}
    record Model(String modelName, Totals totals) {}
    record Data(Totals summary, List<Point> trend, List<Model> models, Instant lastPersistedAt) {}
    record SessionData(Totals summary, Instant lastPersistedAt) {}
    Data overview(ArchiveFilter filter);
    /** All retained runs for a session, without the overview's time range or browse limits. */
    default SessionData session(UUID sessionId) {
        var data = overview(new ArchiveFilter(null, null, null, sessionId, null));
        return new SessionData(data.summary(), data.lastPersistedAt());
    }
}
