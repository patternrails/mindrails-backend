package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.model.Task;
import com.mindrails.backend.common.ServiceResult;
import java.util.*;

public interface TaskService {
    ServiceResult<UUID> create(UUID agentId, Task task, UUID providerId);
    ServiceResult<Task> get(UUID agentId, UUID taskId);
    ServiceResult<List<Task>> list(UUID agentId, int page, int size);
    ServiceResult<Void> update(UUID agentId, UUID taskId, Task task, UUID providerId);
    ServiceResult<Void> delete(UUID agentId, UUID taskId);
    ServiceResult<Void> deleteAll(UUID agentId);
    record Changed(UUID taskId) {}
}
