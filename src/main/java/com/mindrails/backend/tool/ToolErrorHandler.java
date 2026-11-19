package com.mindrails.backend.tool;

import com.mindrails.backend.agent.model.LlmClient;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.concurrent.CancellationException;

/** Shared tool error policy, independent of optional tool modules. */
@Component("toolErrorHandler")
@Slf4j
public class ToolErrorHandler {
    public ToolArgumentsErrorHandler argumentsErrorHandler() {
        return (error, context) -> {
            LlmClient.checkCancelled(context.invocationContext());
            if (error instanceof CancellationException cancelled) throw cancelled;
            return ToolErrorHandlerResult.text("""
                    {"success":false,"status":"ERROR","code":"INVALID_ARGUMENT","message":"Invalid tool arguments; use the declared parameter schema"}
                    """);
        };
    }

    public ToolExecutionErrorHandler executionErrorHandler() {
        return (error, context) -> {
            LlmClient.checkCancelled(context.invocationContext());
            if (error instanceof CancellationException cancelled) throw cancelled;
            if (error instanceof ToolFailure failure) return ToolErrorHandlerResult.text(failure.getMessage());
            log.warn("Tool execution failed: toolName={}", context.toolExecutionRequest().name(), error);
            return ToolErrorHandlerResult.text("""
                    {"success":false,"status":"ERROR","code":"INTERNAL_ERROR","message":"Tool execution failed; the operation was not confirmed successful"}
                    """);
        };
    }
}
