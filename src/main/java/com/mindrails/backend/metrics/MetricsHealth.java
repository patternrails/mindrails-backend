package com.mindrails.backend.metrics;

import java.util.concurrent.atomic.AtomicLong;

/** Independent of MySQL so persistence failures remain observable. No high-cardinality labels. */
public final class MetricsHealth {
    public final AtomicLong droppedRuns = new AtomicLong();
    public final AtomicLong droppedCheckpoints = new AtomicLong();
    public final AtomicLong writeFailures = new AtomicLong();
    public final AtomicLong collectionFailures = new AtomicLong();
    public final AtomicLong orphanCallbacks = new AtomicLong();
    public final AtomicLong truncatedDetails = new AtomicLong();
    public final AtomicLong writtenSnapshots = new AtomicLong();
    public final AtomicLong lastWriteLatencyMs = new AtomicLong();
}
