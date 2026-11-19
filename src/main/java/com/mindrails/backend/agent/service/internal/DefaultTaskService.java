package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.model.Task;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.common.ServiceResult;
import static com.mindrails.backend.common.ServiceResult.ErrorCode.*;
import com.mindrails.backend.provider.ProviderRegistry;
import java.time.Instant;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class DefaultTaskService implements TaskService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.mindrails.backend.agent.service.AgentResourceService resources;

    private final AgentRegistry agents;
    private final TaskRegistry tasks;
    private final ProviderRegistry providers;
    private final DefaultTaskExecutionService executions;
    private final ApplicationEventPublisher events;
    public DefaultTaskService(AgentRegistry agents, TaskRegistry tasks, ProviderRegistry providers,
            DefaultTaskExecutionService executions, ApplicationEventPublisher events) {
        this.agents=agents; this.tasks=tasks; this.providers=providers; this.executions=executions; this.events=events;
    }
    private String validate(Task task, UUID provider) {
        if (task == null || provider == null || task.getInstructions() == null || task.getInstructions().isBlank()
                || task.getInstructions().length() > 100000) return "Provider and instructions (1–100000 characters) are required";
        if (task.getName() != null && task.getName().length() > 160) return "Task name must not exceed 160 characters";
        if (task.getSkillIds() != null && (task.getSkillIds().size() > 100 || task.getSkillIds().stream().anyMatch(Objects::isNull))) return "Invalid skill IDs";
        if (task.getCronjob() != null && task.getCronjob().length() > 255) return "cronjob must not exceed 255 characters";
        if (task.getCronjob() != null && !task.getCronjob().isBlank()) {
            try { if (new UnixCron(task.getCronjob()).next(Instant.now(), java.time.ZoneOffset.UTC) == null) return "cronjob has no future occurrence"; }
            catch (IllegalArgumentException e) { return e.getMessage(); }
        }
        return null;
    }
    public ServiceResult<UUID> create(UUID agent, Task input, UUID providerId) {
        String error=validate(input, providerId);
        if (agent == null || error != null) return ServiceResult.failure(INVALID_ARGUMENT, error == null ? "Agent ID is required" : error);
        synchronized (tasks) {
            if (agents.findManaged(agent).isEmpty()) return ServiceResult.failure(NOT_FOUND, "Agent not found");
            var provider=providers.find(providerId).orElse(null);
            if (provider == null) return ServiceResult.failure(NOT_FOUND, "Provider not found");
            if(resources!=null) resources.validateSkills(agents.findManaged(agent).orElseThrow().getOwnerId(),ids(input));
            var now=Instant.now();
            Task task=Task.builder().id(UUID.randomUUID()).parentAgentId(agent).ownerId(agents.findManaged(agent).orElseThrow().getOwnerId()).name(normalize(input.getName()))
                    .provider(provider).instructions(input.getInstructions()).skillIds(ids(input))
                    .cronjob(normalizeCron(input.getCronjob())).createdAt(now).updatedAt(now).build();
            while (!tasks.insertIfAbsent(task)) task.setId(UUID.randomUUID());
            refreshMembership(agent); events.publishEvent(new Changed(task.getId()));
            return ServiceResult.success(task.getId());
        }
    }
    public ServiceResult<Task> get(UUID agent, UUID id) { return executions.owned(agent, id); }
    public ServiceResult<List<Task>> list(UUID agent, int page, int size) {
        if (agent == null || page < 0 || size < 1 || size > 100) return ServiceResult.failure(INVALID_ARGUMENT, "Invalid Agent or pagination");
        if (agents.findManaged(agent).isEmpty()) return ServiceResult.failure(NOT_FOUND, "Agent not found");
        return ServiceResult.success(tasks.list(agent, page, size));
    }
    public ServiceResult<Void> update(UUID agent, UUID id, Task input, UUID providerId) {
        String error=validate(input,providerId);
        if (error != null) return ServiceResult.failure(INVALID_ARGUMENT,error);
        synchronized (tasks) {
            return get(agent,id).flatMap(old -> {
                var provider=providers.find(providerId).orElse(null);
                if (provider == null) return ServiceResult.failure(NOT_FOUND,"Provider not found");
                if(resources!=null) resources.validateSkills(old.getOwnerId(),ids(input));
                var next=old.toBuilder().name(normalize(input.getName())).instructions(input.getInstructions())
                        .provider(provider).skillIds(ids(input)).cronjob(normalizeCron(input.getCronjob())).updatedAt(Instant.now()).build();
                try {
                    if (tasks.save(next).isEmpty()) return ServiceResult.failure(NOT_FOUND,"Task not found");
                } catch (org.springframework.dao.OptimisticLockingFailureException conflict) {
                    return ServiceResult.failure(CONFLICT,"Task changed concurrently");
                }
                refreshMembership(agent); events.publishEvent(new Changed(id)); return ServiceResult.success();
            });
        }
    }
    public ServiceResult<Void> delete(UUID agent, UUID id) {
        synchronized (tasks) {
            return get(agent,id).flatMap(task -> {
                if (executions.active(id) != null) return ServiceResult.failure(CONFLICT,"Task has an active execution");
                tasks.remove(id); refreshMembership(agent); events.publishEvent(new Changed(id)); return ServiceResult.success();
            });
        }
    }
    public ServiceResult<Void> deleteAll(UUID agent) {
        synchronized (tasks) {
            var all=new ArrayList<Task>();
            for (int page=0;;page++) { var batch=tasks.list(agent,page,100); all.addAll(batch); if(batch.size()<100) break; }
            if (all.stream().anyMatch(t -> executions.active(t.getId()) != null)) return ServiceResult.failure(CONFLICT,"Agent has an active Task execution");
            all.forEach(t -> { tasks.remove(t.getId()); events.publishEvent(new Changed(t.getId())); });
            refreshMembership(agent); return ServiceResult.success();
        }
    }
    private void refreshMembership(UUID agent) {
        if (agents.derivesTaskMembership()) return;
        var all=new ArrayList<Task>();
        for (int page=0;;page++) { var batch=tasks.list(agent,page,100); all.addAll(batch); if(batch.size()<100) break; }
        agents.mutate(agent,a -> a.setTasks(all));
    }
    private static String normalize(String v) { return v == null || v.isBlank() ? null : v.strip(); }
    private static String normalizeCron(String v) { var s=normalize(v); return s == null ? null : s.replaceAll("\\s+"," "); }
    private static List<UUID> ids(Task t) { return t.getSkillIds() == null ? List.of() : t.getSkillIds().stream().distinct().toList(); }
}
