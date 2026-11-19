package com.mindrails.backend.agent.service;

import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.registry.ReadOnlyAgentRegistry;
import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry;
import com.mindrails.backend.agent.service.internal.LoopSessionStreamService;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;

import org.springframework.stereotype.Service;

/** Validates Session ownership and delegates streaming run preparation to the internal service. */
@Service
public class DefaultAgentSessionChatStreamGateway implements AgentSessionChatStreamGateway {

    private final ReadOnlyAgentRegistry agents;
    private final ReadOnlySessionRegistry sessions;
    private final LoopSessionStreamService loops;

    public DefaultAgentSessionChatStreamGateway(ReadOnlyAgentRegistry agents, ReadOnlySessionRegistry sessions,
            LoopSessionStreamService loops) {
        this.agents = agents;
        this.sessions = sessions;
        this.loops = loops;
    }

    @Override
    public ServiceResult<SessionSnapshot> validate(AgentSessionScope agentSessionScope) {
        if (agentSessionScope == null || agentSessionScope.agentId() == null || agentSessionScope.sessionId() == null) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Agent and Session IDs are required");
        }
        boolean ownedByAgent = agents.find(agentSessionScope.agentId())
                .filter(agent -> agent.sessionIds().contains(agentSessionScope.sessionId()))
                .isPresent();
        boolean registered = sessions.find(agentSessionScope.sessionId())
                .filter(session -> agentSessionScope.agentId().equals(session.parentAgentId()))
                .isPresent();
        return ownedByAgent && registered
                ? ServiceResult.success(new SessionSnapshot(null))
                : ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found");
    }

    @Override
    public ServiceResult<Void> chat(AgentSessionScope scope, ClientCommand.Chat message) {
        if (message == null || message.requestId() == null || message.message() == null || message.message().isBlank()) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Request ID and non-blank message are required");
        }
        return validate(scope).flatMap(ignored -> loops.enqueue(scope.sessionId(), message));
    }

    @Override
    public ServiceResult<Void> command(AgentSessionScope scope, CommandType type, Object payload) {
        if (scope == null || scope.agentId() == null || scope.sessionId() == null || type == null
                || type == CommandType.STOP && payload != null
                || type != CommandType.STOP && !(payload instanceof EventSink)) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Valid scope, command and payload are required");
        }
        // Cleanup uses the exact sink already bound to this connection, even after resource deletion.
        if (type == CommandType.UNBIND) return loops.unbind(scope.sessionId(), (EventSink) payload);
        return validate(scope).flatMap(ignored -> switch (type) {
            case STOP -> loops.stop(scope.sessionId());
            case BIND -> loops.bind(scope.sessionId(), (EventSink) payload);
            case UNBIND -> loops.unbind(scope.sessionId(), (EventSink) payload);
        });
    }
}
