package com.mindrails.backend.agent.api.web;

import static com.mindrails.backend.common.ServiceResponses.response;

import com.mindrails.backend.memory.agent.AgentMemoryEntry;
import com.mindrails.backend.memory.agent.AgentMemoryInput;
import com.mindrails.backend.memory.agent.AgentMemoryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent/{agentId}/memories")
public class AgentMemoryController {
    private final AgentMemoryService memory;
    public AgentMemoryController(AgentMemoryService memory) { this.memory = memory; }

    @PostMapping
    public ResponseEntity<AgentMemoryEntry> create(@PathVariable UUID agentId, @Valid @RequestBody AgentMemoryInput input) {
        return response(memory.create(agentId, input), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<AgentMemoryEntry>> list(@PathVariable UUID agentId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return response(memory.list(agentId, page, size), HttpStatus.OK);
    }

    @GetMapping("/search")
    public ResponseEntity<List<AgentMemoryEntry>> search(@PathVariable UUID agentId, @RequestParam String query) {
        return response(memory.search(agentId, query), HttpStatus.OK);
    }

    @GetMapping("/{entryId}")
    public ResponseEntity<AgentMemoryEntry> get(@PathVariable UUID agentId, @PathVariable UUID entryId) {
        return response(memory.get(agentId, entryId), HttpStatus.OK);
    }

    @PutMapping("/{entryId}")
    public ResponseEntity<AgentMemoryEntry> update(@PathVariable UUID agentId, @PathVariable UUID entryId,
            @Valid @RequestBody AgentMemoryInput input) {
        return response(memory.update(agentId, entryId, input), HttpStatus.OK);
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> delete(@PathVariable UUID agentId, @PathVariable UUID entryId, @RequestParam long version) {
        return response(memory.delete(agentId, entryId, version), HttpStatus.NO_CONTENT);
    }
}
