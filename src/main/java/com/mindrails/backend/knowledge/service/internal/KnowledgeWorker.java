package com.mindrails.backend.knowledge.service.internal;

import com.mindrails.backend.common.ExceptionDiagnostics;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Durable SQL queue with bounded workers, unique attempt leases and an execution deadline. */
@Slf4j
public final class KnowledgeWorker implements AutoCloseable {
    private final KnowledgeTaskService tasks;
    private final KnowledgeJobRunner runner;
    private final KnowledgeCleanupService cleanup;
    private final KnowledgeProperties properties;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService scheduler;
    private int ticks, maintenanceCursor;
    private final Map<UUID, Running> running = new HashMap<>();

    private record Running(KnowledgeIngestionJob job, Future<?> future, Instant deadline) {}

    public KnowledgeWorker(
            KnowledgeTaskService tasks,
            KnowledgeJobRunner runner,
            KnowledgeCleanupService cleanup,
            KnowledgeProperties properties) {
        this.tasks = tasks;
        this.runner = runner;
        this.cleanup = cleanup;
        this.properties = properties;
        int count = properties.getWorkers();
        workers =
                new ThreadPoolExecutor(
                        count,
                        count,
                        0,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        Thread.ofPlatform().daemon().name("knowledge-ingest-", 0).factory());
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("knowledge-queue-", 0).factory());
        scheduler.scheduleWithFixedDelay(this::tick, 1000, 500, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            if (++ticks % 60 == 0) {
                cleanup.maintenance(maintenanceCursor++);
            }
            reapFinishedAndExpiredJobs();
            fillAvailableSlots();
        } catch (Exception e) {
            log.warn("Knowledge queue unavailable", ExceptionDiagnostics.forLogging(e));
        }
    }

    private void reapFinishedAndExpiredJobs() {
        var iterator = running.values().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            if (item.future().isDone()) {
                iterator.remove();
            } else if (Instant.now().isAfter(item.deadline())) {
                item.future().cancel(true);
                tasks.fail(
                        item.job(), new TimeoutException("Document processing deadline exceeded"));
                iterator.remove();
            }
        }
    }

    private void fillAvailableSlots() {
        while (running.size() < workers.getMaximumPoolSize()) {
            var job = tasks.claimNextJob(UUID.randomUUID().toString());
            if (job == null) {
                break;
            }
            try {
                var future = workers.submit(() -> runner.process(job));
                running.put(
                        job.getId(),
                        new Running(
                                job,
                                future,
                                Instant.now().plusMillis(properties.getProcessingTimeoutMillis())));
            } catch (RejectedExecutionException busy) {
                tasks.fail(job, busy);
                break;
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }
}
