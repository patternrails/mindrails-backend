package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.Agent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Writable Agent registry. MySQL reads are snapshots; writes must use registry operations. */
public interface AgentRegistry extends ReadOnlyAgentRegistry {

    boolean insertIfAbsent(Agent agent);

    Optional<Agent> findManaged(UUID agentId);

    List<Agent> listManaged(int page, int size);

    Optional<Agent> mutate(UUID agentId, Consumer<Agent> update);

    Optional<Agent> remove(UUID agentId);

    default boolean derivesTaskMembership() { return false; }

    default boolean derivesSessionMembership() { return false; }
}
