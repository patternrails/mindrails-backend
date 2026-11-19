package com.mindrails.backend.mcp.api.toolspec;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.mcp.model.McpTool;
import com.mindrails.backend.mcp.service.McpService;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Objects;

/** AI-facing descriptions and executors; all MCP operations go through McpService. */
@Component
public final class McpTools implements ToolProvider {
    private final McpService mcpService;

    public McpTools(McpService mcpService) {
        this.mcpService = mcpService;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        LlmClient.checkCancelled(request.invocationContext());
        var available = mcpService.availableTools(request.invocationContext());
        var tools = new ArrayList<AiServiceTool>();
        for (var tool : available.tools()) {
            var specification =
                    tool.specification().toBuilder()
                            .name(tool.modelName())
                            .description(
                                    "MCP server "
                                            + tool.serverName()
                                            + "; tool "
                                            + tool.specification().name()
                                            + ". "
                                            + Objects.toString(
                                                    tool.specification().description(), ""))
                            .build();
            tools.add(
                    AiServiceTool.builder()
                            .toolSpecification(specification)
                            .toolExecutor(new McpExecutor(tool))
                            .build());
        }
        if (!available.unavailableSummary().isEmpty()) {
            String summary =
                    "These registered MCP servers are currently unavailable: "
                            + available.unavailableSummary()
                            + ". Their business tools cannot be used this turn. Do not claim to"
                            + " have called them.";
            tools.add(
                    AiServiceTool.builder()
                            .toolSpecification(
                                    ToolSpecification.builder()
                                            .name("mcp_connection_status")
                                            .description(summary)
                                            .parameters(JsonObjectSchema.builder().build())
                                            .build())
                            .toolExecutor((call, memoryId) -> summary)
                            .build());
        }
        LlmClient.checkCancelled(request.invocationContext());
        return ToolProviderResult.builder().addAll(tools).build();
    }

    private final class McpExecutor implements ToolExecutor {
        private final McpTool tool;

        private McpExecutor(McpTool tool) {
            this.tool = tool;
        }

        @Override
        public String execute(ToolExecutionRequest call, Object memoryId) {
            return com.mindrails.backend.tool.ToolResultText.from(executeWithContext(
                            call, InvocationContext.builder().chatMemoryId(memoryId).build()).resultContents());
        }

        @Override
        public ToolExecutionResult executeWithContext(
                ToolExecutionRequest call, InvocationContext context) {
            var actual =
                    ToolExecutionRequest.builder()
                            .id(call.id())
                            .name(tool.specification().name())
                            .arguments(call.arguments())
                            .build();
            return mcpService.executeTool(tool, actual, context);
        }
    }
}
