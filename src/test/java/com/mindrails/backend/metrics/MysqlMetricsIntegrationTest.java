package com.mindrails.backend.metrics;

import static org.junit.jupiter.api.Assertions.*;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.model.output.TokenUsage;
import java.lang.reflect.RecordComponent;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real local MySQL; fake models and random IDs only. Never changes pre-existing rows or schema. */
class MysqlMetricsIntegrationTest {
    private final HikariDataSource source = source();
    private final JdbcTemplate jdbc = new JdbcTemplate(source);
    private final MysqlMetricsRepository repository = new MysqlMetricsRepository(jdbc, new MetricsProperties(), new MetricsHealth());
    private final List<UUID> owned = new ArrayList<>();
    static HikariDataSource source() {
        var s = new HikariDataSource();
        s.setJdbcUrl(System.getenv().getOrDefault("MINDRAILS_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/mindrails"));
        s.setUsername(System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME", "mindrails_app"));
        s.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        s.setMaximumPoolSize(2); s.addDataSourceProperty("connectionTimeZone", "UTC"); s.addDataSourceProperty("forceConnectionTimeZoneToSession", "true"); return s;
    }
    @AfterEach void cleanupOnlyOwnedRows() {
        try { for (var id : owned) {
            jdbc.update("DELETE FROM metrics_capability_calls WHERE run_id=?", id.toString());
            jdbc.update("DELETE FROM metrics_model_calls WHERE run_id=?", id.toString());
            jdbc.update("DELETE FROM metrics_runs WHERE run_id=?", id.toString());
        } } finally { source.close(); }
    }
    MetricsSnapshot value() {
        try (var metrics = new TestMetrics(MetricsPersistenceTest.properties(), ignored -> {}, new MetricsHealth(), false)) {
            var run = metrics.begin(MetricsPersistenceTest.session(), UUID.randomUUID(), "REST"); owned.add(run.runId());
            run.modelStarted(UUID.randomUUID(), "fake").response(MetricsChatTest.response("not stored", new TokenUsage(3, 4, 7)));
            run.capabilityStarted(UUID.randomUUID(), "tool-id", "ping", "FUNCTION").finish(false); run.finish("SUCCEEDED", null, null);
            return run.snapshot();
        }
    }
    static <T extends Record> T copy(T value, Map<String, Object> changes) {
        try {
            var fields = value.getClass().getRecordComponents(); var types = Arrays.stream(fields).map(RecordComponent::getType).toArray(Class<?>[]::new);
            Object[] args = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) args[i] = changes.containsKey(fields[i].getName()) ? changes.get(fields[i].getName()) : fields[i].getAccessor().invoke(value);
            @SuppressWarnings("unchecked") T result = (T) value.getClass().getDeclaredConstructor(types).newInstance(args); return result;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @Test void writesAllThreeTablesIdempotentlyAndRejectsStaleState() {
        var value = value(); repository.save(value); repository.save(value);
        repository.save(copy(value, Map.of("revision", value.revision() - 1, "status", "RUNNING")));
        var row = jdbc.queryForMap("SELECT * FROM metrics_runs WHERE run_id=?", value.runId().toString());
        assertEquals("SUCCEEDED", row.get("status")); assertEquals(7L, ((Number)row.get("known_total_tokens")).longValue());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM metrics_model_calls WHERE run_id=?", Integer.class, value.runId().toString()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM metrics_capability_calls WHERE run_id=?", Integer.class, value.runId().toString()));
        assertTrue(row.get("capability_coverage").toString().contains("NOT_INTEGRATED"));
        try (var second = source()) {
            assertEquals(7L, new JdbcTemplate(second).queryForObject("SELECT known_total_tokens FROM metrics_runs WHERE run_id=?", Long.class, value.runId().toString()));
        }
    }
    @Test void childFailureRollsBackWholeSnapshot() {
        var value = value(); repository.save(value);
        long savedRevision = jdbc.queryForObject("SELECT revision FROM metrics_runs WHERE run_id=?", Long.class, value.runId().toString());
        var changes = new HashMap<String,Object>(); changes.put("capabilityCallId", null);
        var invalid = copy(value.capabilityCalls().getFirst(), changes);
        var broken = copy(value, Map.of("revision", value.revision() + 1, "knownTotalTokens", 999L, "capabilityCalls", List.of(invalid)));
        assertThrows(RuntimeException.class, () -> repository.save(broken));
        assertEquals(7L, jdbc.queryForObject("SELECT known_total_tokens FROM metrics_runs WHERE run_id=?", Long.class, value.runId().toString()));
        assertEquals(savedRevision, jdbc.queryForObject("SELECT revision FROM metrics_runs WHERE run_id=?", Long.class, value.runId().toString()));
    }
    @Test void separatelyPersistedEntriesMergeWithoutLosingUsageOrCountingRetriesTwice() {
        var parameters = com.mindrails.backend.agent.model.LlmClient.parameters(MetricsPersistenceTest.session(), null, "REST");
        UUID id = parameters.get(com.mindrails.backend.agent.model.LlmClient.RUN_ID);
        owned.add(id);
        var context = dev.langchain4j.invocation.InvocationContext.builder().invocationId(UUID.randomUUID()).invocationParameters(parameters).build();
        var at = java.time.Instant.now();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        repository.save(MetricsRecords.start(parameters));
        var response = MetricsRecords.model(context, first, at, "fake", MetricsChatTest.response("ignored", new TokenUsage(1, 2, 3)), false);
        repository.save(response);
        repository.save(response);
        repository.save(MetricsRecords.model(context, second, java.time.Instant.now(), "fake", MetricsChatTest.response("ignored", new TokenUsage(4, 5, 9)), false));
        repository.save(MetricsRecords.finish(parameters, "SUCCEEDED", null, null, null));
        var row = jdbc.queryForMap("SELECT status,model_call_count,known_total_tokens FROM metrics_runs WHERE run_id=?", id.toString());
        assertEquals("SUCCEEDED", row.get("status"));
        assertEquals(2L, ((Number) row.get("model_call_count")).longValue());
        assertEquals(12L, ((Number) row.get("known_total_tokens")).longValue());
    }

    @Test void onlyExplicitlyConfirmedStoppedInstanceIsRecovered() {
        var value = value();
        var running = copy(value, Map.of("status", "RUNNING")); repository.save(running);
        var other = copy(value, Map.of("runId", UUID.randomUUID(), "instanceId", UUID.randomUUID(), "modelCalls", List.of(), "capabilityCalls", List.of(), "status", "RUNNING"));
        owned.add(other.runId()); repository.save(other);
        repository.recoverStoppedInstance(running.instanceId());
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT status FROM metrics_runs WHERE run_id=?", String.class, running.runId().toString()));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM metrics_runs WHERE run_id=?", String.class, other.runId().toString()));
        repository.save(copy(running, Map.of("revision", running.revision() + 10)));
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT status FROM metrics_runs WHERE run_id=?", String.class, running.runId().toString()));
    }

    @Test void nativeModelEventsMergeAcrossFlushesWithoutBindingsOrDuplicateCalls() {
        var parameters = com.mindrails.backend.agent.model.LlmClient.parameters(MetricsPersistenceTest.session(), null, "REST");
        UUID id = parameters.get(com.mindrails.backend.agent.model.LlmClient.RUN_ID);
        owned.add(id);
        var context = dev.langchain4j.invocation.InvocationContext.builder()
                .invocationId(UUID.randomUUID()).invocationParameters(parameters).build();
        try (var metrics = new TestMetrics(MetricsPersistenceTest.properties(), repository, new MetricsHealth(), false)) {
            metrics.recorder().record(MetricsRecords.start(parameters));
            metrics.recorder().record(MetricsRecords.modelRequested(context, "fake"));
            metrics.flush();
            var response = MetricsRecords.modelResponded(context, MetricsChatTest.response("ignored", new TokenUsage(1, 2, 3)));
            metrics.recorder().record(response);
            metrics.flush();
            // Replayed responses and an AI Service error after a successful model call do not add a model failure.
            metrics.recorder().record(response);
            metrics.recorder().record(MetricsRecords.modelFailed(context));
            metrics.flush();
            metrics.recorder().record(MetricsRecords.modelRequested(context, "fake"));
            metrics.flush();
            metrics.recorder().record(MetricsRecords.modelFailed(context));
            metrics.recorder().record(MetricsRecords.finish(parameters, "FAILED", "TEST", null, null));
            metrics.flush();
            assertEquals(0, metrics.health().writeFailures.get());
        }
        var row = jdbc.queryForMap("SELECT model_call_count,model_succeeded_count,model_failed_count,known_total_tokens FROM metrics_runs WHERE run_id=?", id.toString());
        assertEquals(2L, ((Number) row.get("model_call_count")).longValue());
        assertEquals(1L, ((Number) row.get("model_succeeded_count")).longValue());
        assertEquals(1L, ((Number) row.get("model_failed_count")).longValue());
        assertEquals(3L, ((Number) row.get("known_total_tokens")).longValue());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM metrics_model_calls WHERE run_id=?", Integer.class, id.toString()));
    }
    @Test void acceptedChatIsEventuallyPersistedThroughAsyncWriter() throws Exception {
        var p = MetricsPersistenceTest.properties(); p.setFlushInterval(java.time.Duration.ofMillis(20));
        try (var metrics = new TestMetrics(p, repository, new MetricsHealth())) {
            var run = metrics.begin(MetricsPersistenceTest.session(), null, "REST"); owned.add(run.runId());
            run.modelStarted(UUID.randomUUID(), "fake").response(MetricsChatTest.response("hello", new TokenUsage(1, 2, 3)));
            run.finish("SUCCEEDED", null, null);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < deadline) {
                var rows = jdbc.queryForList("SELECT status FROM metrics_runs WHERE run_id=?", String.class, run.runId().toString());
                if (rows.contains("SUCCEEDED")) break;
                Thread.sleep(20);
            }
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM metrics_runs WHERE run_id=?", String.class, run.runId().toString()));
            assertEquals(3L, jdbc.queryForObject("SELECT known_total_tokens FROM metrics_runs WHERE run_id=?", Long.class, run.runId().toString()));
        }
    }
}
