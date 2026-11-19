package com.mindrails.backend.knowledge.api.web;

import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.knowledge.model.KnowledgeBinding;
import com.mindrails.backend.knowledge.model.KnowledgePage;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.service.KnowledgeBindingService;
import com.mindrails.backend.knowledge.service.KnowledgeException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Library-owned assignment UI. Each save has optimistic concurrency and preserves other memberships. */
@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/agents")
public class KnowledgeAssignmentController {
    private final AgentRegistry agents;
    private final KnowledgeBindingService bindings;
    private final ObjectProvider<KnowledgeRegistry> repositories;

    public KnowledgeAssignmentController(AgentRegistry agents, KnowledgeBindingService bindings,
            ObjectProvider<KnowledgeRegistry> repositories) {
        this.agents = agents;
        this.bindings = bindings;
        this.repositories = repositories;
    }

    public record Assignment(UUID agentId, String name, boolean assigned, long agentVersion) {}
    public record Request(Boolean assigned, Long version) {}

    @GetMapping
    public KnowledgePage<Assignment> list(@PathVariable UUID knowledgeBaseId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) throw KnowledgeException.invalid("Invalid page or size");
        var registry = repositories.getIfAvailable();
        if (registry == null) throw KnowledgeException.disabled();
        registry.getKnowledgeBase(knowledgeBaseId);
        var items = new java.util.ArrayList<Assignment>();
        // Read a stable page in registry order; total is derived without exposing Agent configuration.
        long total = 0;
        for (int batch = 0; ; batch++) {
            var found = agents.listManaged(batch, 100);
            for (var agent : found) {
                if (total >= (long) page * size && items.size() < size) {
                    items.add(new Assignment(agent.getId(), agent.getName(),
                            agent.getKnowledgeBaseIds().contains(knowledgeBaseId), agent.getVersion()));
                }
                total++;
            }
            if (found.size() < 100) break;
        }
        return new KnowledgePage<>(java.util.List.copyOf(items), total, page, size, ((long) page + 1) * size < total);
    }

    @PutMapping("/{agentId}")
    public KnowledgeBinding assign(@PathVariable UUID knowledgeBaseId, @PathVariable UUID agentId,
            @RequestBody Request input) {
        if (input.assigned() == null || input.version() == null || input.version() < 0)
            throw KnowledgeException.invalid("assigned and current Agent version are required");
        var registry = repositories.getIfAvailable();
        if (registry == null) throw KnowledgeException.disabled();
        registry.getKnowledgeBase(knowledgeBaseId);
        return bindings.assign(agentId, knowledgeBaseId, input.assigned(), input.version());
    }
}
