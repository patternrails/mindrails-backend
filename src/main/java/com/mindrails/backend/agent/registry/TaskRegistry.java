package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.Task;
import java.util.*;

public interface TaskRegistry {
    boolean insertIfAbsent(Task task);
    Optional<Task> find(UUID id);
    List<Task> list(UUID agentId, int page, int size);
    List<Task> scheduled();
    boolean referencesProvider(UUID providerId);
    Optional<Task> save(Task task);
    Optional<Task> remove(UUID id);
}
