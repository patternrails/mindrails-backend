package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Session operations independent of the selected registry and memory store. */
@Service
public class DefaultSessionService implements SessionService {

    private final SessionRegistry sessionRegistry;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private SessionForkService forks;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private DefaultLoopSessionStreamService loops;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.mindrails.backend.asset.registry.AssetRegistry assets;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.mindrails.backend.asset.service.SessionDeliverables deliverables;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.mindrails.backend.userstatus.UserSessionStatusService userStatuses;

    public DefaultSessionService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    public ServiceResult<Session> startSession(Session session) {
        if (session == null || session.getId() == null || session.getProvider() == null) {
            return ServiceResult.failure(
                    ErrorCode.INVALID_ARGUMENT, "Session ID and provider are required");
        }
        if (session.getMemoryId() == null) {
            session.setMemoryId(UUID.randomUUID());
        }
        if (!sessionRegistry.insertIfAbsent(session)) {
            return ServiceResult.failure(ErrorCode.CONFLICT, "Session ID already exists");
        }
        return ServiceResult.success(session);
    }

    @Override
    public ServiceResult<List<Session>> listSessions(UUID agentId, int page, int size) {
        if (agentId == null || page < 0 || size < 1 || size > 100) {
            return ServiceResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "Agent ID, non-negative page, and size between 1 and 100 are required");
        }
        return ServiceResult.success(sessionRegistry.listManaged(agentId, page, size));
    }

    @Override
    public ServiceResult<Void> updateSession(UUID sessionId, Session session) {
        return loops == null || sessionId == null ? updateLocked(sessionId, session)
                : loops.tryWithSessionLock(sessionId, () -> updateLocked(sessionId, session));
    }

    @Override
    public ServiceResult<Session> forkSession(UUID sessionId) {
        if (findSession(sessionId).isEmpty()) return ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found");
        return forks == null ? ServiceResult.failure(ErrorCode.NOT_IMPLEMENTED, "Session fork storage is not configured")
                : forks.fork(sessionId);
    }

    @Override
    public ServiceResult<Void> deleteSession(UUID sessionId) {
        return loops == null || sessionId == null ? deleteLocked(sessionId)
                : loops.tryWithSessionLock(sessionId, () -> deleteLocked(sessionId));
    }

    @Override
    public ServiceResult<Void> discardFork(UUID sessionId) {
        return forks == null ? deleteSession(sessionId) : forks.discard(sessionId);
    }

    private ServiceResult<Void> updateLocked(UUID sessionId, Session session) {
        if (sessionId == null || session == null || session.getProvider() == null) {
            return ServiceResult.failure(
                    ErrorCode.INVALID_ARGUMENT, "Session ID and session provider are required");
        }
        Session stored = findSession(sessionId).orElse(null);
        if (stored == null) {
            return ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found");
        }
        if (session.getMemoryId() != null && !session.getMemoryId().equals(stored.getMemoryId())) {
            return ServiceResult.failure(
                    ErrorCode.INVALID_ARGUMENT, "Session memory ID cannot be changed");
        }
        Session update =
                stored.toBuilder()
                        .name(
                                session.getName() == null || session.getName().isBlank()
                                        ? null
                                        : session.getName().strip())
                        .provider(session.getProvider())
                        .systemMessage(session.getSystemMessage())
                        .updatedAt(Instant.now())
                        .build();
        return sessionRegistry
                .save(update)
                .<ServiceResult<Void>>map(
                        updated -> {
                            return ServiceResult.success();
                        })
                .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found"));
    }

    private ServiceResult<Void> deleteLocked(UUID sessionId) {
        if (deliverables != null && findSession(sessionId).isPresent()) deliverables.deleteSession(sessionId);
        return sessionRegistry
                .remove(sessionId)
                .<ServiceResult<Void>>map(
                        session -> {
                            if (userStatuses != null) userStatuses.remove(session.getOwnerId(), sessionId);
                            if(assets!=null)assets.clearSession(sessionId);
                            return ServiceResult.success();
                        })
                .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found"));
    }

    private Optional<Session> findSession(UUID sessionId) {
        return sessionRegistry.findManaged(sessionId);
    }
}
