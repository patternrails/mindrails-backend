package com.mindrails.backend.agent.registry;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read access to Agent identity and owned Session membership without exposing the mutable Agent. */
public interface ReadOnlyAgentRegistry {

    /** sessionIds contains only Sessions whose parentAgentId matches this Agent's ID. */
    record AgentView(UUID id, Set<UUID> sessionIds, Set<UUID> taskIds) {
        public AgentView(UUID id, Set<UUID> sessionIds) { this(id,sessionIds,Set.of()); }
        public AgentView {
            sessionIds = Set.copyOf(sessionIds);
            taskIds = Set.copyOf(taskIds);
        }
    }

    Optional<AgentView> find(UUID agentId);
}
