package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.Session;
import org.springframework.dao.OptimisticLockingFailureException;
import com.mindrails.backend.identity.ResourceAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared, thread-safe in-memory store. It keeps the existing managed Session identity. */
final class InMemorySessionRegistry implements SessionRegistry {

    private final Map<UUID, Session> sessions = new HashMap<>();

    @Override
    public synchronized void nameIfAbsent(UUID id, String message) {
        SessionRegistry.super.nameIfAbsent(id, message);
    }

    @Override
    public synchronized Optional<SessionView> find(UUID sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null || !ResourceAccess.visible(session.getOwnerId())) return Optional.empty();
        var provider = session.getProvider();
        return Optional.of(
                new SessionView(
                        session.getId(),
                        session.getParentAgentId(),
                        provider == null ? null : provider.getId(),
                        provider == null ? null : provider.getStreaming()));
    }

    @Override
    public synchronized boolean insertIfAbsent(Session session) {
        if (session == null || session.getId() == null)
            throw new IllegalArgumentException("Session ID is required");
        session.setOwnerId(ResourceAccess.owner(session.getOwnerId()));
        ResourceAccess.requireOwner(session.getOwnerId());
        return sessions.putIfAbsent(session.getId(), session) == null;
    }

    @Override
    public synchronized Optional<Session> findManaged(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId)).filter(item -> ResourceAccess.visible(item.getOwnerId()));
    }

    @Override
    public synchronized Optional<Session> snapshot(UUID sessionId) {
        return findManaged(sessionId).map(session -> session.toBuilder().build());
    }

    @Override
    public synchronized List<Session> listManaged(UUID agentId, int page, int size) {
        return sessions.values().stream()
                .filter(item -> ResourceAccess.visible(item.getOwnerId()))
                .filter(session -> agentId.equals(session.getParentAgentId()))
                .sorted(Comparator.comparing(Session::getCreatedAt).thenComparing(Session::getId))
                .skip((long) page * size)
                .limit(size)
                .toList();
    }

    @Override
    public synchronized List<Candidate> discover(UUID ownerId, UUID excludeSessionId, String query, int offset, int limit) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        return sessions.values().stream()
                .filter(s -> ResourceAccess.sameOwner(ownerId, s.getOwnerId()) && ResourceAccess.visible(s.getOwnerId()))
                .filter(s -> !s.getId().equals(excludeSessionId))
                .filter(s -> Objects.toString(s.getName(), "").toLowerCase(java.util.Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Session::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Session::getId))
                .skip(offset).limit(limit)
                .map(s -> new Candidate(s.getId(), s.getParentAgentId(), s.getName(), s.getUpdatedAt())).toList();
    }

    @Override
    public synchronized Optional<Session> save(Session update) {
        Session stored = sessions.get(update.getId());
        if (stored == null || !ResourceAccess.visible(stored.getOwnerId())) return Optional.empty();
        if (stored.getVersion() != update.getVersion()) {
            throw new OptimisticLockingFailureException("Session changed concurrently");
        }
        if (!Objects.equals(stored.getOwnerId(), update.getOwnerId())
                || !Objects.equals(stored.getMemoryId(), update.getMemoryId())
                || !Objects.equals(stored.getParentAgentId(), update.getParentAgentId())) {
            throw new IllegalArgumentException("Session memory and owner are immutable");
        }
        var skillIds = new ArrayList<>(update.getSkillIds());
        stored.setName(update.getName());
        stored.setProvider(update.getProvider());
        stored.setSystemMessage(update.getSystemMessage());
        stored.setSkillIds(skillIds);
        stored.setUpdatedAt(update.getUpdatedAt());
        stored.setVersion(stored.getVersion() + 1);
        return Optional.of(stored);
    }

    @Override
    public synchronized Optional<Session> remove(UUID sessionId) {
        if (findManaged(sessionId).isEmpty()) return Optional.empty();
        return Optional.ofNullable(sessions.remove(sessionId));
    }
}
