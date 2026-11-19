package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.Task;
import java.util.*;
import com.mindrails.backend.identity.ResourceAccess;
import org.springframework.dao.OptimisticLockingFailureException;

public final class InMemoryTaskRegistry implements TaskRegistry {
    private final Map<UUID, Task> tasks = new HashMap<>();
    private final com.mindrails.backend.provider.ProviderRegistry providers;
    public InMemoryTaskRegistry() { this(null); }
    public InMemoryTaskRegistry(com.mindrails.backend.provider.ProviderRegistry providers) { this.providers=providers; }
    private Task snapshot(Task task) {
        Task copy=task.snapshot();
        if(providers!=null)copy.setProvider(providers.find(task.getProvider().getId()).orElseThrow(() -> new IllegalStateException("Task Provider no longer exists")));
        return copy.snapshot();
    }
    public synchronized boolean referencesProvider(UUID id) { return tasks.values().stream().anyMatch(t -> id.equals(t.getProvider().getId())); }
    public synchronized boolean insertIfAbsent(Task task) { task.setOwnerId(ResourceAccess.owner(task.getOwnerId())); ResourceAccess.requireOwner(task.getOwnerId()); return tasks.putIfAbsent(task.getId(), task.snapshot()) == null; }
    public synchronized Optional<Task> find(UUID id) { return Optional.ofNullable(tasks.get(id)).filter(t -> ResourceAccess.visible(t.getOwnerId())).map(this::snapshot); }
    public synchronized List<Task> list(UUID agent, int page, int size) {
        return tasks.values().stream().filter(t -> ResourceAccess.visible(t.getOwnerId())).filter(t -> agent.equals(t.getParentAgentId()))
                .sorted(Comparator.comparing(Task::getCreatedAt).thenComparing(Task::getId))
                .skip((long) page * size).limit(size).map(this::snapshot).toList();
    }
    public synchronized List<Task> scheduled() { return tasks.values().stream().filter(t -> t.getCronjob() != null).map(this::snapshot).toList(); }
    public synchronized Optional<Task> save(Task task) {
        Task stored = tasks.get(task.getId());
        if (stored == null || !ResourceAccess.visible(stored.getOwnerId())) return Optional.empty();
        if (!Objects.equals(stored.getOwnerId(), task.getOwnerId()) || !Objects.equals(stored.getParentAgentId(), task.getParentAgentId())) throw new IllegalArgumentException("Task owner is immutable");
        if (stored.getVersion() != task.getVersion()) throw new OptimisticLockingFailureException("Task changed concurrently");
        Task next = task.snapshot(); next.setVersion(stored.getVersion() + 1); tasks.put(next.getId(), next);
        return Optional.of(next.snapshot());
    }
    public synchronized Optional<Task> remove(UUID id) { if (find(id).isEmpty()) return Optional.empty(); return Optional.ofNullable(tasks.remove(id)).map(this::snapshot); }
}
