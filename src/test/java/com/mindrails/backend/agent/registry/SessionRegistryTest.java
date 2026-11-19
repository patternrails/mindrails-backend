package com.mindrails.backend.agent.registry;

import com.mindrails.backend.metrics.TestMetrics;

import com.mindrails.backend.history.TestHistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.provider.Provider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SessionRegistryTest {

    @Test
    void sessionServiceWritesAreVisibleThroughAnImmutableReadOnlyView() {
        SessionRegistry registry = new SessionRegistryConfiguration().sessionRegistry();
        ReadOnlySessionRegistry reader = registry;
        DefaultSessionService service = new DefaultSessionService(
                registry);
        UUID agentId = UUID.randomUUID(), providerId = UUID.randomUUID();
        Provider provider = Provider.builder().id(providerId).streaming(true).build();
        Session session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).parentAgentId(agentId)
                .provider(provider).createdAt(Instant.now()).updatedAt(Instant.now()).build();

        assertSame(session, ((ServiceResult.Success<Session>) service.startSession(session)).value());
        var firstView = reader.find(session.getId()).orElseThrow();
        assertEquals(new ReadOnlySessionRegistry.SessionView(session.getId(), agentId, providerId, true), firstView);
        assertNotSame(session, firstView);

        var changedProvider = Provider.builder().id(UUID.randomUUID()).streaming(false).build();
        service.updateSession(session.getId(), Session.builder().provider(changedProvider).build());
        assertEquals(providerId, firstView.providerId()); // Previously handed-out views do not change.
        assertEquals(changedProvider.getId(), reader.find(session.getId()).orElseThrow().providerId());
        assertFalse(reader.find(session.getId()).orElseThrow().streaming());

        service.deleteSession(session.getId());
        assertTrue(reader.find(session.getId()).isEmpty());
    }

    @Test
    void staleSaveCannotOverwriteAnAcceptedUpdate() {
        var registry = new SessionRegistryConfiguration().sessionRegistry();
        var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).systemMessage("original").build();
        registry.insertIfAbsent(session);
        var first = registry.findManaged(session.getId()).orElseThrow().clone();
        var stale = registry.findManaged(session.getId()).orElseThrow().clone();
        first.setSystemMessage("accepted");
        registry.save(first).orElseThrow();
        stale.setSystemMessage("stale");
        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> registry.save(stale));
        assertEquals("accepted", registry.findManaged(session.getId()).orElseThrow().getSystemMessage());
        assertEquals(1, registry.findManaged(session.getId()).orElseThrow().getVersion());
        registry.remove(session.getId());
        assertTrue(registry.save(first).isEmpty());
    }

    @Test
    void registryRejectsIdentityChangesWithoutChangingStoredConfiguration() {
        var registry = new SessionRegistryConfiguration().sessionRegistry();
        var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build();
        registry.insertIfAbsent(session);
        var changed = session.toBuilder().memoryId(UUID.randomUUID()).systemMessage("invalid").build();
        assertThrows(IllegalArgumentException.class, () -> registry.save(changed));
        assertEquals(session.getMemoryId(), registry.findManaged(session.getId()).orElseThrow().getMemoryId());
        assertEquals(0, session.getVersion());
    }

    @Test
    void concurrentSavesOfTheSameVersionHaveOneWinner() throws Exception {
        var registry = new SessionRegistryConfiguration().sessionRegistry();
        var session = Session.builder().id(UUID.randomUUID()).build();
        registry.insertIfAbsent(session);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var updates = java.util.stream.IntStream.range(0, 16).mapToObj(index -> session.toBuilder().systemMessage("writer-" + index).build()).toList();
            var tasks = updates.stream().map(update -> executor.submit(() -> {
                gate.await();
                try { return registry.save(update).isPresent(); }
                catch (org.springframework.dao.OptimisticLockingFailureException conflict) { return false; }
            })).toList();
            gate.countDown();
            int accepted = 0;
            for (var task : tasks) if (task.get(2, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
        }
    }

    @Test
    void concurrentInsertsForOneIdHaveExactlyOneWinner() throws Exception {
        SessionRegistry registry = new SessionRegistryConfiguration().sessionRegistry();
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 64; i++) {
                int index = i;
                tasks.add(executor.submit(() -> registry.insertIfAbsent(Session.builder().id(id)
                        .parentAgentId(owner).systemMessage("writer-" + index)
                        .createdAt(Instant.EPOCH).build())));
            }
            int accepted = 0;
            for (var task : tasks) if (task.get(2, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
        }
        assertEquals(1, registry.listManaged(owner, 0, 20).size());
        assertTrue(registry.find(id).isPresent());
    }
}
