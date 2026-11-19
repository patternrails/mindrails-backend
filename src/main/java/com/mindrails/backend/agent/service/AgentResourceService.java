package com.mindrails.backend.agent.service;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.identity.IdentityException;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.mcp.registry.McpRegistry;
import com.mindrails.backend.skill.SkillCatalog;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Validates resource relationships independently from the identity's administrative role. */
@Service
public class AgentResourceService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.mindrails.backend.knowledge.registry.KnowledgeRegistry knowledge;
    private final AgentRegistry agents;
    private final McpRegistry mcp;
    private final SkillCatalog skills;
    public AgentResourceService(AgentRegistry agents, McpRegistry mcp, SkillCatalog skills) {
        this.agents=agents; this.mcp=mcp; this.skills=skills;
    }
    public record Bindings(List<UUID> skillIds, List<UUID> connectionIds, long version) {}
    private Agent agent(UUID id) {
        return agents.findManaged(id).orElseThrow(() -> new IdentityException(404,"Agent not found"));
    }
    public Bindings get(UUID id) { var a=agent(id); return new Bindings(List.copyOf(a.getSkillIds()),List.copyOf(a.getConnectionIds()),a.getVersion()); }
    public synchronized Bindings bind(UUID id, Bindings input) {
        var skillIds=ids(input.skillIds()); var connectionIds=ids(input.connectionIds());
        var a=agent(id);
        validateSkills(a.getOwnerId(),skillIds);
        validateConnections(a.getOwnerId(),connectionIds);
        agents.mutate(id,stored -> {
            if(stored.getVersion()!=input.version()) throw new IdentityException(409,"Agent changed; refresh before retrying");
            stored.setSkillIds(skillIds); stored.setConnectionIds(connectionIds); stored.setUpdatedAt(Instant.now());
        }).orElseThrow(() -> new IdentityException(404,"Agent not found"));
        return get(id);
    }
    public void validateSkills(UUID owner,List<UUID> ids) { skills.validateIds(owner,ids(ids)); }
    public void validateKnowledge(UUID owner,List<UUID> ids) {
        for(var id:ids(ids)) {
            if(knowledge==null) throw new IdentityException(404,"Knowledge base not found");
            ResourceAccess.requireBinding(owner,knowledge.getKnowledgeBase(id).getOwnerId());
        }
    }
    public void validateConnections(UUID owner,List<UUID> ids) {
        for(var id:ids(ids)) {
            var server=mcp.find(id).orElseThrow(() -> new IdentityException(404,"MCP server not found"));
            ResourceAccess.requireBinding(owner,server.ownerId());
        }
    }
    private static List<UUID> ids(List<UUID> values) {
        if(values==null || values.size()>100 || values.stream().anyMatch(Objects::isNull))
            throw new IdentityException(400,"Supply at most 100 non-null resource IDs");
        return values.stream().distinct().toList();
    }
}
