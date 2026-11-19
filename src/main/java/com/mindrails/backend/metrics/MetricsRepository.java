package com.mindrails.backend.metrics;

import java.time.Instant;
import java.util.UUID;

public interface MetricsRepository {
    void save(MetricsSnapshot snapshot);
    default void cleanup(Instant detailsBefore, Instant runsBefore) {}
    default void recoverStoppedInstance(UUID instanceId) {}
}
