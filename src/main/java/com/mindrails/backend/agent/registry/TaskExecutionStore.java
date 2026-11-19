package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.TaskExecution;
import java.util.*;

public interface TaskExecutionStore {
    void insert(TaskExecution execution);
    void save(TaskExecution execution);
    void append(UUID executionId, TaskExecution.Entry entry);
    Optional<TaskExecution> find(UUID id);
    List<TaskExecution> list(UUID agentId, UUID taskId, int page, int size);
    /** Single-executor deployment: unfinished records cannot resume after process restart. */
    List<TaskExecution> recoverInterrupted();
}
