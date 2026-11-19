package com.mindrails.backend.knowledge.service;

import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.knowledge.model.KnowledgeBaseState;
import com.mindrails.backend.knowledge.model.KnowledgeBinding;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Agent is the sole binding authority. Sessions only resolve their parent Agent; they carry no knowledge binding. */
@Component
public final class DefaultKnowledgeBindingService implements KnowledgeBindingService {
    private final AgentRegistry agents;
    private final SessionRegistry sessions;
    private final ObjectProvider<KnowledgeRegistry> repositories;

    public DefaultKnowledgeBindingService(
            AgentRegistry agents,
            SessionRegistry sessions,
            ObjectProvider<KnowledgeRegistry> repositories) {
        this.agents = agents;
        this.sessions = sessions;
        this.repositories = repositories;
    }

    public KnowledgeBinding agent(UUID agentId) {
        var agent = agents.findManaged(agentId).orElseThrow(KnowledgeException::missing);
        var registry = repositories.getIfAvailable();
        if (registry != null) for (var id : agent.getKnowledgeBaseIds())
            com.mindrails.backend.identity.ResourceAccess.requireBinding(agent.getOwnerId(), registry.getKnowledgeBase(id).getOwnerId());
        return new KnowledgeBinding(agentId, agent.getKnowledgeBaseIds(), agent.getVersion());
    }

    public KnowledgeBinding session(UUID sessionId) {
        var session = sessions.findManaged(sessionId).orElseThrow(KnowledgeException::missing);
        return agent(session.getParentAgentId());
    }

    public void check(KnowledgeBinding binding) {
        if (!binding.equals(agent(binding.agentId()))) {
            throw KnowledgeException.conflict(
                    "Agent knowledge binding changed; retry in the next turn");
        }
    }

    public void checkSession(UUID sessionId, KnowledgeBinding expected) {
        if (!expected.equals(session(sessionId))) {
            throw KnowledgeException.conflict("Agent knowledge binding changed");
        }
    }

    public synchronized KnowledgeBinding bind(UUID agentId, List<UUID> knowledgeBaseIds, long expectedVersion) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.size() > 100 || knowledgeBaseIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw KnowledgeException.invalid("knowledgeBaseIds must contain at most 100 non-null IDs");
        }
        var owner=agents.findManaged(agentId).orElseThrow(KnowledgeException::missing);
        var ids = knowledgeBaseIds.stream().distinct().sorted().toList();
        var registry = repositories.getIfAvailable();
        if (!ids.isEmpty() && registry == null) throw KnowledgeException.disabled();
        Supplier<KnowledgeBinding> change = () -> {
            if (registry != null) {
                // Lock both removed and added bases so deletion cannot race an assignment.
                var locked = new java.util.TreeSet<>(agent(agentId).knowledgeBaseIds());
                locked.addAll(ids);
                locked.forEach(registry::lockKnowledgeBase);
                for (var id : ids) {
                    var base = registry.getKnowledgeBase(id);
                    com.mindrails.backend.identity.ResourceAccess.requireBinding(agents.findManaged(agentId).orElseThrow(KnowledgeException::missing).getOwnerId(), base.getOwnerId());
                    if (!List.of(KnowledgeBaseState.ACTIVE, KnowledgeBaseState.REBUILDING).contains(base.getState())) {
                        throw KnowledgeException.conflict("Knowledge base is being deleted");
                    }
                }
            }
            agents.mutate(agentId, agent -> {
                if (agent.getVersion() != expectedVersion) throw KnowledgeException.conflict("Agent version changed");
                agent.setKnowledgeBaseIds(ids);
                agent.setUpdatedAt(Instant.now());
            }).orElseThrow(KnowledgeException::missing);
            return agent(agentId);
        };
        return registry == null ? change.get() : registry.transaction(change);
    }

    /** Toggle one membership without overwriting the Agent's other knowledge bases. */
    public synchronized KnowledgeBinding assign(UUID agentId, UUID knowledgeBaseId, boolean assigned, long expectedVersion) {
        var current = agent(agentId);
        if (current.agentVersion() != expectedVersion) throw KnowledgeException.conflict("Agent version changed");
        var ids = new ArrayList<>(current.knowledgeBaseIds());
        ids.remove(knowledgeBaseId);
        if (assigned) ids.add(knowledgeBaseId);
        return bind(agentId, ids, expectedVersion);
    }

    public List<KnowledgeBinding> referencing(UUID knowledgeBaseId) {
        var found = new ArrayList<KnowledgeBinding>();
        for (int page = 0; ; page++) {
            var batch = agents.listManaged(page, 100);
            for (var agent : batch) {
                if (agent.getKnowledgeBaseIds().contains(knowledgeBaseId)) {
                    found.add(
                            new KnowledgeBinding(
                                    agent.getId(), agent.getKnowledgeBaseIds(), agent.getVersion()));
                }
            }
            if (batch.size() < 100) {
                return List.copyOf(found);
            }
        }
    }
}
