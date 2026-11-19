package com.mindrails.backend.agent.service.impl;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.ChatReply;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.SessionService;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.provider.Provider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultAgentServiceImplTest {

    private final Provider configuredProvider = Provider.builder().id(UUID.randomUUID()).build();

    private final DefaultAgentService service = new DefaultAgentService(
            new AgentRegistryConfiguration().agentRegistry(),
            new DefaultSessionService(
                    new SessionRegistryConfiguration().sessionRegistry()), List.of(configuredProvider));

    @Test
    void forkLeavesTheFacadeAvailableAndGuardsSourceMembershipUntilPublication() throws Exception {
        var registry = new AgentRegistryConfiguration().agentRegistry();
        var sessions = mock(SessionService.class);
        var facade = new DefaultAgentService(registry, sessions, List.of(configuredProvider));
        UUID owner = value(facade.create(Agent.builder().name("Fork owner").build()));
        var source = Session.builder().id(UUID.randomUUID()).parentAgentId(owner).build();
        registry.findManaged(owner).orElseThrow().getSessions().add(source);
        var child = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(sessions.forkSession(source.getId())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Fork timed out");
            return ServiceResult.success(child);
        });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var fork = executor.submit(() -> facade.forkSession(owner, source.getId()));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            try {
                value(executor.submit(() -> facade.get(owner)).get(2, java.util.concurrent.TimeUnit.SECONDS));
                assertFailure(facade.delete(owner), ErrorCode.CONFLICT);
                assertFailure(facade.deleteSession(owner, source.getId()), ErrorCode.CONFLICT);
                assertFailure(facade.forkSession(owner, source.getId()), ErrorCode.CONFLICT);
                UUID other = value(facade.create(Agent.builder().name("Other work").build()));
                assertFailure(facade.deleteSession(other, source.getId()), ErrorCode.NOT_FOUND);
            } finally { release.countDown(); }
            assertEquals(child.getId(), value(fork.get(2, java.util.concurrent.TimeUnit.SECONDS)));
        }
        assertEquals(List.of(source, child), value(facade.get(owner)).getSessions());
        when(sessions.deleteSession(source.getId())).thenReturn(ServiceResult.success());
        value(facade.deleteSession(owner, source.getId()));
        verify(sessions).deleteSession(source.getId());
    }

    @Test
    void agentDeleteEnumeratesSessionsAndRetainsProgressWhenALaterDeleteFails() {
        var registry = new AgentRegistryConfiguration().agentRegistry();
        var sessions = mock(SessionService.class);
        var facade = new DefaultAgentService(registry, sessions, List.of(configuredProvider));
        UUID id = value(facade.create(Agent.builder().name("Delete sequentially").build()));
        var first = Session.builder().id(UUID.randomUUID()).parentAgentId(id).build();
        var second = Session.builder().id(UUID.randomUUID()).parentAgentId(id).build();
        var third = Session.builder().id(UUID.randomUUID()).parentAgentId(id).build();
        registry.findManaged(id).orElseThrow().getSessions().addAll(List.of(first, second, third));
        when(sessions.deleteSession(first.getId())).thenReturn(ServiceResult.success());
        when(sessions.deleteSession(second.getId())).thenReturn(ServiceResult.failure(ErrorCode.INTERNAL_ERROR, "Delete failed"));
        assertFailure(facade.delete(id), ErrorCode.INTERNAL_ERROR);
        var order = inOrder(sessions);
        order.verify(sessions).deleteSession(first.getId());
        order.verify(sessions).deleteSession(second.getId());
        verify(sessions, never()).deleteSession(third.getId());
        assertEquals(List.of(second, third), registry.findManaged(id).orElseThrow().getSessions());
        when(sessions.deleteSession(second.getId())).thenReturn(ServiceResult.success());
        when(sessions.deleteSession(third.getId())).thenReturn(ServiceResult.success());
        value(facade.delete(id));
        assertTrue(registry.findManaged(id).isEmpty());
        verify(sessions, times(1)).deleteSession(first.getId());
    }

    @Test
    void createsUpdatesAndDeletesWhilePreservingIdentityAndSessions() {
        UUID owner = UUID.randomUUID();
        UUID suppliedId = UUID.randomUUID();
        Session suppliedSession = new Session();
        Agent input = Agent.builder().id(suppliedId).ownerId(owner).name("Travel")
                .sessions(List.of(suppliedSession)).createdAt(Instant.EPOCH).build();
        UUID id = value(service.create(input));
        assertNotEquals(suppliedId, id);
        Agent created = value(service.get(id));
        assertEquals(owner, created.getOwnerId());
        assertNotNull(created.getMemoryId());
        assertNotEquals(Instant.EPOCH, created.getCreatedAt());
        assertEquals(created.getCreatedAt(), created.getUpdatedAt());
        assertTrue(created.getSessions().isEmpty());
        UUID sessionId = value(service.startSession(id, configuredProvider.getId()));
        Session session = value(service.listSessions(id, 0, 20)).getFirst();
        assertEquals(sessionId, session.getId());

        Agent update = Agent.builder().id(UUID.randomUUID()).ownerId(UUID.randomUUID())
                .name("Family travel").description("Updated description").goal("Family trips")
                .systemMessage("Confirm bookings").build();
        value(service.update(id, update));
        Agent updated = value(service.get(id));
        assertEquals(id, updated.getId());
        assertEquals(owner, updated.getOwnerId());
        assertEquals(created.getCreatedAt(), updated.getCreatedAt());
        assertEquals(created.getMemoryId(), updated.getMemoryId());
        assertFalse(updated.getUpdatedAt().isBefore(created.getUpdatedAt()));
        assertSame(session, updated.getSessions().getFirst());
        assertEquals("Family travel", updated.getName());
        assertEquals("Family trips", updated.getGoal());
        assertEquals("Confirm bookings", updated.getSystemMessage());

        value(service.delete(id));
        assertFailure(service.get(id), ErrorCode.NOT_FOUND);
        assertFailure(service.update(id, update), ErrorCode.NOT_FOUND);
        assertFailure(service.delete(id), ErrorCode.NOT_FOUND);
    }

    @Test
    void paginatesWithoutCloningAndGetsAShallowCopy() {
        Agent input = Agent.builder().name("First").build();
        UUID first = value(service.create(input));
        input.setName("Changed outside service");
        value(service.create(Agent.builder().name("Second").build()));
        value(service.create(Agent.builder().name("Third").build()));
        List<Agent> all = value(service.list(0, 20));
        assertEquals(all.subList(0, 2), value(service.list(0, 2)));
        assertEquals(all.subList(2, 3), value(service.list(1, 2)));
        assertSame(all.getFirst(), value(service.list(0, 2)).getFirst());
        assertTrue(value(service.list(Integer.MAX_VALUE, 100)).isEmpty());
        Agent returned = value(service.get(first));
        assertEquals("First", returned.getName());
        assertNotSame(input, returned);
        assertSame(input.getSkillIds(), returned.getSkillIds());
        returned.setName("Modified clone");
        assertEquals("First", value(service.get(first)).getName());
    }

    @Test
    void returnsFailuresForInvalidInputs() {
        assertFailure(service.create(null), ErrorCode.INVALID_ARGUMENT);
        assertFailure(service.create(Agent.builder().name(" ").build()), ErrorCode.INVALID_ARGUMENT);
        assertFailure(service.get(null), ErrorCode.INVALID_ARGUMENT);
        assertFailure(service.delete(null), ErrorCode.INVALID_ARGUMENT);
        assertFailure(service.update(null, Agent.builder().name("Valid").build()), ErrorCode.INVALID_ARGUMENT);
        assertFailure(service.update(UUID.randomUUID(), null), ErrorCode.INVALID_ARGUMENT);
        assertFailure(service.list(-1, 20), ErrorCode.INVALID_ARGUMENT);
        assertFailure(service.list(0, 0), ErrorCode.INVALID_ARGUMENT);
        assertFailure(service.list(0, 101), ErrorCode.INVALID_ARGUMENT);
        assertTrue(value(service.list(0, 20)).isEmpty());
    }

    @Test
    void assemblesSessionsAndDelegatesSessionOperations() {
        SessionService internal = mock(SessionService.class);
        UUID providerId = UUID.randomUUID();
        Provider provider = Provider.builder().id(providerId).name("test-provider").build();
        DefaultAgentService facade = new DefaultAgentService(new AgentRegistryConfiguration().agentRegistry(),
                internal, List.of(provider));
        UUID skillId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID agentId = value(facade.create(Agent.builder().name("Travel")
                .systemMessage("Compare before booking")
                .skillIds(List.of(skillId))
                .memoryId(memoryId).knowledgeBaseIds(java.util.List.of(knowledgeBaseId)).build()));
        Session forkedSession = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build();
        ServiceResult<List<Session>> listed = ServiceResult.success(List.of());
        ServiceResult<Void> updated = ServiceResult.success(null);
        ServiceResult<Session> forked = ServiceResult.success(forkedSession);
        ServiceResult<Void> deleted = ServiceResult.success(null);

        when(internal.startSession(any(Session.class)))
                .thenAnswer(invocation -> ServiceResult.success(invocation.getArgument(0)));
        when(internal.listSessions(agentId, 1, 5)).thenReturn(listed);
        when(internal.updateSession(any(UUID.class), any(Session.class))).thenReturn(updated);

        UUID sessionId = value(facade.startSession(agentId, providerId));
        Session session = value(facade.get(agentId)).getSessions().getFirst();
        assertEquals(sessionId, session.getId());
        assertEquals(agentId, session.getParentAgentId());
        assertSame(provider, session.getProvider());
        assertEquals("Compare before booking", session.getSystemMessage());
        assertTrue(session.getSkillIds().isEmpty());
        assertNotNull(session.getMemoryId());
        assertNotEquals(value(facade.get(agentId)).getMemoryId(), session.getMemoryId());
        assertFalse(new tools.jackson.databind.json.JsonMapper().writeValueAsString(session).contains("knowledgeBaseId"));
        assertNotNull(session.getCreatedAt());
        assertEquals(session.getCreatedAt(), session.getUpdatedAt());

        when(internal.forkSession(sessionId)).thenReturn(forked);
        when(internal.deleteSession(sessionId)).thenReturn(deleted);

        assertSame(listed, facade.listSessions(agentId, 1, 5));
        Session updateRequest = Session.builder().systemMessage("Updated").build();
        assertSame(updated, facade.updateSession(agentId, sessionId, updateRequest, providerId));
        assertNull(updateRequest.getProvider());
        assertFailure(facade.updateSession(agentId, sessionId, updateRequest, UUID.randomUUID()),
                ErrorCode.NOT_FOUND);
        assertEquals(forkedSession.getId(), value(facade.forkSession(agentId, sessionId)));
        assertEquals(agentId, forkedSession.getParentAgentId());
        assertEquals(List.of(session, forkedSession), value(facade.get(agentId)).getSessions());
        assertSame(deleted, facade.deleteSession(agentId, sessionId));
        assertEquals(List.of(forkedSession), value(facade.get(agentId)).getSessions());
        verify(internal).startSession(session);
        verify(internal).listSessions(agentId, 1, 5);
        verify(internal).updateSession(eq(sessionId), argThat(changes -> changes != updateRequest
                && changes.getProvider() == provider
                && "Updated".equals(changes.getSystemMessage())));
        verify(internal).forkSession(sessionId);
        verify(internal).deleteSession(sessionId);
    }

    @Test
    void rejectsSessionOperationsWhenAgentIsMissing() {
        SessionService internal = mock(SessionService.class);
        DefaultAgentService facade = new DefaultAgentService(new AgentRegistryConfiguration().agentRegistry(),
                internal, List.of());
        UUID missingId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Session session = new Session();
        UUID providerId = UUID.randomUUID();

        assertFailure(facade.startSession(null, providerId), ErrorCode.NOT_FOUND);
        assertFailure(facade.listSessions(null, 0, 20), ErrorCode.NOT_FOUND);
        assertFailure(facade.updateSession(null, sessionId, session, providerId), ErrorCode.NOT_FOUND);
        assertFailure(facade.forkSession(null, sessionId), ErrorCode.NOT_FOUND);
        assertFailure(facade.deleteSession(null, sessionId), ErrorCode.NOT_FOUND);
        assertFailure(facade.startSession(missingId, providerId), ErrorCode.NOT_FOUND);
        assertFailure(facade.listSessions(missingId, 0, 20), ErrorCode.NOT_FOUND);
        assertFailure(facade.updateSession(missingId, sessionId, session, providerId), ErrorCode.NOT_FOUND);
        assertFailure(facade.forkSession(missingId, sessionId), ErrorCode.NOT_FOUND);
        assertFailure(facade.deleteSession(missingId, sessionId), ErrorCode.NOT_FOUND);
        verifyNoInteractions(internal);
    }

    @Test
    void rejectsUnknownProviderWithoutCallingSessionService() {
        SessionService internal = mock(SessionService.class);
        DefaultAgentService facade = new DefaultAgentService(new AgentRegistryConfiguration().agentRegistry(),
                internal, List.of());
        UUID agentId = value(facade.create(Agent.builder().name("Travel").build()));

        assertFailure(facade.startSession(agentId, UUID.randomUUID()), ErrorCode.NOT_FOUND);
        verifyNoInteractions(internal);
    }

    @Test
    void rejectsSessionOperationsThroughAnotherAgent() {
        UUID providerId = UUID.randomUUID();
        Provider provider = Provider.builder().id(providerId).build();
        DefaultAgentService facade = new DefaultAgentService(
                new AgentRegistryConfiguration().agentRegistry(),
                new DefaultSessionService(
                        new SessionRegistryConfiguration().sessionRegistry()), List.of(provider));
        UUID ownerId = value(facade.create(Agent.builder().name("Owner").build()));
        UUID otherId = value(facade.create(Agent.builder().name("Other").build()));
        UUID sessionId = value(facade.startSession(ownerId, providerId));

        assertFailure(facade.updateSession(otherId, sessionId, new Session(), providerId), ErrorCode.NOT_FOUND);
        assertFailure(facade.forkSession(otherId, sessionId), ErrorCode.NOT_FOUND);
        assertFailure(facade.deleteSession(otherId, sessionId), ErrorCode.NOT_FOUND);
        assertEquals(List.of(sessionId), value(facade.listSessions(ownerId, 0, 20)).stream()
                .map(Session::getId).toList());
    }

    @Test
    void leavesAgentMembershipAloneWhenSessionOperationsFail() {
        SessionService internal = mock(SessionService.class);
        UUID providerId = UUID.randomUUID();
        Provider provider = Provider.builder().id(providerId).build();
        DefaultAgentService facade = new DefaultAgentService(new AgentRegistryConfiguration().agentRegistry(),
                internal, List.of(provider));
        UUID agentId = value(facade.create(Agent.builder().name("Travel").build()));
        when(internal.startSession(any(Session.class)))
                .thenReturn(ServiceResult.failure(ErrorCode.INTERNAL_ERROR, "Could not create session"))
                .thenAnswer(invocation -> ServiceResult.success(invocation.getArgument(0)));

        assertFailure(facade.startSession(agentId, providerId), ErrorCode.INTERNAL_ERROR);
        assertTrue(value(facade.get(agentId)).getSessions().isEmpty());
        UUID sessionId = value(facade.startSession(agentId, providerId));
        Session session = value(facade.get(agentId)).getSessions().getFirst();
        when(internal.deleteSession(sessionId))
                .thenReturn(ServiceResult.failure(ErrorCode.INTERNAL_ERROR, "Could not delete session"));
        assertFailure(facade.deleteSession(agentId, sessionId), ErrorCode.INTERNAL_ERROR);
        assertEquals(List.of(session), value(facade.get(agentId)).getSessions());
    }

    private <T> T value(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }

    private <T> void assertFailure(ServiceResult<T> result, ErrorCode code) {
        assertInstanceOf(ServiceResult.Failure.class, result);
        assertEquals(code, ((ServiceResult.Failure<T>) result).code());
    }
}
