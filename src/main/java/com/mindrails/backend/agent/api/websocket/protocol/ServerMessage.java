package com.mindrails.backend.agent.api.websocket.protocol;

import java.time.Instant;
import java.util.UUID;

/** One JSON text frame. Sequence numbers are scoped to a connection, not persisted chat history. */
public record ServerMessage<T>(int version, ServerMessageType type, UUID agentId, UUID sessionId,
        UUID requestId, long seq, Instant timestamp, T data) {}
