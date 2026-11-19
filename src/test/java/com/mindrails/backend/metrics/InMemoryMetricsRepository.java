package com.mindrails.backend.metrics;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test/development store. MySQL is recommended for long-running installations. */
public final class InMemoryMetricsRepository implements MetricsRepository {
    private final MetricsProperties properties;
    private final MetricsHealth health;
    public InMemoryMetricsRepository(MetricsProperties properties, MetricsHealth health) {
        this.properties = properties;
        this.health = health;
    }
    private final ConcurrentHashMap<UUID, MetricsSnapshot> runs = new ConcurrentHashMap<>();
    @Override public void save(MetricsSnapshot snapshot) {
        runs.compute(snapshot.runId(), (id, previous) -> {
            if (MetricsRecords.expired(previous, snapshot, properties.getLateCallbackRetention())) return orphan(previous);
            var merged = MetricsRecords.merge(previous, snapshot);
            var view = MetricsRecords.details(merged, properties.getMaxDetailsPerRun());
            long added = view.droppedDetailCount() - (previous == null ? 0 : previous.droppedDetailCount());
            if (added > 0) health.truncatedDetails.addAndGet(added);
            return merged.toBuilder().detailsTruncated(view.detailsTruncated()).droppedDetailCount(view.droppedDetailCount()).build();
        });
    }
    private MetricsSnapshot orphan(MetricsSnapshot previous) { health.orphanCallbacks.incrementAndGet(); return previous; }
    public List<MetricsSnapshot> snapshots() { return runs.values().stream().map(snapshot -> MetricsRecords.details(snapshot, properties.getMaxDetailsPerRun())).toList(); }
    @Override public void cleanup(java.time.Instant detailsBefore, java.time.Instant runsBefore) {
        runs.values().removeIf(run -> run.terminal() && run.lastObservedAt().isBefore(runsBefore));
    }
}
