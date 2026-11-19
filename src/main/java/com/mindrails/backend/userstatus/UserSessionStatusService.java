package com.mindrails.backend.userstatus;

import com.mindrails.backend.identity.ResourceAccess;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;

/** In-process observation only. Callbacks enqueue data; they must never perform network I/O. */
@Service
public final class UserSessionStatusService {
    public record Finished(UUID requestId, String outcome) {}
    public record Status(UUID agentId, UUID sessionId, long revision, String state,
            UUID activeRequestId, int queuedCount, Finished lastFinished) {}
    public record Removed(UUID agentId, UUID sessionId) {}
    public record Snapshot(List<Status> sessions) {}
    public record Event(int version, String type, Object data) {
        static Event of(String type, Object data) { return new Event(1, type, data); }
    }
    @FunctionalInterface public interface Observer {
        void changed(String state, UUID activeRequestId, int queuedCount, Finished lastFinished);
    }
    private static final class Owner {
        final Map<UUID, Registration> sessions = new HashMap<>();
        final Map<UUID, Consumer<Event>> listeners = new HashMap<>();
    }
    private final Map<UUID, Owner> owners = new HashMap<>();

    public final class Registration implements Observer {
        private final UUID ownerId, agentId, sessionId;
        private Status latest;
        private Registration(UUID ownerId, UUID agentId, UUID sessionId) {
            this.ownerId = ownerId; this.agentId = agentId; this.sessionId = sessionId;
        }
        @Override public void changed(String state, UUID active, int queued, Finished finished) {
            synchronized (UserSessionStatusService.this) {
                var owner = owners.get(ownerId);
                if (owner == null || owner.sessions.get(sessionId) != this) return;
                latest = new Status(agentId, sessionId, latest == null ? 1 : latest.revision() + 1, state, active, queued, finished);
                broadcast(owner, Event.of("user.session.status", latest));
            }
        }
    }

    public synchronized Registration register(UUID ownerId, UUID agentId, UUID sessionId) {
        UUID id = ResourceAccess.owner(ownerId);
        var owner = owners.computeIfAbsent(id, ignored -> new Owner());
        return owner.sessions.computeIfAbsent(sessionId, ignored -> new Registration(id, agentId, sessionId));
    }

    public synchronized Runnable subscribe(UUID ownerId, Consumer<Event> listener) {
        UUID id = ResourceAccess.owner(ownerId), connectionId = UUID.randomUUID();
        var owner = owners.computeIfAbsent(id, ignored -> new Owner());
        owner.listeners.put(connectionId, listener);
        try {
            listener.accept(Event.of("user.status.snapshot", new Snapshot(owner.sessions.values().stream()
                    .map(r -> r.latest).filter(Objects::nonNull).toList())));
        } catch (RuntimeException error) { owner.listeners.remove(connectionId); }
        return () -> unsubscribe(id, connectionId);
    }

    private synchronized void unsubscribe(UUID ownerId, UUID connectionId) {
        var owner = owners.get(ownerId);
        if (owner == null) return;
        owner.listeners.remove(connectionId);
        if (owner.listeners.isEmpty() && owner.sessions.isEmpty()) owners.remove(ownerId);
    }

    public synchronized void remove(UUID ownerId, UUID sessionId) {
        UUID id = ResourceAccess.owner(ownerId);
        var owner = owners.get(id);
        if (owner == null) return;
        var removed = owner.sessions.remove(sessionId);
        if (removed != null) broadcast(owner, Event.of("user.session.removed", new Removed(removed.agentId, sessionId)));
        if (owner.listeners.isEmpty() && owner.sessions.isEmpty()) owners.remove(id);
    }

    private void broadcast(Owner owner, Event event) {
        owner.listeners.entrySet().removeIf(entry -> {
            try { entry.getValue().accept(event); return false; }
            catch (RuntimeException failure) { return true; }
        });
    }
}
