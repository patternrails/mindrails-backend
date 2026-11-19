package com.mindrails.backend.agent.model;

import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

public interface LlmClient extends dev.langchain4j.service.memory.ChatMemoryAccess {
    String RUN_ID = "mindrails.client.runId";
    String OWNER_ID = "mindrails.client.ownerId";
    String AGENT_ID = "mindrails.client.agentId";
    String SESSION_ID = "mindrails.client.sessionId";
    String TASK_ID = "mindrails.client.taskId";
    String MEMORY_ID = "mindrails.client.memoryId";
    String PROVIDER_ID = "mindrails.client.providerId";
    String PROVIDER_NAME = "mindrails.client.providerName";
    String MODEL_NAME = "mindrails.client.modelName";
    String SESSION_VERSION = "mindrails.client.sessionVersion";
    String SKILL_IDS = "mindrails.client.skillIds";
    String REQUEST_ID = "mindrails.client.requestId";
    String TRANSPORT = "mindrails.client.transport";
    String STARTED_AT = "mindrails.client.startedAt";
    String COMMUNICATION_SOURCE = "mindrails.client.communicationSource";
    String ATTACHMENTS = "mindrails.client.attachments";
    String COMMUNICATION_CALLS = "mindrails.client.communicationCalls";

    static InvocationParameters parameters(Task task, UUID executionId, UUID requestId, Instant startedAt, String transport) {
        var values = new HashMap<String, Object>();
        values.put(RUN_ID, executionId);
        values.put(AGENT_ID, task.getParentAgentId());
        values.put(OWNER_ID, task.getOwnerId());
        values.put(TASK_ID, task.getId());
        values.put(MEMORY_ID, executionId);
        values.put(SESSION_VERSION, task.getVersion());
        values.put(SKILL_IDS, List.copyOf(task.getSkillIds()));
        values.put(PROVIDER_ID, task.getProvider().getId());
        values.put(PROVIDER_NAME, task.getProvider().getName());
        values.put(MODEL_NAME, task.getProvider().getModelName());
        values.put(REQUEST_ID, requestId);
        values.put(TRANSPORT, transport);
        values.put(STARTED_AT, startedAt);
        values.values().removeIf(Objects::isNull);
        return InvocationParameters.from(values);
    }

    /** Immutable correlation values, with no collector or recorder stored in the invocation. */
    static InvocationParameters parameters(Session session, UUID requestId, String transport) {
        var values = new HashMap<String, Object>();
        values.put(RUN_ID, UUID.randomUUID());
        values.put(AGENT_ID, session.getParentAgentId());
        values.put(OWNER_ID, session.getOwnerId());
        values.put(SESSION_ID, session.getId());
        values.put(MEMORY_ID, session.getMemoryId());
        values.put(SESSION_VERSION, session.getVersion());
        if (session.getProvider() != null) {
            values.put(PROVIDER_ID, session.getProvider().getId());
            values.put(PROVIDER_NAME, session.getProvider().getName());
            values.put(MODEL_NAME, session.getProvider().getModelName());
        }
        if (requestId != null) values.put(REQUEST_ID, requestId);
        values.put(TRANSPORT, transport);
        values.put(STARTED_AT, Instant.now());
        values.values().removeIf(Objects::isNull);
        return InvocationParameters.from(values);
    }

    String CANCELLATION = "mindrails.client.cancellation";
    String TOOL_SOURCES = "mindrails.client.toolSources";

    static CompletableFuture<Void> cancellation(InvocationContext context) {
        return context == null || context.invocationParameters() == null
                ? null : context.invocationParameters().get(CANCELLATION);
    }

    static void checkCancelled(InvocationContext context) {
        var cancellation = cancellation(context);
        if (Thread.currentThread().isInterrupted() || cancellation != null && cancellation.isDone()) {
            throw new CancellationException("Client invocation cancelled");
        }
    }

    /** Correlation values and cancellation travel with this invocation. */
    Result<List<String>> chatWithLines(@MemoryId UUID memoryId, @UserMessage String userMessage,
            InvocationParameters parameters);

    TokenStream chatWithStream(@MemoryId UUID memoryId, @UserMessage String userMessage,
            InvocationParameters parameters);

    Result<List<String>> chatWithLines(@MemoryId UUID memoryId, @UserMessage String userMessage);

    TokenStream chatWithStream(@MemoryId UUID memoryId, @UserMessage String userMessage);
}
