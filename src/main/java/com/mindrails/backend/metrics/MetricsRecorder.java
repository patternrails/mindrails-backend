package com.mindrails.backend.metrics;

import lombok.extern.slf4j.Slf4j;

/** Stateless recording boundary; collection and persistence failures never escape to callers. */
@Slf4j
public final class MetricsRecorder {
    private final MetricsProperties properties;
    private final MetricsSnapshotBuffer buffer;
    private final MetricsHealth health;

    public MetricsRecorder(MetricsProperties properties, MetricsSnapshotBuffer buffer, MetricsHealth health) {
        this.properties = properties;
        this.buffer = buffer;
        this.health = health;
    }

    public void record(MetricsSnapshot record) {
        try {
            if (record != null && properties.valid()) buffer.offer(record);
        } catch (Exception failure) {
            health.collectionFailures.incrementAndGet();
            log.warn("Metrics recording failed: {}", failure.getClass().getSimpleName());
        }
    }
}
