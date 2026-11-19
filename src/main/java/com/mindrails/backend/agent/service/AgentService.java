package com.mindrails.backend.agent.service;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.TaskExecutionRequest;
import com.mindrails.backend.agent.model.TaskExecution;
import com.mindrails.backend.agent.model.TaskReply;
import com.mindrails.backend.agent.model.Task;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.common.ServiceResult;
import java.util.List;
import java.util.UUID;

/** Operations on agents and their sessions; operational failures are returned as Failure results. */
public interface AgentService {

    ServiceResult<UUID> create(Agent agent);

    /** Replaces editable fields of an existing agent; identity and session state must be preserved. */
    ServiceResult<Void> update(UUID id, Agent agent);

    /** A missing agent returns NOT_FOUND rather than a successful null value. */
    ServiceResult<Agent> get(UUID id);

    /** Returns one page of agents; page is zero-based and size is the requested page size. */
    ServiceResult<List<Agent>> list(int page, int size);

    ServiceResult<Void> delete(UUID id);

    ServiceResult<UUID> startSession(UUID agentId, UUID providerId);

    ServiceResult<List<Session>> listSessions(UUID agentId, int page, int size);

    ServiceResult<Void> updateSession(UUID agentId, UUID sessionId, Session session, UUID providerId);

    ServiceResult<UUID> forkSession(UUID agentId, UUID sessionId);

    ServiceResult<Void> deleteSession(UUID agentId, UUID sessionId);

    ServiceResult<UUID> createTask(UUID agentId, Task task, UUID providerId);
    ServiceResult<Task> getTask(UUID agentId, UUID taskId);
    ServiceResult<List<Task>> listTasks(UUID agentId, int page, int size);
    ServiceResult<Void> updateTask(UUID agentId, UUID taskId, Task task, UUID providerId);
    ServiceResult<Void> deleteTask(UUID agentId, UUID taskId);
    ServiceResult<TaskReply> executeWithDetailed(UUID agentId, UUID taskId, TaskExecutionRequest request);
    ServiceResult<List<TaskExecution>> listTaskExecutions(UUID agentId, UUID taskId, int page, int size);
    ServiceResult<TaskExecution> getTaskExecution(UUID agentId, UUID taskId, UUID executionId);
    ServiceResult<Void> cancelTaskExecution(UUID agentId, UUID taskId, UUID executionId);
}
