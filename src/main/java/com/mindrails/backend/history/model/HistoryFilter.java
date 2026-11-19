package com.mindrails.backend.history.model;

import java.time.Instant;
import java.util.UUID;

public record HistoryFilter(Instant from, Instant to, UUID agentId, UUID sessionId, String status) {
    public boolean matches(Instant time, UUID agent, UUID session, String state) {
        return (from == null || !time.isBefore(from))
                && (to == null || time.isBefore(to))
                && (agentId == null || agentId.equals(agent))
                && (sessionId == null || sessionId.equals(session))
                && (status == null || status.equals(state));
    }
}
