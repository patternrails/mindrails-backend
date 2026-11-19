package com.mindrails.backend.agent.registry;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.AgentSessionScope;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.DefaultAgentSessionChatStreamGateway;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRegistryTest {

    @Test
    void agentServiceCrudIsVisibleThroughAnImmutableReadOnlyView() {
        AgentRegistry registry = new AgentRegistryConfiguration().agentRegistry();
        ReadOnlyAgentRegistry reader = registry;
        DefaultAgentService service = new DefaultAgentService(registry,
                new DefaultSessionService(
                        new SessionRegistryConfiguration().sessionRegistry()), List.of());

        UUID sessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        UUID id = ((ServiceResult.Success<UUID>) service.create(Agent.builder().name("Travel").build())).value();
        registry.mutate(id, agent -> agent.setSessions(List.of(
                Session.builder().id(sessionId).parentAgentId(id).build(),
                Session.builder().id(otherSessionId).parentAgentId(UUID.randomUUID()).build())));
        var firstView = reader.find(id).orElseThrow();
        assertEquals(id, firstView.id());
        assertEquals(Set.of(sessionId), firstView.sessionIds());
        assertThrows(UnsupportedOperationException.class, () -> firstView.sessionIds().add(UUID.randomUUID()));
        assertSame(registry.findManaged(id).orElseThrow(), registry.listManaged(0, 20).getFirst());
        SessionRegistry sessions = new SessionRegistryConfiguration().sessionRegistry();
        sessions.insertIfAbsent(Session.builder().id(sessionId).parentAgentId(id).build());
        var gateway = new DefaultAgentSessionChatStreamGateway(reader, sessions,
                org.mockito.Mockito.mock(com.mindrails.backend.agent.service.internal.LoopSessionStreamService.class));
        assertTrue(gateway.validate(new AgentSessionScope(id, sessionId)) instanceof ServiceResult.Success<?>);
        assertEquals(ErrorCode.NOT_FOUND, ((ServiceResult.Failure<?>) gateway.validate(
                new AgentSessionScope(id, otherSessionId))).code());

        service.update(id, Agent.builder().name("Family travel").build());
        assertEquals("Family travel", registry.findManaged(id).orElseThrow().getName());
        registry.mutate(id, agent -> agent.setSessions(List.of()));
        assertEquals(Set.of(sessionId), firstView.sessionIds());
        assertTrue(reader.find(id).orElseThrow().sessionIds().isEmpty());

        service.delete(id);
        assertFalse(reader.find(id).isPresent());
    }
}
