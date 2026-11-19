package com.mindrails.backend.metrics;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Latest snapshot per Run, bounded by count AND conservative payload bytes. Never performs I/O. */
public final class MetricsSnapshotBuffer {
    private final LinkedHashMap<UUID, MetricsSnapshot> pending = new LinkedHashMap<>();
    private final int maxRuns;
    private final long maxBytes;
    private final MetricsHealth health;
    private final Duration lateCallbackRetention;
    private long bytes;
    public MetricsSnapshotBuffer(int maxRuns, long maxBytes, Duration lateCallbackRetention, MetricsHealth health) {
        this.lateCallbackRetention = lateCallbackRetention;
        this.maxRuns = maxRuns; this.maxBytes = maxBytes; this.health = health;
    }
    public synchronized boolean offer(MetricsSnapshot value) {
        var old = pending.get(value.runId());
        if (MetricsRecords.expired(old, value, lateCallbackRetention)) { health.orphanCallbacks.incrementAndGet(); return true; }
        value = MetricsRecords.merge(old, value);
        long charge = value.estimatedBytes();
        if (charge > maxBytes) { dropped(value); return false; }
        if (old != null) { pending.remove(value.runId()); bytes -= old.estimatedBytes(); }
        while (pending.size() >= maxRuns || bytes + charge > maxBytes) {
            var victim = pending.values().stream().filter(run -> !run.terminal()).findFirst().orElse(null);
            if (victim == null) {
                if (old != null) { pending.put(old.runId(), old); bytes += old.estimatedBytes(); }
                dropped(value); return false;
            }
            pending.remove(victim.runId()); bytes -= victim.estimatedBytes(); dropped(victim);
        }
        pending.put(value.runId(), value); bytes += charge; return true;
    }
    private void dropped(MetricsSnapshot value) {
        if (value.terminal()) health.droppedRuns.incrementAndGet(); else health.droppedCheckpoints.incrementAndGet();
    }
    public synchronized List<MetricsSnapshot> drain(int limit) {
        var values = new ArrayList<>(pending.values());
        values.sort(java.util.Comparator.comparing(MetricsSnapshot::terminal).reversed());
        var result = new ArrayList<MetricsSnapshot>();
        for (var value : values) {
            if (result.size() >= limit) break;
            pending.remove(value.runId()); bytes -= value.estimatedBytes(); result.add(value);
        }
        return result;
    }
    public synchronized int size() { return pending.size(); }
    public synchronized long bytes() { return bytes; }
}
