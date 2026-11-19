package com.mindrails.backend.agent.service;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.SessionInput;
import com.mindrails.backend.agent.model.SessionMessageSource;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.service.internal.LoopSessionStreamService;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.identity.ResourceAccess;
import dev.langchain4j.invocation.InvocationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Delivers ordinary inputs, without subscribing to the target or waiting for its answer. */
@Service
public final class SessionCommunicationService {
    public static final int MAX_HOPS = 8;
    private final SessionRegistry sessions;
    private final ObjectProvider<LoopSessionStreamService> loops;

    public SessionCommunicationService(SessionRegistry sessions, ObjectProvider<LoopSessionStreamService> loops) {
        this.sessions = sessions;
        this.loops = loops;
    }

    public ServiceResult<Receipt> communicate(UUID targetId, String message, UUID inReplyTo,
            String toolCallId, InvocationContext context) {
        LlmClient.checkCancelled(context);
        var parameters = context == null ? null : context.invocationParameters();
        if (parameters == null || parameters.get(LlmClient.SESSION_ID) == null
                || parameters.get(LlmClient.RUN_ID) == null || parameters.get(LlmClient.AGENT_ID) == null
                || parameters.get(LlmClient.OWNER_ID) == null || parameters.containsKey(LlmClient.TASK_ID)) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Communication requires an ordinary Session invocation");
        }
        UUID sourceId = parameters.get(LlmClient.SESSION_ID);
        UUID owner = parameters.get(LlmClient.OWNER_ID);
        if (targetId == null || sourceId.equals(targetId) || message == null || message.isBlank()
                || message.length() > 16000 || toolCallId == null || toolCallId.isBlank()) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "A different target Session, tool call ID and 1..16000 characters are required");
        }
        try (var ignored = ResourceAccess.as(owner)) {
            var source = sessions.findManaged(sourceId).orElse(null);
            var target = sessions.findManaged(targetId).orElse(null);
            if (source == null || target == null || !ResourceAccess.sameOwner(owner, source.getOwnerId())
                    || !ResourceAccess.sameOwner(owner, target.getOwnerId())
                    || !source.getParentAgentId().equals(parameters.get(LlmClient.AGENT_ID))) {
                return ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found");
            }
            SessionMessageSource inbound = parameters.get(LlmClient.COMMUNICATION_SOURCE);
            if (inReplyTo != null && (inbound == null || !targetId.equals(inbound.sourceSessionId())
                    || !inReplyTo.equals(parameters.get(LlmClient.REQUEST_ID)))) {
                return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "inReplyTo must identify this invocation's incoming message from the target");
            }
            int hops = inbound == null ? 1 : inbound.hops() + 1;
            if (hops > MAX_HOPS) return ServiceResult.failure(ErrorCode.CONFLICT, "Session communication hop limit reached");
            Calls calls = parameters.get(LlmClient.COMMUNICATION_CALLS);
            if (calls == null) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Session run communication context is missing");
            var args = new Arguments(targetId, message, inReplyTo);
            Attempt attempt;
            synchronized (calls) {
                var previous = calls.attempts.get(toolCallId);
                if (previous != null) {
                    if (!previous.arguments.equals(args)) return ServiceResult.failure(ErrorCode.CONFLICT, "Tool call ID was reused with different arguments");
                    attempt = previous;
                } else {
                    if (calls.attempts.size() >= 256) return ServiceResult.failure(ErrorCode.CONFLICT, "Session run communication limit reached");
                    attempt = new Attempt(args);
                    calls.attempts.put(toolCallId, attempt);
                }
            }
            // Per-call ownership; never hold a source Session or cache monitor across target enqueue.
            if (attempt.claimed.compareAndSet(false, true)) {
                try {
                    LlmClient.checkCancelled(context);
                    UUID messageId = UUID.randomUUID();
                    var provenance = new SessionMessageSource(sourceId, source.getParentAgentId(),
                            parameters.get(LlmClient.RUN_ID), inReplyTo, Instant.now(), hops);
                    attempt.result.complete(loops.getObject().enqueue(targetId,
                            new SessionInput(messageId, message, List.of(), provenance))
                            .map(unused -> new Receipt("enqueued", messageId, targetId)));
                } catch (RuntimeException | Error failure) {
                    attempt.result.completeExceptionally(failure);
                    throw failure;
                }
            }
            return attempt.result.join();
        }
    }

    public record Receipt(String status, UUID messageId, UUID targetSessionId) {}
    /** Owned by one Run's InvocationParameters, bounded and discarded with that Run. */
    public static final class Calls {
        private final Map<String, Attempt> attempts = new HashMap<>();
    }
    private record Arguments(UUID target, String message, UUID inReplyTo) {}
    private static final class Attempt {
        final Arguments arguments;
        final java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        final CompletableFuture<ServiceResult<Receipt>> result = new CompletableFuture<>();
        Attempt(Arguments arguments) { this.arguments = arguments; }
    }
}
