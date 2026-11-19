package com.mindrails.backend.mcp.service;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.mcp.model.McpServerDetails;
import com.mindrails.backend.mcp.model.McpStatus;
import com.mindrails.backend.mcp.model.McpTool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** User management and AI invocation share the same configuration and lifecycle boundary. */
public interface McpService {
    record Page(List<McpServerDetails> items, int total, int page, int size, boolean hasNext) {}

    record Imported(List<McpServerDetails> items, List<String> warnings) {}

    record Tools(
            long configVersion,
            Instant discoveredAt,
            boolean stale,
            List<Map<String, Object>> items) {}

    record AvailableTools(List<McpTool> tools, String unavailableSummary) {}

    ServiceResult<Page> list(int page, int size);

    ServiceResult<McpServerDetails> get(UUID id);

    ServiceResult<Imported> importConfig(
            Object config, String mode, Map<String, Long> expectedVersions);

    ServiceResult<McpServerDetails> update(UUID id, long version, Object config, Boolean enabled);

    ServiceResult<Void> delete(UUID id, long version);

    ServiceResult<McpStatus> test(UUID id);

    ServiceResult<McpStatus> refreshTools(UUID id);

    ServiceResult<Tools> tools(UUID id);

    AvailableTools availableTools(InvocationContext context);

    ToolExecutionResult executeTool(
            McpTool tool, ToolExecutionRequest request, InvocationContext context);
}
