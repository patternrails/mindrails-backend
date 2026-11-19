package com.mindrails.backend.agent.api.web;

import static com.mindrails.backend.common.ServiceResponses.response;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.service.AgentService;
import com.mindrails.backend.common.ServiceResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {


    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public ResponseEntity<UUID> create(@Valid @RequestBody CreateAgentRequest request) {
        return response(agentService.create(Agent.builder()
                .ownerId(com.mindrails.backend.identity.ResourceAccess.creationOwner())
                .name(request.getName())
                .description(request.getDescription())
                .goal(request.getGoal())
                .systemMessage(request.getSystemMessage())
                .build()), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody UpdateAgentRequest request) {
        return response(agentService.update(id, Agent.builder()
                .id(id)
                .ownerId(com.mindrails.backend.identity.ResourceAccess.creationOwner())
                .name(request.getName())
                .description(request.getDescription())
                .goal(request.getGoal())
                .systemMessage(request.getSystemMessage())
                .build()), HttpStatus.NO_CONTENT);
    }

    @GetMapping
    public ResponseEntity<List<Agent>> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            return response(ServiceResult.failure(ServiceResult.ErrorCode.INVALID_ARGUMENT,
                    "page must be non-negative and size must be between 1 and 100"), HttpStatus.OK);
        }
        return response(agentService.list(page, size), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Agent> get(@PathVariable UUID id) {
        return response(agentService.get(id), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return response(agentService.delete(id), HttpStatus.NO_CONTENT);
    }

}
