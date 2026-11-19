package com.mindrails.backend.history.model;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Bounded list fields only; archive JSON is fetched separately. */
public record HistorySummary(
        UUID id, UUID agentId, UUID sessionId, UUID providerId,
        String transport, String status, Instant startedAt, Instant finishedAt,
        Long totalTokens, String finishReason, UUID taskId) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static HistorySummary from(HistoryRecord record) {
        var metadata = JSON.readTree(record.metadata());
        var tokens = metadata.path("usage").path("totalTokens");
        var reason = metadata.path("finishReason");
        String finishReason = reason.isString() ? reason.asString() : null;
        if (finishReason != null && finishReason.codePointCount(0, finishReason.length()) > 120) {
            finishReason = finishReason.substring(0, finishReason.offsetByCodePoints(0, 120));
        }
        return new HistorySummary(record.id(), record.agentId(), record.sessionId(), record.providerId(),
                record.transport().name(), record.status().name(), record.startedAt(), record.finishedAt(),
                tokens.isIntegralNumber() && tokens.canConvertToLong() && tokens.asLong() >= 0 ? tokens.asLong() : null,
                finishReason, record.taskId());
    }
}
