package com.mindrails.backend.agent.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.registry.ReadOnlyAgentRegistry;
import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.*;
import com.mindrails.backend.agent.service.internal.LoopSessionStreamService;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DefaultAgentSessionChatStreamGatewayTest {
    private final ReadOnlyAgentRegistry agents = mock(ReadOnlyAgentRegistry.class);
    private final ReadOnlySessionRegistry sessions = mock(ReadOnlySessionRegistry.class);
    private final LoopSessionStreamService loops = mock(LoopSessionStreamService.class);
    private final DefaultAgentSessionChatStreamGateway gateway = new DefaultAgentSessionChatStreamGateway(agents, sessions, loops);
    private final AgentSessionScope scope = new AgentSessionScope(UUID.randomUUID(), UUID.randomUUID());
    private final ClientCommand.Chat message = new ClientCommand.Chat(UUID.randomUUID(), "Hello");
    private final EventSink sink = mock(EventSink.class);

    private void owned() {
        when(agents.find(scope.agentId())).thenReturn(Optional.of(new ReadOnlyAgentRegistry.AgentView(scope.agentId(), Set.of(scope.sessionId()))));
        when(sessions.find(scope.sessionId())).thenReturn(Optional.of(new ReadOnlySessionRegistry.SessionView(scope.sessionId(), scope.agentId(), UUID.randomUUID(), true)));
    }

    @Test void messagesEnqueueWithoutBindingASink() {
        owned();
        var success = ServiceResult.success();
        when(loops.enqueue(scope.sessionId(), message)).thenReturn(success);
        assertSame(success, gateway.chat(scope, message));
        verify(loops).enqueue(scope.sessionId(), message);
        verifyNoMoreInteractions(loops);
        verifyNoInteractions(sink);
    }

    @ParameterizedTest @EnumSource(CommandType.class)
    void routesTheThreeCommands(CommandType type) {
        owned();
        when(loops.stop(scope.sessionId())).thenReturn(ServiceResult.success());
        when(loops.bind(scope.sessionId(), sink)).thenReturn(ServiceResult.success());
        when(loops.unbind(scope.sessionId(), sink)).thenReturn(ServiceResult.success());
        assertEquals(ServiceResult.success(), gateway.command(scope, type, type == CommandType.STOP ? null : sink));
        switch (type) {
            case STOP -> verify(loops).stop(scope.sessionId());
            case BIND -> verify(loops).bind(scope.sessionId(), sink);
            case UNBIND -> verify(loops).unbind(scope.sessionId(), sink);
        }
    }

    @ParameterizedTest @EnumSource(ErrorCode.class)
    void preservesFailures(ErrorCode code) {
        owned();
        var failure = ServiceResult.<Void>failure(code, "Unavailable");
        when(loops.enqueue(scope.sessionId(), message)).thenReturn(failure);
        when(loops.stop(scope.sessionId())).thenReturn(failure);
        assertEquals(failure, gateway.chat(scope, message));
        assertEquals(failure, gateway.command(scope, CommandType.STOP, null));
    }

    @Test void rejectsWrongOwnershipBeforeExecutionOrBinding() {
        assertFailure(gateway.chat(scope, message), ErrorCode.NOT_FOUND);
        assertFailure(gateway.command(scope, CommandType.BIND, sink), ErrorCode.NOT_FOUND);
        assertFailure(gateway.command(scope, CommandType.STOP, null), ErrorCode.NOT_FOUND);
        verifyNoInteractions(loops);
    }

    @Test void unbindStillCleansUpAfterSessionDeletion() {
        when(loops.unbind(scope.sessionId(), sink)).thenReturn(ServiceResult.success());
        assertEquals(ServiceResult.success(), gateway.command(scope, CommandType.UNBIND, sink));
        verifyNoInteractions(agents, sessions);
    }

    @Test void rejectsInvalidPayloadsAndMessages() {
        assertFailure(gateway.command(scope, null, null), ErrorCode.INVALID_ARGUMENT);
        assertFailure(gateway.command(scope, CommandType.STOP, sink), ErrorCode.INVALID_ARGUMENT);
        assertFailure(gateway.command(scope, CommandType.BIND, new Object()), ErrorCode.INVALID_ARGUMENT);
        assertFailure(gateway.command(scope, CommandType.UNBIND, null), ErrorCode.INVALID_ARGUMENT);
        assertFailure(gateway.chat(scope, null), ErrorCode.INVALID_ARGUMENT);
        assertFailure(gateway.chat(scope, new ClientCommand.Chat(null, "Hello")), ErrorCode.INVALID_ARGUMENT);
        assertFailure(gateway.chat(scope, new ClientCommand.Chat(UUID.randomUUID(), " ")), ErrorCode.INVALID_ARGUMENT);
        verifyNoInteractions(loops);
    }

    @Test void rejectsInvalidScopes() {
        assertFailure(gateway.validate(null), ErrorCode.INVALID_ARGUMENT);
        assertFailure(gateway.validate(new AgentSessionScope(null, scope.sessionId())), ErrorCode.INVALID_ARGUMENT);
        assertFailure(gateway.command(null, CommandType.STOP, null), ErrorCode.INVALID_ARGUMENT);
        assertFailure(gateway.chat(null, message), ErrorCode.INVALID_ARGUMENT);
        verifyNoInteractions(loops);
    }

    private void assertFailure(ServiceResult<?> result, ErrorCode code) {
        assertEquals(code, assertInstanceOf(ServiceResult.Failure.class, result).code());
    }
}
