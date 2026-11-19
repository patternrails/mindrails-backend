package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.common.ServiceResult;
import java.util.List;
import java.util.UUID;

/** Internal session operations; AgentService owns membership and access checks. */
public interface SessionService {

    ServiceResult<Session> startSession(Session session);

    ServiceResult<List<Session>> listSessions(UUID agentId, int page, int size);

    ServiceResult<Void> updateSession(UUID sessionId, Session session);

    ServiceResult<Session> forkSession(UUID sessionId);

    ServiceResult<Void> deleteSession(UUID sessionId);

    /** Compensates an unpublished fork if its parent Agent disappears before membership is attached. */
    default ServiceResult<Void> discardFork(UUID sessionId) { return deleteSession(sessionId); }

}
