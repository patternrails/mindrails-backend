package com.mindrails.backend.agent.api.web;

import com.mindrails.backend.agent.service.AgentResourceService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/{agentId}/resources")
public class AgentResourceController {
    private final AgentResourceService resources;
    public AgentResourceController(AgentResourceService resources) { this.resources=resources; }
    @GetMapping public AgentResourceService.Bindings get(@PathVariable UUID agentId) { return resources.get(agentId); }
    @PutMapping public AgentResourceService.Bindings bind(@PathVariable UUID agentId,@RequestBody AgentResourceService.Bindings request) {
        return resources.bind(agentId,request);
    }
}
