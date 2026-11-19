package com.mindrails.backend.history.store;

import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.service.internal.HistoryRecords;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

public final class MysqlHistoryStore implements HistoryStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MysqlHistoryStore(JdbcTemplate shared) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(shared.getDataSource()));
        jdbc.setQueryTimeout(5);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(shared.getDataSource()));
        transactions.setTimeout(5);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void insert(HistoryRecord record) {
        transactions.executeWithoutResult(ignored -> {
            // INSERT holds the row lock even when another callback created this run first.
            var empty = record.toBuilder().status(HistoryRecord.Status.RUNNING).messages("[]").metadata("{}").finishedAt(null).build();
            write(empty, true);
            var previous = jdbc.queryForObject("SELECT * FROM history WHERE id=? FOR UPDATE",
                    (rs, row) -> MysqlHistoryQueryStore.read(rs), key(record.id()));
            var merged = HistoryRecords.merge(previous, record);
            write(merged, false);
        });
    }

    private void write(HistoryRecord record, boolean createOnly) {
        write(jdbc, record, " ON DUPLICATE KEY UPDATE " + (createOnly ? "id = history.id"
                : "invocation_id=VALUES(invocation_id),status=VALUES(status),messages=VALUES(messages),metadata=VALUES(metadata),finished_at=VALUES(finished_at)"));
    }

    static void insertSnapshot(JdbcTemplate jdbc, HistoryRecord record) {
        write(jdbc, record, "");
    }

    private static void write(JdbcTemplate jdbc, HistoryRecord record, String suffix) {
        jdbc.update(
                """
                INSERT INTO history (id, owner_id, agent_id, session_id, memory_id, provider_id,
                    request_id, invocation_id, transport, status, messages, metadata,
                    started_at, finished_at, schema_version, task_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """ + suffix,
                key(record.id()),
                key(com.mindrails.backend.identity.ResourceAccess.owner(record.ownerId())),
                key(record.agentId()),
                key(record.sessionId()),
                key(record.memoryId()),
                key(record.providerId()),
                key(record.requestId()),
                key(record.invocationId()),
                record.transport().name(),
                record.status().name(),
                record.messages(),
                record.metadata(),
                Timestamp.from(record.startedAt()),
                record.finishedAt() == null ? null : Timestamp.from(record.finishedAt()),
                record.schemaVersion(), key(record.taskId()));
    }

    private static String key(UUID value) {
        return value == null ? null : value.toString();
    }
}
