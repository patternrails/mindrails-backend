package com.mindrails.backend.agent.registry;

import java.util.Optional;
import java.util.UUID;

/** Read access to Session identity and configuration, without exposing the mutable stored Session. */
public interface ReadOnlySessionRegistry {

    record SessionView(UUID id, UUID parentAgentId, UUID providerId, Boolean streaming) {}

    Optional<SessionView> find(UUID sessionId);
}
