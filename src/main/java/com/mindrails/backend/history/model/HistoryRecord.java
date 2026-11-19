package com.mindrails.backend.history.model;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable archive of one accepted chat turn. IDs are snapshots, independent of live resources.
 */
@lombok.Builder(toBuilder = true)
public record HistoryRecord(
        UUID id,
        UUID ownerId,
        UUID agentId,
        UUID sessionId,
        UUID memoryId,
        UUID providerId,
        UUID requestId,
        UUID invocationId,
        Transport transport,
        Status status,
        @JsonRawValue String messages,
        @JsonRawValue String metadata,
        Instant startedAt,
        Instant finishedAt,
        int schemaVersion, UUID taskId) {
    public HistoryRecord(UUID id, UUID ownerId, UUID agentId, UUID sessionId, UUID memoryId,
            UUID providerId, UUID requestId, UUID invocationId, Transport transport, Status status,
            String messages, String metadata, Instant startedAt, Instant finishedAt, int schemaVersion) {
        this(id,ownerId,agentId,sessionId,memoryId,providerId,requestId,invocationId,transport,status,
                messages,metadata,startedAt,finishedAt,schemaVersion,null);
    }
    public String resourceType() { return taskId == null ? "SESSION" : "TASK"; }
    public enum Transport {
        SYNC,
        STREAM
    }

    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
