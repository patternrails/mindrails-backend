package com.mindrails.backend.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.agent.api.web.UpdateSessionRequest;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeBindingService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

class KnowledgeBindingTest {
    @Test
    void sessionResolvesCurrentAgentBindingWithoutKnowledgeState() {
        var agents = new AgentRegistryConfiguration().agentRegistry();
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        UUID agent = UUID.randomUUID(), session = UUID.randomUUID(), kb = UUID.randomUUID();
        agents.insertIfAbsent(Agent.builder().id(agent).name("Owner").knowledgeBaseIds(java.util.List.of(kb)).build());
        sessions.insertIfAbsent(
                Session.builder()
                        .id(session)
                        .parentAgentId(agent)
                        .build());
        var bindings =
                new DefaultKnowledgeBindingService(
                        agents, sessions, org.mockito.Mockito.mock(ObjectProvider.class));
        assertEquals(java.util.List.of(kb), bindings.session(session).knowledgeBaseIds());
        agents.mutate(agent, current -> current.setKnowledgeBaseIds(java.util.List.of()));
        assertTrue(bindings.session(session).knowledgeBaseIds().isEmpty());
    }

    @Test
    void assigningOneBasePreservesOtherMembershipsAndStaleVersionsCannotRegrantAccess() {
        var agents = new AgentRegistryConfiguration().agentRegistry();
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        var repository = org.mockito.Mockito.mock(com.mindrails.backend.knowledge.registry.KnowledgeRegistry.class);
        var provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(repository);
        org.mockito.Mockito.when(repository.transaction(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        org.mockito.Mockito.when(repository.getKnowledgeBase(org.mockito.ArgumentMatchers.any())).thenAnswer(call ->
                com.mindrails.backend.knowledge.model.KnowledgeBase.builder().id(call.getArgument(0))
                        .state(com.mindrails.backend.knowledge.model.KnowledgeBaseState.ACTIVE).build());
        var service = new DefaultKnowledgeBindingService(agents, sessions, provider);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), one = UUID.randomUUID(), two = UUID.randomUUID();
        agents.insertIfAbsent(Agent.builder().id(one).createdAt(java.time.Instant.now()).knowledgeBaseIds(java.util.List.of(first)).build());
        agents.insertIfAbsent(Agent.builder().id(two).createdAt(java.time.Instant.now()).build());
        var both = service.assign(one, second, true, 0);
        assertEquals(java.util.Set.of(first, second), new java.util.HashSet<>(both.knowledgeBaseIds()));
        service.assign(two, second, true, 0);
        assertEquals(2, service.referencing(second).size());
        var reduced = service.assign(one, second, false, both.agentVersion());
        assertEquals(java.util.List.of(first), reduced.knowledgeBaseIds());
        assertThrows(com.mindrails.backend.knowledge.service.KnowledgeException.class,
                () -> service.assign(one, second, true, both.agentVersion()));
        assertEquals(1, service.referencing(second).size());
        assertThrows(UnsupportedOperationException.class, () -> both.knowledgeBaseIds().clear());
    }

    @Test
    void sessionInputRejectsBothReplacementAndExplicitClearing() {
        var mapper = JsonMapper.builder().build();
        for (String value : new String[] {"null", "\"" + UUID.randomUUID() + "\""}) {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            mapper.readValue(
                                    "{\"providerId\":\""
                                            + UUID.randomUUID()
                                            + "\",\"knowledgeBaseId\":"
                                            + value
                                            + "}",
                                    UpdateSessionRequest.class));
        }
    }
}
