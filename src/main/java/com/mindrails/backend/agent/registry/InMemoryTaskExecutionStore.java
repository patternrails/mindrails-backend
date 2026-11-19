package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.TaskExecution;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

public final class InMemoryTaskExecutionStore implements TaskExecutionStore {
    private final Map<UUID, TaskExecution> executions = new HashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private TaskExecution copy(TaskExecution e) { return json.readValue(json.writeValueAsBytes(e), TaskExecution.class); }
    public synchronized void insert(TaskExecution e) {
        if (executions.putIfAbsent(e.id(), copy(e)) != null) throw new IllegalStateException("Execution already exists");
    }
    public synchronized void save(TaskExecution e) {
        var old = executions.get(e.id());
        if (old == null) throw new IllegalStateException("Execution not found");
        if (!old.status().terminal()) executions.put(e.id(), copy(e.toBuilder().records(old.records()).build()));
    }
    public synchronized void append(UUID id, TaskExecution.Entry entry) {
        var old = executions.get(id);
        if (old == null) throw new IllegalStateException("Execution not found");
        if (old.status().terminal()) return;
        var records = new ArrayList<>(old.records()); records.add(entry);
        executions.put(id, copy(old.toBuilder().records(records).build()));
    }
    public synchronized Optional<TaskExecution> find(UUID id) { return Optional.ofNullable(executions.get(id)).filter(e -> com.mindrails.backend.identity.ResourceAccess.visible(e.ownerId())).map(this::copy); }
    public synchronized List<TaskExecution> list(UUID agent, UUID task, int page, int size) {
        return executions.values().stream().filter(e -> com.mindrails.backend.identity.ResourceAccess.visible(e.ownerId())).filter(e -> agent.equals(e.agentId()) && task.equals(e.taskId()))
                .sorted(Comparator.comparing(TaskExecution::createdAt).thenComparing(TaskExecution::id).reversed())
                .skip((long) page * size).limit(size).map(e -> copy(e.toBuilder().records(List.of()).build())).toList();
    }
    public synchronized List<TaskExecution> recoverInterrupted() {
        var recovered = new ArrayList<TaskExecution>();
        executions.replaceAll((id, e) -> {
            if(e.status().terminal())return e;
            var failed=interrupted(e);recovered.add(copy(failed));return failed;
        });
        return List.copyOf(recovered);
    }
    static TaskExecution interrupted(TaskExecution e) {
        Instant now = Instant.now();
        return e.toBuilder().status(TaskExecution.Status.FAILED).finishedAt(now)
                .durationMs(Duration.between(e.createdAt(), now).toMillis())
                .error(new TaskExecution.Failure("EXECUTION_INTERRUPTED", "Execution process stopped; it was not replayed")).build();
    }
}
