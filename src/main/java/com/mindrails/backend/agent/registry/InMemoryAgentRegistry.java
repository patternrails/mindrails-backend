package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.identity.ResourceAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Shared, thread-safe in-memory store. It keeps the existing managed Agent identity. */
final class InMemoryAgentRegistry implements AgentRegistry {

    private final Map<UUID, Agent> agents = new HashMap<>();

    @Override
    public synchronized Optional<AgentView> find(UUID agentId) {
        Agent agent = agents.get(agentId);
        if (agent == null || !ResourceAccess.visible(agent.getOwnerId())) return Optional.empty();
        Set<UUID> sessionIds = agent.getSessions() == null ? Set.of() : agent.getSessions().stream()
                .filter(session -> session != null && session.getId() != null
                        && agent.getId().equals(session.getParentAgentId()))
                .map(Session::getId)
                .collect(Collectors.toSet());
        Set<UUID> taskIds = agent.getTasks() == null ? Set.of() : agent.getTasks().stream()
                .filter(task -> task != null && task.getId() != null && agent.getId().equals(task.getParentAgentId()))
                .map(com.mindrails.backend.agent.model.Task::getId).collect(Collectors.toSet());
        return Optional.of(new AgentView(agent.getId(), sessionIds, taskIds));
    }

    @Override
    public synchronized boolean insertIfAbsent(Agent agent) {
        if (agent == null || agent.getId() == null) throw new IllegalArgumentException("Agent ID is required");
        agent.setOwnerId(ResourceAccess.owner(agent.getOwnerId()));
        ResourceAccess.requireOwner(agent.getOwnerId());
        return agents.putIfAbsent(agent.getId(), agent) == null;
    }

    @Override
    public synchronized Optional<Agent> findManaged(UUID agentId) {
        return Optional.ofNullable(agents.get(agentId)).filter(item -> ResourceAccess.visible(item.getOwnerId()));
    }

    @Override
    public synchronized List<Agent> listManaged(int page, int size) {
        return agents.values().stream()
                .filter(item -> ResourceAccess.visible(item.getOwnerId()))
                .sorted(Comparator.comparing(Agent::getCreatedAt).thenComparing(Agent::getId))
                .skip((long) page * size)
                .limit(size)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public synchronized Optional<Agent> mutate(UUID agentId, Consumer<Agent> update) {
        Agent agent = agents.get(agentId);
        if (agent == null || !ResourceAccess.visible(agent.getOwnerId())) return Optional.empty();
        UUID owner = agent.getOwnerId();
        update.accept(agent);
        if (!Objects.equals(owner, agent.getOwnerId())) { agent.setOwnerId(owner); throw new IllegalArgumentException("Resource owner is immutable"); }
        agent.setVersion(agent.getVersion() + 1);
        return Optional.of(agent);
    }

    @Override
    public synchronized Optional<Agent> remove(UUID agentId) {
        if (findManaged(agentId).isEmpty()) return Optional.empty();
        return Optional.ofNullable(agents.remove(agentId));
    }
}
