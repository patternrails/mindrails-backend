package com.mindrails.backend.userstatus;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class UserSessionStatusServiceTest {
    @Test void successfulSessionDeletionRemovesRuntimeObservation() {
        var sessions = new com.mindrails.backend.agent.registry.SessionRegistryConfiguration().sessionRegistry();
        UUID owner = UUID.randomUUID(), agent = UUID.randomUUID(), sessionId = UUID.randomUUID();
        sessions.insertIfAbsent(com.mindrails.backend.agent.model.Session.builder().id(sessionId).ownerId(owner).parentAgentId(agent).build());
        var statuses = new UserSessionStatusService();
        var registration = statuses.register(owner, agent, sessionId); registration.changed("running", UUID.randomUUID(), 1, null);
        var events = new ArrayList<UserSessionStatusService.Event>(); statuses.subscribe(owner, events::add);
        var service = new com.mindrails.backend.agent.service.internal.DefaultSessionService(sessions);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "userStatuses", statuses);
        assertInstanceOf(com.mindrails.backend.common.ServiceResult.Success.class, service.deleteSession(sessionId));
        assertEquals("user.session.removed", events.getLast().type());
        int count = events.size(); registration.changed("idle", null, 0, null); assertEquals(count, events.size());
    }

    @Test void ownerIsolationMultiplePagesAndDeletionInvalidateLateCallbacks() {
        var service = new UserSessionStatusService();
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), agent = UUID.randomUUID(), session = UUID.randomUUID();
        var registration = service.register(owner, agent, session);
        registration.changed("running", UUID.randomUUID(), 2, null);
        var one = new ArrayList<UserSessionStatusService.Event>();
        var two = new ArrayList<UserSessionStatusService.Event>();
        var foreign = new ArrayList<UserSessionStatusService.Event>();
        var unsubscribe = service.subscribe(owner, one::add);
        service.subscribe(owner, two::add); service.subscribe(other, foreign::add);
        assertEquals(1, ((UserSessionStatusService.Snapshot) one.getFirst().data()).sessions().size());
        assertTrue(((UserSessionStatusService.Snapshot) foreign.getFirst().data()).sessions().isEmpty());
        service.subscribe(owner, event -> { throw new IllegalStateException("broken consumer"); });
        registration.changed("idle", null, 0, new UserSessionStatusService.Finished(UUID.randomUUID(), "completed"));
        assertEquals(2, one.size()); assertEquals(2, two.size()); assertEquals(1, foreign.size());
        unsubscribe.run(); service.remove(owner, session);
        assertEquals(2, one.size()); assertEquals("user.session.removed", two.getLast().type());
        int count = two.size(); registration.changed("running", UUID.randomUUID(), 0, null);
        assertEquals(count, two.size());
        var afterDelete = new ArrayList<UserSessionStatusService.Event>(); service.subscribe(owner, afterDelete::add);
        assertTrue(((UserSessionStatusService.Snapshot) afterDelete.getFirst().data()).sessions().isEmpty());
    }

    @Test void concurrentSubscribeAndPublishAlwaysStartWithSnapshotAndFinishAtNewestRevision() throws Exception {
        var service = new UserSessionStatusService(); UUID owner = UUID.randomUUID();
        var registration = service.register(owner, UUID.randomUUID(), UUID.randomUUID());
        registration.changed("idle", null, 0, null);
        var events = new CopyOnWriteArrayList<UserSessionStatusService.Event>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var publisher = executor.submit(() -> { for (int i = 0; i < 200; i++) registration.changed("running", null, i, null); });
            var subscriber = executor.submit(() -> service.subscribe(owner, events::add));
            publisher.get(3, TimeUnit.SECONDS); subscriber.get(3, TimeUnit.SECONDS);
        }
        assertEquals("user.status.snapshot", events.getFirst().type());
        long revision = ((UserSessionStatusService.Snapshot) events.getFirst().data()).sessions().getFirst().revision();
        for (var event : events.subList(1, events.size())) {
            long next = ((UserSessionStatusService.Status) event.data()).revision();
            assertTrue(next > revision); revision = next;
        }
        assertEquals(201, revision);
    }
}
