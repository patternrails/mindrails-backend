package com.mindrails.backend.agent.model;

import java.util.List;
import java.util.UUID;

/** Transport-independent input to a Session's serial queue. */
public record SessionInput(UUID requestId, String message, List<UUID> assetVersionIds,
        SessionMessageSource communication) {
    public SessionInput {
        assetVersionIds = assetVersionIds == null ? List.of() : List.copyOf(assetVersionIds);
    }
}
