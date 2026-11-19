package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.Session;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Writable Session registry. MySQL reads are snapshots; writes must use registry operations. */
public interface SessionRegistry extends ReadOnlySessionRegistry {

    boolean insertIfAbsent(Session session);

    /** Derive a short first-message title once, without changing model context. */
    default void nameIfAbsent(UUID id, String message) {
        var found = findManaged(id);
        if (found.isEmpty() || found.get().getName() != null) return;
        String title = message.strip().replaceAll("\\s+", " ");
        int count = title.codePointCount(0, title.length());
        if (count > 80) title = title.substring(0, title.offsetByCodePoints(0, 80)) + "…";
        try {
            save(found.get().toBuilder().name(title).build());
        } catch (org.springframework.dao.OptimisticLockingFailureException conflict) {
            /* A concurrent edit wins. */
        }
    }

    Optional<Session> findManaged(UUID sessionId);

    /**
     * Configuration snapshot used for optimistic writes; implementations with managed entities copy
     * under their lock.
     */
    default Optional<Session> snapshot(UUID sessionId) {
        return findManaged(sessionId).map(session -> session.toBuilder().build());
    }

    List<Session> listManaged(UUID agentId, int page, int size);

    /** Lightweight discovery of ordinary Sessions; no history, memory or model configuration. */
    record Candidate(UUID sessionId, UUID agentId, String name, java.time.Instant updatedAt) {}
    List<Candidate> discover(UUID ownerId, UUID excludeSessionId, String query, int offset, int limit);

    /**
     * Saves a detached configuration using its read version; missing sessions are never recreated.
     */
    Optional<Session> save(Session session);

    Optional<Session> remove(UUID sessionId);

    default List<Session> listAllManaged(UUID agentId) {
        var result = new java.util.ArrayList<Session>();
        for (int page = 0; ; page++) {
            var entries = listManaged(agentId, page, 100);
            result.addAll(entries);
            if (entries.size() < 100) return result;
        }
    }
}
