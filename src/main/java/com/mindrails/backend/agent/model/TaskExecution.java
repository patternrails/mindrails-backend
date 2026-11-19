package com.mindrails.backend.agent.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Business record of one invocation, persisted independently of optional history and metrics. */
@lombok.Builder(toBuilder = true)
public record TaskExecution(UUID id, UUID ownerId, UUID agentId, UUID taskId, UUID requestId,
        Trigger trigger, Map<String, Object> parameters, Definition taskSnapshot,
        Status status, Instant createdAt, Instant startedAt, Instant finishedAt, Long durationMs,
        List<Entry> records, ChatReply.Usage usage, List<ToolCall> toolExecutions,
        TaskReply result, Failure error) {
    public TaskExecution {
        parameters = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters == null ? Map.of() : parameters));
        records = records == null ? List.of() : List.copyOf(records);
        toolExecutions = toolExecutions == null ? List.of() : List.copyOf(toolExecutions);
    }
    public enum Status {
        STARTING, RUNNING, CANCELLING, COMPLETED, FAILED, CANCELLED, SKIPPED;
        public boolean terminal() { return this == COMPLETED || this == FAILED || this == CANCELLED || this == SKIPPED; }
    }
    public enum TriggerType { API, SESSION, TASK, CRON }
    public record Trigger(TriggerType type, UUID sourceSessionId, UUID sourceExecutionId, Instant scheduledAt) {
        public static Trigger api() { return new Trigger(TriggerType.API, null, null, null); }
    }
    /** Credential-free snapshot. Agent instructions are frozen with the task configuration. */
    public record Definition(long version, String name, String instructions, String agentSystemMessage,
            UUID providerId, String providerName, String modelName, List<UUID> skillIds, String cronjob) {
        public Definition { skillIds = List.copyOf(skillIds); }
    }
    public record Entry(long sequence, Instant timestamp, String type, Object data) {}
    public record ToolCall(String id, String name, String arguments, String result, boolean failed, Long durationMs) {}
    public record Failure(String code, String message) {}
}
