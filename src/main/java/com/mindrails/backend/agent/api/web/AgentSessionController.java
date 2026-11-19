package com.mindrails.backend.agent.api.web;

import static com.mindrails.backend.common.ServiceResponses.response;

import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.service.AgentService;
import com.mindrails.backend.common.ServiceResult;

import jakarta.validation.Valid;

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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent/{agentId}/sessions")
public class AgentSessionController {

    private final AgentService agentService;

    public AgentSessionController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public ResponseEntity<UUID> startSession(
            @PathVariable UUID agentId, @Valid @RequestBody StartSessionRequest request) {
        return response(
                agentService.startSession(agentId, request.getProviderId()), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<Session>> listSessions(
            @PathVariable UUID agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            return response(
                    ServiceResult.failure(
                            ServiceResult.ErrorCode.INVALID_ARGUMENT,
                            "page must be non-negative and size must be between 1 and 100"),
                    HttpStatus.OK);
        }
        return response(agentService.listSessions(agentId, page, size), HttpStatus.OK);
    }

    @PutMapping("/{sessionId}")
    public ResponseEntity<Void> updateSession(
            @PathVariable UUID agentId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody UpdateSessionRequest request) {
        Session session =
                Session.builder()
                        .name(request.getName())
                        .systemMessage(request.getSystemMessage())
                        .skillIds(request.getSkillIds())
                        .memoryId(request.getMemoryId())
                        .build();
        return response(
                agentService.updateSession(agentId, sessionId, session, request.getProviderId()),
                HttpStatus.NO_CONTENT);
    }

    @PostMapping("/{sessionId}/fork")
    public ResponseEntity<UUID> forkSession(
            @PathVariable UUID agentId, @PathVariable UUID sessionId) {
        return response(agentService.forkSession(agentId, sessionId), HttpStatus.CREATED);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable UUID agentId, @PathVariable UUID sessionId) {
        return response(agentService.deleteSession(agentId, sessionId), HttpStatus.NO_CONTENT);
    }

}
