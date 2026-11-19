package com.mindrails.backend.history.store;

import com.mindrails.backend.history.model.HistoryFilter;
import com.mindrails.backend.history.model.HistoryPage;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.model.HistorySummary;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MysqlHistoryQueryStore implements HistoryQueryStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate read;

    public MysqlHistoryQueryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        named = new NamedParameterJdbcTemplate(jdbc);
        read =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getDataSource())));
        read.setReadOnly(true);
        read.setTimeout(3);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public HistoryPage list(HistoryFilter filter, int page, int size) {
        return read.execute(
                ignored -> {
                    var p = new MapSqlParameterSource();
                    String where = sql(filter, p);
                    long total =
                            Objects.requireNonNull(
                                    named.queryForObject(
                                            "SELECT COUNT(*) FROM history" + where, p, Long.class));
                    long offset = (long) page * size;
                    p.addValue("offset", offset).addValue("size", Math.min(size, 1000 - offset));
                    // Sort only bounded scalar columns. Neither JSON column enters MySQL's sort buffer.
                    var rows = named.query(
                            "SELECT id,agent_id,session_id,task_id,provider_id,transport,status,started_at,finished_at,"
                                    + " CASE WHEN JSON_TYPE(JSON_EXTRACT(metadata,'$.usage.totalTokens'))='INTEGER'"
                                    + " AND CAST(JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.usage.totalTokens')) AS SIGNED)>=0"
                                    + " THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.usage.totalTokens')) AS SIGNED) END AS total_tokens,"
                                    + " CASE WHEN JSON_TYPE(JSON_EXTRACT(metadata,'$.finishReason'))='STRING'"
                                    + " THEN LEFT(JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.finishReason')),120) END AS finish_reason"
                                    + " FROM history" + where
                                    + " ORDER BY started_at DESC,id DESC LIMIT :size OFFSET :offset",
                            p,
                            (rs, n) -> new HistorySummary(
                                    id(rs, "id"), id(rs, "agent_id"), id(rs, "session_id"), id(rs, "provider_id"),
                                    rs.getString("transport"), rs.getString("status"),
                                    rs.getTimestamp("started_at").toInstant(),
                                    rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                                    rs.getObject("total_tokens", Long.class), rs.getString("finish_reason"), id(rs,"task_id")));
                    return HistoryQueryStore.page(rows, total, page, size);
                });
    }

    @Override
    public Optional<HistoryRecord> find(UUID key) {
        var user=com.mindrails.backend.identity.ResourceAccess.userId();
        return jdbc
                .query(
                        "SELECT * FROM history WHERE id=?" + (user==null?"":" AND owner_id=?"),
                        (rs, n) -> read(rs),
                        user==null?new Object[]{key.toString()}:new Object[]{key.toString(),user.toString()})
                .stream()
                .filter(r -> com.mindrails.backend.identity.ResourceAccess.visible(r.ownerId())).findFirst();
    }

    @Override
    public Optional<String> metadata(UUID id) {
        return jsonColumn(id, "metadata");
    }

    @Override
    public Optional<String> originalMessage(UUID id, int index) {
        if (index < 0) return Optional.empty();
        return jsonColumn(id, "JSON_EXTRACT(messages,'$[" + index + "]')");
    }

    private Optional<String> jsonColumn(UUID id, String expression) {
        var user = com.mindrails.backend.identity.ResourceAccess.userId();
        return jdbc.query("SELECT " + expression + " FROM history WHERE id=?" + (user == null ? "" : " AND owner_id=?"),
                (rs, n) -> rs.getString(1), user == null ? new Object[]{id.toString()} : new Object[]{id.toString(), user.toString()})
                .stream().filter(Objects::nonNull).findFirst();
    }

    static HistoryRecord read(ResultSet rs) throws SQLException {
        return new HistoryRecord(
                                        id(rs, "id"),
                                        id(rs, "owner_id"),
                                        id(rs, "agent_id"),
                                        id(rs, "session_id"),
                                        id(rs, "memory_id"),
                                        id(rs, "provider_id"),
                                        id(rs, "request_id"),
                                        id(rs, "invocation_id"),
                                        HistoryRecord.Transport.valueOf(rs.getString("transport")),
                                        HistoryRecord.Status.valueOf(rs.getString("status")),
                                        rs.getString("messages"),
                                        rs.getString("metadata"),
                                        rs.getTimestamp("started_at").toInstant(),
                                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                                        rs.getInt("schema_version"), id(rs,"task_id"));
    }

    @Override
    public boolean delete(UUID key) {
        var user=com.mindrails.backend.identity.ResourceAccess.userId();
        return user==null ? jdbc.update("DELETE FROM history WHERE id=?", key.toString()) > 0
                : jdbc.update("DELETE FROM history WHERE id=? AND owner_id=?", key.toString(),user.toString())>0;
    }

    private String sql(HistoryFilter filter, MapSqlParameterSource parameters) {
        var sql = new StringBuilder(" WHERE status <> 'RUNNING'");
        var user=com.mindrails.backend.identity.ResourceAccess.userId();
        if(user!=null) { sql.append(" AND owner_id=:resource_user"); parameters.addValue("resource_user",user.toString()); }
        if (filter.from() != null) {
            sql.append(" AND started_at>=:from");
            parameters.addValue("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" AND started_at<:to");
            parameters.addValue("to", Timestamp.from(filter.to()));
        }
        if (filter.agentId() != null) {
            sql.append(" AND agent_id=:agent");
            parameters.addValue("agent", filter.agentId().toString());
        }
        if (filter.sessionId() != null) {
            sql.append(" AND session_id=:session");
            parameters.addValue("session", filter.sessionId().toString());
        }
        if (filter.status() != null) {
            sql.append(" AND status=:status");
            parameters.addValue("status", filter.status());
        }
        return sql.toString();
    }

    private static UUID id(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }
}
