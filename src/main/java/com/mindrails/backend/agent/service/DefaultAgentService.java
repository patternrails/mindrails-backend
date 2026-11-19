package com.mindrails.backend.agent.service;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.TaskExecutionRequest;
import com.mindrails.backend.agent.model.TaskExecution;
import com.mindrails.backend.agent.model.TaskReply;
import com.mindrails.backend.agent.model.Task;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.agent.service.internal.SessionService;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.provider.ProviderRegistry;
import com.mindrails.backend.provider.SnapshotProviderRegistry;
import com.mindrails.backend.memory.agent.AgentMemoryService;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Agent operations independent of the selected registry implementation. */
@Service
public class DefaultAgentService implements AgentService {

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.mindrails.backend.agent.service.AgentResourceService resources;
    private final AgentRegistry agents;
    private final SessionService sessionService;
    // Guard membership while copying outside this facade's monitor.
    private final java.util.Map<UUID, UUID> forkingSessions = new java.util.HashMap<>();
    private final ProviderRegistry providers;
    private final AgentMemoryService memory;
    @Autowired(required = false) private com.mindrails.backend.agent.service.internal.TaskService taskService;
    @Autowired(required = false) private com.mindrails.backend.agent.registry.TaskRegistry taskRegistry;
    @Autowired(required = false) private com.mindrails.backend.agent.service.internal.DefaultTaskExecutionService taskExecutions;

    @Autowired
    public DefaultAgentService(AgentRegistry agents, SessionService sessionService,
            ProviderRegistry providers, ObjectProvider<AgentMemoryService> memory) {
        this(agents, sessionService, providers, memory.getIfAvailable());
    }

    public DefaultAgentService(AgentRegistry agents, SessionService sessions, ProviderRegistry providers) {
        this(agents, sessions, providers, (AgentMemoryService) null);
    }

    public DefaultAgentService(AgentRegistry agents, SessionService sessionService,
            ProviderRegistry providers, AgentMemoryService memory) {
        this.agents = agents;
        this.sessionService = sessionService;
        this.providers = providers;
        this.memory = memory;
    }

    public DefaultAgentService(AgentRegistry agents, SessionService sessions, List<Provider> providers) {
        this(agents, sessions, new SnapshotProviderRegistry(providers));
    }

    @Override
    public synchronized ServiceResult<UUID> create(Agent agent) {
        if (!validAgent(agent)) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Agent name must not be blank");
        }
        // IMPORTANT: This is the primary assembly point for a complete Agent.
        // Future resource bindings and Session initialization belong here.
        Agent stored = agent.clone();
        Instant now = Instant.now();
        stored.setId(UUID.randomUUID());
        stored.setOwnerId(com.mindrails.backend.identity.ResourceAccess.userId()==null ? com.mindrails.backend.identity.ResourceAccess.owner(agent.getOwnerId()) : com.mindrails.backend.identity.ResourceAccess.creationOwner());
        if(resources!=null) { resources.validateSkills(stored.getOwnerId(),stored.getSkillIds()); resources.validateConnections(stored.getOwnerId(),stored.getConnectionIds()); resources.validateKnowledge(stored.getOwnerId(),stored.getKnowledgeBaseIds()); }
        // Sessions are created through SessionService; do not accept unattached nested objects.
        stored.setSessions(new ArrayList<>());
        stored.setTasks(new ArrayList<>());

        // for skills and memory and others
        stored.setMemoryId(UUID.randomUUID());

        stored.setCreatedAt(now);
        stored.setUpdatedAt(now);
        while (!agents.insertIfAbsent(stored)) stored.setId(UUID.randomUUID());
        return ServiceResult.success(stored.getId());
    }

    @Override
    public synchronized ServiceResult<Void> update(UUID id, Agent agent) {
        if (id == null || !validAgent(agent)) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Agent ID and a non-blank name are required");
        }
        Optional<Agent> updated = agents.mutate(id, stored -> {
            stored.setName(agent.getName());
            stored.setDescription(agent.getDescription());
            stored.setGoal(agent.getGoal());
            stored.setSystemMessage(agent.getSystemMessage());
            stored.setUpdatedAt(Instant.now());
        });
        if (updated.isEmpty()) {
            return ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent not found");
        }
        return ServiceResult.success(null);
    }

    @Override
    public synchronized ServiceResult<Agent> get(UUID id) {
        if (id == null) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Agent ID is required");
        }
        return agents.findManaged(id)
                .<ServiceResult<Agent>>map(agent -> ServiceResult.success(agent.clone()))
                .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent not found"));
    }

    @Override
    public synchronized ServiceResult<List<Agent>> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT,
                    "page must be non-negative and size must be between 1 and 100");
        }
        return ServiceResult.success(agents.listManaged(page, size));
    }

    @Override
    public synchronized ServiceResult<Void> delete(UUID id) {
        synchronized (taskRegistry == null ? this : taskRegistry) {
            return deleteAgentWithTaskGuard(id);
        }
    }

    private ServiceResult<Void> deleteAgentWithTaskGuard(UUID id) {
        if (id == null) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Agent ID is required");
        }
        return withAgent(id, agent -> {
            if (forkingSessions.containsValue(id)) return ServiceResult.failure(ErrorCode.CONFLICT, "An Agent Session is being forked");
            var removed = new java.util.concurrent.atomic.AtomicBoolean();
            Supplier<ServiceResult<Void>> remove = () -> {
                boolean exists = agents.remove(id).isPresent();
                removed.set(exists);
                return exists ? ServiceResult.success() : ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent not found");
            };
            try {
                Supplier<ServiceResult<Void>> deletion = () -> {
                    if (taskService != null) {
                        var tasksDeleted = taskService.deleteAll(id);
                        if (tasksDeleted instanceof ServiceResult.Failure<Void>) return tasksDeleted;
                    }
                    for (var session : List.copyOf(agent.getSessions())) {
                        var result = deleteSession(id, session.getId());
                        if (result instanceof ServiceResult.Failure<Void>) return result;
                    }
                    return remove.get();
                };
                var result = memory == null ? deletion.get() : memory.deleteWithAgent(agent, deletion);
                if (result instanceof ServiceResult.Failure<?> && removed.get() && !agents.derivesSessionMembership()) {
                    agents.insertIfAbsent(agent);
                }
                return result;
            } catch (RuntimeException | Error failure) {
                        // Memory-backed parents participate in rollback when SQL-backed memory
                        // cleanup fails.
                if (removed.get() && !agents.derivesSessionMembership()) agents.insertIfAbsent(agent);
                throw failure;
            }
        });
    }

    @Override
    public synchronized ServiceResult<UUID> startSession(UUID agentId, UUID providerId) {
        return withAgent(agentId, agent -> withProvider(providerId, provider -> {
            Instant now = Instant.now();
            Session session = Session.builder()
                    .id(UUID.randomUUID())
                    .parentAgentId(agent.getId())
                    .ownerId(agent.getOwnerId())
                    .provider(provider)
                    .systemMessage(agent.getSystemMessage())
                    .memoryId(UUID.randomUUID())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            return attachSession(agent, sessionService.startSession(session));
        }));
    }

    @Override
    public synchronized ServiceResult<List<Session>> listSessions(UUID agentId, int page, int size) {
        return withAgent(agentId, agent -> sessionService.listSessions(agent.getId(), page, size));
    }

    @Override
    public synchronized ServiceResult<Void> updateSession(UUID agentId, UUID sessionId, Session session,
            UUID providerId) {
        return withAgent(agentId, agent -> withOwnedSession(agent, sessionId, () -> withProvider(providerId,
                provider -> sessionService.updateSession(sessionId,
                        session == null ? null : session.toBuilder().provider(provider).build()))));
    }

    @Override
    public ServiceResult<UUID> forkSession(UUID agentId, UUID sessionId) {
        synchronized (this) {
            var access = withAgent(agentId, agent -> withOwnedSession(agent, sessionId, ServiceResult::success));
            if (access instanceof ServiceResult.Failure<?> failure) return ServiceResult.failure(failure.code(), failure.message());
            if (forkingSessions.putIfAbsent(sessionId, agentId) != null)
                return ServiceResult.failure(ErrorCode.CONFLICT, "Session is already being forked");
        }
        try {
            // Copy files outside the facade's global monitor; other Sessions remain usable.
            return sessionService.forkSession(sessionId).flatMap(fork -> {
                synchronized (this) {
                    var parent = agents.findManaged(agentId).orElse(null);
                    if (parent != null && parent.getSessions().stream().anyMatch(s -> sessionId.equals(s.getId())))
                        return attachSession(parent, ServiceResult.success(fork));
                }
                return sessionService.discardFork(fork.getId()).flatMap(ignored ->
                        ServiceResult.failure(ErrorCode.CONFLICT, "Source Session was deleted while forking"));
            });
        } finally {
            synchronized (this) { forkingSessions.remove(sessionId); }
        }
    }

    @Override
    public synchronized ServiceResult<Void> deleteSession(UUID agentId, UUID sessionId) {
        return withAgent(agentId, agent -> withOwnedSession(agent, sessionId,
                () -> (forkingSessions.containsKey(sessionId)
                        ? ServiceResult.<Void>failure(ErrorCode.CONFLICT, "Session is being forked")
                        : sessionService.deleteSession(sessionId)).onSuccess(() -> {
                    if (!agents.derivesSessionMembership()) agents.mutate(agent.getId(), stored -> {
                        List<Session> remaining = new ArrayList<>(stored.getSessions());
                        remaining.removeIf(session -> sessionId.equals(session.getId()));
                        stored.setSessions(remaining);
                    });
                })));
    }

    @Override public ServiceResult<UUID> createTask(UUID agentId, Task task, UUID providerId) { return taskService == null ? tasksUnavailable() : taskService.create(agentId, task, providerId); }
    @Override public ServiceResult<Task> getTask(UUID agentId, UUID taskId) { return taskService == null ? tasksUnavailable() : taskService.get(agentId, taskId); }
    @Override public ServiceResult<List<Task>> listTasks(UUID agentId, int page, int size) { return taskService == null ? tasksUnavailable() : taskService.list(agentId, page, size); }
    @Override public ServiceResult<Void> updateTask(UUID agentId, UUID taskId, Task task, UUID providerId) { return taskService == null ? tasksUnavailable() : taskService.update(agentId, taskId, task, providerId); }
    @Override public ServiceResult<Void> deleteTask(UUID agentId, UUID taskId) { return taskService == null ? tasksUnavailable() : taskService.delete(agentId, taskId); }
    @Override public ServiceResult<TaskReply> executeWithDetailed(UUID agentId, UUID taskId, TaskExecutionRequest request) { return taskExecutions == null ? tasksUnavailable() : taskExecutions.executeWithDetailed(agentId, taskId, request); }
    @Override public ServiceResult<List<TaskExecution>> listTaskExecutions(UUID agentId, UUID taskId, int page, int size) { return taskExecutions == null ? tasksUnavailable() : taskExecutions.list(agentId, taskId, page, size); }
    @Override public ServiceResult<TaskExecution> getTaskExecution(UUID agentId, UUID taskId, UUID executionId) { return taskExecutions == null ? tasksUnavailable() : taskExecutions.get(agentId, taskId, executionId); }
    @Override public ServiceResult<Void> cancelTaskExecution(UUID agentId, UUID taskId, UUID executionId) { return taskExecutions == null ? tasksUnavailable() : taskExecutions.cancel(agentId, taskId, executionId); }

    private static <T> ServiceResult<T> tasksUnavailable() {
        return ServiceResult.failure(ErrorCode.NOT_IMPLEMENTED, "Task services are not configured");
    }

    private ServiceResult<UUID> attachSession(Agent agent, ServiceResult<Session> result) {
        return result.map(session -> {
            session.setParentAgentId(agent.getId());
            if (!agents.derivesSessionMembership()) agents.mutate(agent.getId(), stored -> {
                List<Session> agentSessions = new ArrayList<>(stored.getSessions());
                agentSessions.add(session);
                stored.setSessions(agentSessions);
            });
            return session.getId();
        });
    }

    private <T> ServiceResult<T> withOwnedSession(Agent agent, UUID sessionId,
            Supplier<ServiceResult<T>> operation) {
        return Optional.ofNullable(sessionId)
                .flatMap(id -> agent.getSessions().stream()
                        .filter(session -> id.equals(session.getId())
                                && agent.getId().equals(session.getParentAgentId()))
                        .findFirst())
                .map(session -> operation.get())
                .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found"));
    }

    private <T> ServiceResult<T> withAgent(UUID agentId, Function<Agent, ServiceResult<T>> operation) {
        return Optional.ofNullable(agentId)
                .flatMap(agents::findManaged)
                .map(operation)
                .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent not found"));
    }

    private <T> ServiceResult<T> withProvider(UUID providerId, Function<Provider, ServiceResult<T>> operation) {
        return Optional.ofNullable(providerId)
                .flatMap(providers::find)
                .map(operation)
                .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "Provider not found"));
    }

    private boolean validAgent(Agent agent) {
        return agent != null && agent.getName() != null && !agent.getName().isBlank();
    }

}
