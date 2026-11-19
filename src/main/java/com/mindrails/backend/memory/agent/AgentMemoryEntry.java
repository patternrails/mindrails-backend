package com.mindrails.backend.memory.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An explicitly saved fact shared by an Agent's sessions, independent of chat messages. */
public record AgentMemoryEntry(UUID id, UUID agentId, UUID memoryId, String key, Type type, String content,
        List<String> tags, boolean pinned, int priority, UUID sourceSessionId,
        Instant createdAt, Instant updatedAt, long version) {
    public enum Type { FACT, PREFERENCE, DECISION }

    public AgentMemoryEntry {
        tags = List.copyOf(tags);
    }
}
