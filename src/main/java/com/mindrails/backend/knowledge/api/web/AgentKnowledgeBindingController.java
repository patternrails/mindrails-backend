package com.mindrails.backend.knowledge.api.web;

import com.mindrails.backend.knowledge.model.KnowledgeBinding;
import com.mindrails.backend.knowledge.service.KnowledgeBindingService;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/agent/{agentId}/knowledge-bases")
public class AgentKnowledgeBindingController {
    private final KnowledgeBindingService bindings;

    public AgentKnowledgeBindingController(
            KnowledgeBindingService bindings) {
        this.bindings = bindings;
    }

    @GetMapping
    public KnowledgeBinding get(@PathVariable UUID agentId) {
        return bindings.agent(agentId);
    }

    @PutMapping
    public KnowledgeBinding bind(
            @PathVariable UUID agentId, @RequestBody KnowledgeBindingRequest input) {
        if (input.version() == null || input.version() < 0) {
            throw KnowledgeException.invalid("Current Agent version is required");
        }
        return bindings.bind(agentId, input.knowledgeBaseIds(), input.version());
    }
}
