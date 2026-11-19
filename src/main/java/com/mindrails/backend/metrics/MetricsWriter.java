package com.mindrails.backend.metrics;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/** Sole owner of blocking persistence, retention, and retry. Chat threads only offer to the buffer. */
@Slf4j
public final class MetricsWriter implements AutoCloseable {
    private final MetricsRepository repository;
    private final MetricsSnapshotBuffer buffer;
    private final MetricsProperties properties;
    private final MetricsHealth health;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "mindrails-metrics-writer"); thread.setDaemon(true); return thread;
    });
    private long retryAt, failedSince, nextCleanup, nextLog;
    private int failureStreak;
    private boolean recovered;
    public MetricsWriter(MetricsRepository repository, MetricsSnapshotBuffer buffer, MetricsProperties properties,
            MetricsHealth health) {
        this.repository = repository; this.buffer = buffer; this.properties = properties; this.health = health;
    }
    public void start() {
        if (!properties.valid()) return;
        executor.scheduleWithFixedDelay(this::tick, 0, Math.max(1, properties.getFlushInterval().toMillis()), TimeUnit.MILLISECONDS);
    }
    private void tick() {
        try {
            long now = System.nanoTime();
            flush();
            if (System.nanoTime() < retryAt) return;
            if (!recovered) {
                if (properties.getRecoverInstanceId() != null) repository.recoverStoppedInstance(UUID.fromString(properties.getRecoverInstanceId()));
                recovered = true;
            }
            if (now >= nextCleanup) {
                var instant = Instant.now();
                repository.cleanup(instant.minus(properties.getDetailsRetention()), instant.minus(properties.getRunsRetention()));
                nextCleanup = now + TimeUnit.HOURS.toNanos(1);
            }
            if (now >= nextLog) {
                log.info("Metrics health: pending={}, droppedRuns={}, droppedCheckpoints={}, writeFailures={}, collectionFailures={}, orphanCallbacks={}, truncatedDetails={}",
                        buffer.size(), health.droppedRuns.get(), health.droppedCheckpoints.get(), health.writeFailures.get(),
                        health.collectionFailures.get(), health.orphanCallbacks.get(), health.truncatedDetails.get());
                nextLog = now + TimeUnit.MINUTES.toNanos(1);
            }
        } catch (RuntimeException failure) { failure(); }
    }
    public synchronized void flush() {
        long now = System.nanoTime();
        if (now < retryAt) return;
        var batch = buffer.drain(properties.getBatchSize());
        for (int i = 0; i < batch.size(); i++) {
            var snapshot = batch.get(i);
            long start = System.nanoTime();
            try {
                repository.save(snapshot); health.writtenSnapshots.incrementAndGet();
                health.lastWriteLatencyMs.set((System.nanoTime() - start) / 1_000_000);
                failureStreak = 0; failedSince = 0;
            } catch (RuntimeException failure) {
                failure();
                boolean expired = System.nanoTime() - failedSince > properties.getRetryMaxAge().toNanos();
                for (int j = i; j < batch.size(); j++) {
                    if (expired) {
                        if (batch.get(j).terminal()) health.droppedRuns.incrementAndGet(); else health.droppedCheckpoints.incrementAndGet();
                    } else buffer.offer(batch.get(j));
                }
                if (expired) failedSince = 0;
                return;
            }
        }
    }
    private void failure() {
        long now = System.nanoTime();
        health.writeFailures.incrementAndGet();
        if (failedSince == 0) failedSince = now;
        long seconds = Math.min(30, 1L << Math.min(5, failureStreak++));
        retryAt = now + TimeUnit.SECONDS.toNanos(seconds) + ThreadLocalRandom.current().nextLong(TimeUnit.MILLISECONDS.toNanos(250));
        if (now >= nextLog) {
            log.warn("Metrics persistence unavailable; background retry in {}s. Conversation execution continues. pending={}, droppedRuns={}",
                    seconds, buffer.size(), health.droppedRuns.get());
            nextLog = now + TimeUnit.MINUTES.toNanos(1);
        }
    }
    @Override public void close() {
        executor.execute(() -> { retryAt = 0; flush(); });
        executor.shutdown();
        try { if (!executor.awaitTermination(3, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
        if (buffer.size() > 0) log.warn("Metrics shutdown left {} Run snapshots unpersisted", buffer.size());
    }
}
