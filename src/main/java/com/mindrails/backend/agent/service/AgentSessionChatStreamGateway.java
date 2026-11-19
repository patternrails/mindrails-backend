package com.mindrails.backend.agent.service;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.api.websocket.protocol.RunState;
import com.mindrails.backend.common.ServiceResult;
import java.util.UUID;

/**
 * Streaming execution contract for the WebSocket API, without Spring WebSocketSession or LC4J types.
 * All methods return promptly; model/tool work must run outside the calling thread.
 * Implementations own resource authorization; the loop serializes messages.
 */
public interface AgentSessionChatStreamGateway {
    record AgentSessionScope(UUID agentId, UUID sessionId) {}
    record ActiveRun(UUID runId, RunState state) {}
    record SessionSnapshot(ActiveRun activeRun) {}

    @FunctionalInterface
    interface EventSink { void emit(ChatStreamEvent event); }

    /** Validate existence/ownership and return a point-in-time busy snapshot. */
    ServiceResult<SessionSnapshot> validate(AgentSessionScope agentSessionScope);

    enum CommandType { STOP, BIND, UNBIND }

    /** Enqueue a message independently of the observing connections. */
    ServiceResult<Void> chat(AgentSessionScope scope, ClientCommand.Chat message);

    /** STOP has no payload; BIND and UNBIND carry an EventSink. */
    ServiceResult<Void> command(AgentSessionScope scope, CommandType type, Object payload);
}
