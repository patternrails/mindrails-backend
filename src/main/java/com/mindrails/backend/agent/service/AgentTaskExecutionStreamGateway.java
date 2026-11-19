package com.mindrails.backend.agent.service;

import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.common.ServiceResult;
import java.time.Instant;
import java.util.UUID;

public interface AgentTaskExecutionStreamGateway {
    record AgentTaskScope(UUID agentId, UUID taskId) {}
    record ActiveExecution(UUID executionId, TaskExecution.Status status) {}
    record TaskSnapshot(ActiveExecution activeExecution) {}
    record Event(String type, UUID executionId, UUID requestId, Instant timestamp, Object data) {}
    @FunctionalInterface interface EventSink { void emit(Event event); }
    sealed interface Command {
        UUID requestId();
        record Execute(UUID requestId, java.util.Map<String,Object> parameters) implements Command {}
        record Cancel(UUID requestId, UUID executionId) implements Command {}
    }
    ServiceResult<TaskSnapshot> validate(AgentTaskScope scope);
    ServiceResult<UUID> reserveAndExecute(AgentTaskScope scope, Command.Execute command, EventSink sink);
    ServiceResult<Void> command(AgentTaskScope scope, Command command);
}
