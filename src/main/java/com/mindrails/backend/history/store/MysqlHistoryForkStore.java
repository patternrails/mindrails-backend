package com.mindrails.backend.history.store;

import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.identity.ResourceAccess;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses the Session writer's datasource so the entire fork joins one transaction. */
public final class MysqlHistoryForkStore implements HistoryForkStore {
    private final JdbcTemplate jdbc;

    public MysqlHistoryForkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<HistoryRecord> session(UUID sessionId) {
        var owner = ResourceAccess.userId();
        return jdbc.query("SELECT * FROM history WHERE session_id=?"
                        + (owner == null ? "" : " AND owner_id=?") + " ORDER BY started_at,id FOR UPDATE",
                (rs, row) -> MysqlHistoryQueryStore.read(rs),
                owner == null ? new Object[]{sessionId.toString()} : new Object[]{sessionId.toString(), owner.toString()});
    }

    @Override
    public void insertFork(HistoryRecord record) {
        ResourceAccess.requireOwner(record.ownerId());
        MysqlHistoryStore.insertSnapshot(jdbc, record);
    }

    @Override
    public void deleteFork(UUID id) {
        var owner = ResourceAccess.userId();
        jdbc.update("DELETE FROM history WHERE id=?" + (owner == null ? "" : " AND owner_id=?"),
                owner == null ? new Object[]{id.toString()} : new Object[]{id.toString(), owner.toString()});
    }
}
