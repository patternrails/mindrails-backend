package com.mindrails.backend.mcp.api.web;

import static com.mindrails.backend.common.ServiceResponses.response;

import com.mindrails.backend.mcp.model.McpServerDetails;
import com.mindrails.backend.mcp.model.McpStatus;
import com.mindrails.backend.mcp.service.McpService;

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

import java.util.UUID;

@RestController
@RequestMapping("/api/mcp/servers")
public class McpController {
    private final McpService mcpService;

    public McpController(McpService mcpService) {
        this.mcpService = mcpService;
    }

    @GetMapping
    public ResponseEntity<McpService.Page> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(mcpService.list(page, size), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<McpServerDetails> detail(@PathVariable UUID id) {
        return response(mcpService.get(id), HttpStatus.OK);
    }

    @PostMapping("/import")
    public ResponseEntity<McpService.Imported> importConfig(
            @Valid @RequestBody ImportMcpRequest request) {
        return response(
                mcpService.importConfig(
                        request.getConfig(), request.getMode(), request.getExpectedVersions()),
                HttpStatus.OK);
    }

    @PutMapping("/{id}")
    public ResponseEntity<McpServerDetails> update(
            @PathVariable UUID id, @Valid @RequestBody UpdateMcpRequest request) {
        return response(
                mcpService.update(
                        id, request.getVersion(), request.getConfig(), request.getEnabled()),
                HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version) {
        return response(mcpService.delete(id, version), HttpStatus.NO_CONTENT);
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<McpStatus> test(@PathVariable UUID id) {
        return response(mcpService.test(id), HttpStatus.OK);
    }

    @PostMapping("/{id}/refresh-tools")
    public ResponseEntity<McpStatus> refresh(@PathVariable UUID id) {
        return response(mcpService.refreshTools(id), HttpStatus.OK);
    }

    @GetMapping("/{id}/tools")
    public ResponseEntity<McpService.Tools> tools(@PathVariable UUID id) {
        return response(mcpService.tools(id), HttpStatus.OK);
    }
}
