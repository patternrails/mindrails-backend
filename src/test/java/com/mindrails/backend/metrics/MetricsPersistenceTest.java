package com.mindrails.backend.metrics;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.Session;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MetricsPersistenceTest {
    static Session session() { return Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build(); }
    static MetricsProperties properties() { var p = new MetricsProperties(); return p; }
    @Test void boundedDetailsContinueAggregatingKnownUsage() {
        var p = properties(); p.setMaxDetailsPerRun(1);
        var store = new InMemoryMetricsRepository(p, new MetricsHealth());
        try (var metrics = new TestMetrics(p, store, new MetricsHealth(), false)) {
            var run = metrics.begin(session(), null, "REST");
            run.modelStarted(UUID.randomUUID(), "model").response(MetricsChatTest.response("secret answer", new TokenUsage(1, 2, 3)));
            run.modelStarted(UUID.randomUUID(), "model").response(MetricsChatTest.response("secret answer", new TokenUsage(4, 5, 9)));
            run.finish("SUCCEEDED", null, null); metrics.flush();
            var value = store.snapshots().getFirst(); assertEquals(2, value.modelCallCount()); assertEquals(12L, value.knownTotalTokens());
            assertEquals(1, value.modelCalls().size()); assertTrue(value.detailsTruncated()); assertEquals(1, value.droppedDetailCount());
        }
    }
    @Test void providerExtensionsAreSubsetsAndNeverAddedToTotal() {
        var usage = dev.langchain4j.model.openai.OpenAiTokenUsage.builder().inputTokenCount(10).outputTokenCount(5).totalTokenCount(15)
                .inputTokensDetails(dev.langchain4j.model.openai.OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(4).build())
                .outputTokensDetails(dev.langchain4j.model.openai.OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(2).build()).build();
        var store = new InMemoryMetricsRepository(new MetricsProperties(), new MetricsHealth());
        try (var metrics = new TestMetrics(properties(), store, new MetricsHealth(), false)) {
            var run = metrics.begin(session(), null, "REST");
            run.modelStarted(UUID.randomUUID(), null).response(MetricsChatTest.response("hi", usage)); run.finish("SUCCEEDED", null, null); metrics.flush();
            var model = store.snapshots().getFirst().modelCalls().getFirst();
            assertEquals(4L, model.cachedInputTokens()); assertEquals(2L, model.reasoningTokens()); assertEquals(15L, store.snapshots().getFirst().knownTotalTokens());
        }
    }
    @Test void unknownInputWithKnownTotalIsPartial() {
        var store = new InMemoryMetricsRepository(new MetricsProperties(), new MetricsHealth());
        try (var metrics = new TestMetrics(properties(), store, new MetricsHealth(), false)) {
            var run = metrics.begin(session(), null, "REST");
            run.modelStarted(UUID.randomUUID(), null).response(MetricsChatTest.response("hi", new TokenUsage(null, 5, 10))); run.finish("SUCCEEDED", null, null); metrics.flush();
            var value = store.snapshots().getFirst(); assertEquals("PARTIAL", value.usageCoverage()); assertNull(value.knownInputTokens()); assertEquals(10L, value.knownTotalTokens());
        }
    }
    @Test void bufferPrioritizesFinalSnapshotAndRejectsOlderVersions() {
        var health = new MetricsHealth(); var buffer = new MetricsSnapshotBuffer(1, 8192, java.time.Duration.ofSeconds(60), health);
        var metrics = new TestMetrics(properties(), ignored -> {}, health, false);
        var first = metrics.begin(session(), null, "REST"); var old = first.snapshot(); first.finish("SUCCEEDED", null, null); var terminal = first.snapshot();
        buffer.offer(old); buffer.offer(terminal); buffer.offer(old);
        var next = metrics.begin(session(), null, "REST"); assertFalse(buffer.offer(next.snapshot()));
        var saved = buffer.drain(5).getFirst(); assertEquals(terminal.status(), saved.status()); assertEquals(terminal.endedAt(), saved.endedAt()); assertTrue(health.droppedCheckpoints.get() > 0); metrics.close();
    }
    @Test void payloadByteLimitIsEnforcedAndReported() {
        var health = new MetricsHealth(); var buffer = new MetricsSnapshotBuffer(10, 8192, java.time.Duration.ofSeconds(60), health);
        var metrics = new TestMetrics(properties(), ignored -> {}, health, false);
        var run = metrics.begin(session(), null, "REST"); run.modelStarted(UUID.randomUUID(), "m"); run.finish("CANCELLED", "USER_CANCELLED", null);
        assertFalse(buffer.offer(run.snapshot())); assertEquals(0, buffer.bytes()); assertEquals(1, health.droppedRuns.get()); metrics.close();
    }
    @Test void slowDatabaseNeverBlocksChatThreads() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var p = properties(); p.setFlushInterval(java.time.Duration.ofMillis(5));
        var metrics = new TestMetrics(p, snapshot -> {
            assertTrue(Thread.currentThread().getName().startsWith("mindrails-metrics-writer")); entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, new MetricsHealth());
        try {
            var first = metrics.begin(session(), null, "REST"); assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofMillis(500), () -> {
                first.finish("SUCCEEDED", null, null);
                var next = metrics.begin(session(), null, "REST"); next.finish("FAILED", "TEST", null);
            });
        } finally { release.countDown(); metrics.close(); }
    }
    @Test void failedOldSnapshotCannotReplaceNewTerminalInRetryBuffer() {
        var health = new MetricsHealth(); var buffer = new MetricsSnapshotBuffer(5, 81920, java.time.Duration.ofSeconds(60), health);
        var metrics = new TestMetrics(properties(), ignored -> {}, health, false);
        var run = metrics.begin(session(), null, "REST"); buffer.offer(run.snapshot());
        var writer = new MetricsWriter(old -> { run.finish("SUCCEEDED", null, null); buffer.offer(run.snapshot()); throw new IllegalStateException("commit reply lost"); },
                buffer, properties(), health);
        writer.flush(); var latest = buffer.drain(1).getFirst(); assertEquals("SUCCEEDED", latest.status()); assertEquals(1, health.writeFailures.get());
        writer.close(); metrics.close();
    }
    @Test void unavailableStatisticsDatabaseDoesNotPreventStartup() {
        new ApplicationContextRunner().withUserConfiguration(MetricsConfiguration.class)
                .withPropertyValues("mindrails.mysql.url=jdbc:mysql://127.0.0.1:1/unavailable")
                .run(context -> { assertNull(context.getStartupFailure()); assertTrue(context.getBean(MetricsProperties.class).valid()); });
    }
    @Test void lateCallbackExpirationDoesNotMutateFinalizedSnapshot() {
        var p = properties(); p.setLateCallbackRetention(java.time.Duration.ofNanos(1));
        var store = new InMemoryMetricsRepository(p, new MetricsHealth());
        try (var metrics = new TestMetrics(p, store, new MetricsHealth(), false)) {
            var run = metrics.begin(session(), null, "WEBSOCKET"); var call = run.modelStarted(UUID.randomUUID(), "model");
            run.finish("CANCELLED", "USER_CANCELLED", null);
            call.response(MetricsChatTest.response("late", new TokenUsage(1, 2, 3))); metrics.flush();
            assertNull(store.snapshots().getFirst().knownTotalTokens()); assertEquals("CANCELLED", store.snapshots().getFirst().status());
            assertEquals(1, metrics.health().orphanCallbacks.get());
        }
    }
    @Test void invalidConfigurationNeedsNoDatabase() {
        new ApplicationContextRunner().withUserConfiguration(MetricsConfiguration.class)
                .withPropertyValues("mindrails.metrics.max-runs=0")
                .run(context -> { assertNull(context.getStartupFailure()); assertFalse(context.getBean(MetricsProperties.class).valid()); });
    }
}
