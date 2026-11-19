package com.mindrails.backend.metrics;

import java.lang.reflect.RecordComponent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Parent row lock + one independent transaction guard the whole snapshot against retries and out-of-order writes. */
public final class MysqlMetricsRepository implements MetricsRepository {
    private final MetricsProperties properties;
    private final MetricsHealth health;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();
    public MysqlMetricsRepository(JdbcTemplate jdbc, MetricsProperties properties, MetricsHealth health) {
        this.properties = properties; this.health = health;
        this.jdbc = jdbc; named = new NamedParameterJdbcTemplate(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        transactions.setTimeout(3);
        transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public void save(MetricsSnapshot snapshot) {
        transactions.executeWithoutResult(ignored -> {
            var initial = row(snapshot.toBuilder().ownerId(com.mindrails.backend.identity.ResourceAccess.owner(snapshot.ownerId())).status("RUNNING").endedAt(null).durationMs(null).terminalReason(null).errorCode(null).revision(0).build());
            initial.put("capability_coverage", "{}");
            upsert("metrics_runs", "run_id", initial, true);
            var previous = jdbc.queryForMap("SELECT * FROM metrics_runs WHERE run_id=? FOR UPDATE", snapshot.runId().toString());
            if (Boolean.TRUE.equals(previous.get("details_expired"))) return;
            var old = read(MetricsSnapshot.class, previous).toBuilder()
                    .modelCalls(jdbc.queryForList("SELECT * FROM metrics_model_calls WHERE run_id=? ORDER BY sequence", snapshot.runId().toString())
                            .stream().map(values -> read(MetricsSnapshot.ModelCall.class, values)).toList())
                    .capabilityCalls(jdbc.queryForList("SELECT * FROM metrics_capability_calls WHERE run_id=? ORDER BY started_at,capability_call_id", snapshot.runId().toString())
                            .stream().map(values -> read(MetricsSnapshot.CapabilityCall.class, values)).toList()).build();
            if (MetricsRecords.expired(old, snapshot, properties.getLateCallbackRetention())) { health.orphanCallbacks.incrementAndGet(); return; }
            var merged = MetricsRecords.merge(old, snapshot);
            var view = MetricsRecords.details(merged, properties.getMaxDetailsPerRun());
            var row = row(view.toBuilder().ownerId(com.mindrails.backend.identity.ResourceAccess.owner(view.ownerId())).build());
            row.put("capability_coverage", json.writeValueAsString(Map.of("FUNCTION", "INTEGRATED", "MCP_TOOL", "INTEGRATED",
                    "SKILL_LOAD", "NOT_INTEGRATED", "SKILL_EXECUTE", "NOT_INTEGRATED")));
            row.put("persisted_at", Timestamp.from(Instant.now()));
            upsert("metrics_runs", "run_id", row, false);
            for (var model : merged.modelCalls()) {
                if (model.modelCallId() == null) continue;
                var values = row(model); values.put("revision", merged.revision());
                upsert("metrics_model_calls", "model_call_id", values, false);
            }
            for (var capability : merged.capabilityCalls()) {
                var values = row(capability); values.put("revision", merged.revision());
                upsert("metrics_capability_calls", "capability_call_id", values, false);
            }
            long added = view.droppedDetailCount() - old.droppedDetailCount();
            if (added > 0) health.truncatedDetails.addAndGet(added);
        });
    }
    private <T extends Record> T read(Class<T> type, Map<String, Object> values) {
        var fields = type.getRecordComponents();
        var arguments = new Object[fields.length];
        try {
            for (int i = 0; i < fields.length; i++) {
                var field = fields[i];
                String name = field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
                Object value = values.get(name);
                if (value != null) {
                    if (field.getType() == UUID.class) value = UUID.fromString(value.toString());
                    else if (field.getType() == Instant.class) value = ((Timestamp) value).toInstant();
                    else if (field.getType() == Long.class || field.getType() == long.class) value = ((Number) value).longValue();
                    else if (field.getType() == boolean.class) value = value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
                    else if (field.getType() == List.class) value = json.readValue(value.toString(), new tools.jackson.core.type.TypeReference<List<UUID>>() {});
                    else if (field.getType() == MetricsSnapshot.Usage.class) value = json.readValue(value.toString(), MetricsSnapshot.Usage.class);
                } else if (field.getType() == long.class) value = 0L;
                else if (field.getType() == boolean.class) value = false;
                arguments[i] = value;
            }
            return type.getDeclaredConstructor(java.util.Arrays.stream(fields).map(RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(arguments);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Metrics record mapping failed", failure); }
    }

    private Map<String, Object> row(Record record) {
        var values = new LinkedHashMap<String, Object>();
        for (RecordComponent field : record.getClass().getRecordComponents()) {
            if (field.getName().equals("modelCalls") || field.getName().equals("capabilityCalls")) continue;
            try {
                Object value = field.getAccessor().invoke(record);
                if (value instanceof UUID id) value = id.toString();
                else if (value instanceof Instant instant) value = Timestamp.from(instant);
                else if (value instanceof List<?> || value instanceof MetricsSnapshot.Usage) value = json.writeValueAsString(value);
                values.put(field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT), value);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Metrics record mapping failed", failure); }
        }
        return values;
    }
    private void upsert(String table, String key, Map<String, Object> values, boolean createOnly) {
        String columns = String.join(",", values.keySet());
        String parameters = String.join(",", values.keySet().stream().map(name -> ":" + name).toList());
        String updates = createOnly ? key + "=" + key : String.join(",", values.keySet().stream()
                .filter(name -> !name.equals(key)).map(name -> name + "=VALUES(" + name + ")").toList());
        named.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + parameters + ") ON DUPLICATE KEY UPDATE " + updates,
                new MapSqlParameterSource(values));
    }
    @Override public void cleanup(Instant detailsBefore, Instant runsBefore) {
        transactions.executeWithoutResult(ignored -> {
            var oldDetails = jdbc.queryForList("SELECT run_id FROM metrics_runs WHERE status <> 'RUNNING' AND details_expired=FALSE AND last_observed_at<? LIMIT 100 FOR UPDATE",
                    String.class, Timestamp.from(detailsBefore));
            for (String id : oldDetails) {
                deleteDetails(id); jdbc.update("UPDATE metrics_runs SET details_expired=TRUE WHERE run_id=?", id);
            }
            var oldRuns = jdbc.queryForList("SELECT run_id FROM metrics_runs WHERE status <> 'RUNNING' AND last_observed_at<? LIMIT 100 FOR UPDATE",
                    String.class, Timestamp.from(runsBefore));
            for (String id : oldRuns) { deleteDetails(id); jdbc.update("DELETE FROM metrics_runs WHERE run_id=?", id); }
        });
    }
    private void deleteDetails(String id) {
        jdbc.update("DELETE FROM metrics_model_calls WHERE run_id=?", id);
        jdbc.update("DELETE FROM metrics_capability_calls WHERE run_id=?", id);
    }
    @Override public void recoverStoppedInstance(UUID instanceId) {
        transactions.executeWithoutResult(ignored -> {
            var ids = jdbc.queryForList("SELECT run_id FROM metrics_runs WHERE instance_id=? AND status='RUNNING' LIMIT 1000 FOR UPDATE",
                    String.class, instanceId.toString());
            for (String id : ids) {
                jdbc.update("UPDATE metrics_runs SET status='INTERRUPTED', terminal_reason='CONFIRMED_INSTANCE_STOPPED', revision=revision+1, recovery_detected_at=CURRENT_TIMESTAMP(6), persisted_at=CURRENT_TIMESTAMP(6) WHERE run_id=?", id);
                jdbc.update("UPDATE metrics_model_calls SET status='UNKNOWN_OUTCOME', revision=revision+1 WHERE run_id=? AND status='RUNNING'", id);
                jdbc.update("UPDATE metrics_capability_calls SET status='UNKNOWN_OUTCOME', revision=revision+1 WHERE run_id=? AND status='RUNNING'", id);
            }
        });
    }
}
