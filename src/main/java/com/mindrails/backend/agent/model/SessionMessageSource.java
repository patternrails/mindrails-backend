package com.mindrails.backend.agent.model;

import java.time.Instant;
import java.util.UUID;

/** Server-authored provenance; message text cannot supply or override these identities. */
public record SessionMessageSource(UUID sourceSessionId, UUID sourceAgentId, UUID sourceRunId,
        UUID inReplyTo, Instant sentAt, int hops) {}
