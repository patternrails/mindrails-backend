package com.mindrails.backend.mcp.service;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.identity.IdentityException;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.mcp.model.McpServer;
import com.mindrails.backend.mcp.registry.McpRegistry;

import dev.langchain4j.invocation.InvocationContext;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves connection scope immediately before discovery or execution using the caller's identity.
 */
@Component
public final class McpToolAuthorization {
    private final AgentRegistry agents;
    private final McpRegistry registry;

    public McpToolAuthorization(AgentRegistry agents, McpRegistry registry) {
        this.agents = agents;
        this.registry = registry;
    }

    public Set<UUID> allowed(InvocationContext context) {
        if (context == null || context.invocationParameters() == null) {
            return Set.of();
        }
        var parameters = context.invocationParameters();
        UUID sessionId = parameters.get(LlmClient.SESSION_ID);
        if (sessionId != null) {
            UUID ownerId = parameters.get(LlmClient.OWNER_ID);
            if (ownerId == null) {
                return Set.of();
            }
            ResourceAccess.requireOwner(ownerId);
            try (var ignored = ResourceAccess.as(ownerId)) {
                return registry.list().stream()
                        .filter(McpServer::enabled)
                        .map(McpServer::id)
                        .collect(Collectors.toUnmodifiableSet());
            }
        }
        // Tasks use their Agent's MCP bindings.
        UUID agentId = parameters.get(LlmClient.AGENT_ID);
        if (agentId == null) {
            return Set.of();
        }
        var agent =
                agents.findManaged(agentId)
                        .orElseThrow(() -> new IdentityException(404, "Agent not found"));
        var allowed = new HashSet<UUID>();
        for (UUID connectionId : agent.getConnectionIds()) {
            var server = registry.find(connectionId).orElse(null);
            if (server != null && ResourceAccess.sameOwner(agent.getOwnerId(), server.ownerId())) {
                allowed.add(connectionId);
            }
        }
        return Set.copyOf(allowed);
    }
}
