package com.mindrails.backend.observability;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

public record ArchiveFilter(Instant from, Instant to, UUID agentId, UUID sessionId, String status) {
    public boolean matches(Instant time, UUID agent, UUID session, String state) {
        return (from == null || !time.isBefore(from)) && (to == null || time.isBefore(to))
                && (agentId == null || agentId.equals(agent)) && (sessionId == null || sessionId.equals(session))
                && (status == null || status.equals(state));
    }
    public String sql(MapSqlParameterSource parameters) {
        var sql = new StringBuilder(" WHERE 1=1");
        var user=com.mindrails.backend.identity.ResourceAccess.userId();
        if(user!=null) { sql.append(" AND owner_id=:resource_user"); parameters.addValue("resource_user",user.toString()); }
        if (from != null) { sql.append(" AND started_at>=:from"); parameters.addValue("from", java.sql.Timestamp.from(from)); }
        if (to != null) { sql.append(" AND started_at<:to"); parameters.addValue("to", java.sql.Timestamp.from(to)); }
        if (agentId != null) { sql.append(" AND agent_id=:agent"); parameters.addValue("agent", agentId.toString()); }
        if (sessionId != null) { sql.append(" AND session_id=:session"); parameters.addValue("session", sessionId.toString()); }
        if (status != null) { sql.append(" AND status=:status"); parameters.addValue("status", status); }
        return sql.toString();
    }
}
