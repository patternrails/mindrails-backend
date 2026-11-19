package com.mindrails.backend.metrics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable absolute values: retries never add deltas. No conversation text or tool arguments. */
@lombok.Builder(toBuilder = true)
public record MetricsSnapshot(
        UUID runId, UUID instanceId, UUID ownerId, UUID agentId, UUID sessionId, UUID memoryId, UUID requestId,
        UUID providerId, String providerName, String modelName, long sessionVersion, String transport,
        List<UUID> configuredSkillIds, String status, Instant startedAt, Instant endedAt, Instant lastObservedAt,
        Long durationMs, Long firstResponseMs, String terminalReason, String errorCode,
        long modelCallCount, long modelSucceededCount, long modelFailedCount, long modelUnresolvedCount,
        long toolRequestedCount, long functionCallCount, long mcpToolCallCount, long skillLoadCount,
        long skillExecutionCount, long capabilityFailedCount, long capabilityUnresolvedCount,
        Long knownInputTokens, Long knownOutputTokens, Long knownTotalTokens,
        long inputKnownCallCount, long outputKnownCallCount, long usageKnownCallCount, String usageCoverage,
        Usage aggregateUsageReported, boolean detailsTruncated, long droppedDetailCount, long revision,
        List<ModelCall> modelCalls, List<CapabilityCall> capabilityCalls, UUID taskId,
        UUID appId, UUID appRevisionId, UUID appReleaseId, String appMode) {
    public MetricsSnapshot {
        configuredSkillIds = configuredSkillIds == null ? List.of() : List.copyOf(configuredSkillIds);
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
        capabilityCalls = capabilityCalls == null ? List.of() : List.copyOf(capabilityCalls);
    }
    public boolean terminal() { return !"RUNNING".equals(status); }
    /** Conservative charge for bounded strings/fields, including JSON expansion; no serialization on chat threads. */
    public long estimatedBytes() { return 8192L * (1 + modelCalls.size() + capabilityCalls.size()); }
    public record Usage(Long inputTokens, Long outputTokens, Long totalTokens) {}
    @lombok.Builder(toBuilder = true)
    public record ModelCall(UUID modelCallId, UUID runId, UUID invocationId, long sequence, UUID providerId,
            String requestedModel, String responseModel, String providerResponseId, String status,
            Instant startedAt, Instant endedAt, Long durationMs, Long inputTokens, Long outputTokens,
            Long totalTokens, Long cachedInputTokens, Long cacheWriteTokens, Long reasoningTokens,
            String usageSource, String finishReason, String errorCode, long toolRequestedCount) {}
    @lombok.Builder(toBuilder = true)
    public record CapabilityCall(UUID capabilityCallId, UUID runId, UUID modelCallId, UUID invocationId,
            UUID parentCapabilityCallId, String providerToolCallId, String kind, String capabilityId,
            String capabilityName, String capabilityVersion, String serverId, UUID connectionId,
            String status, Instant startedAt, Instant endedAt, Long durationMs, String errorCode) {}
}
