package com.mindrails.backend.tool;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.metrics.MetricsRecords;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A tool with an identity that Agent and Session can reference by UUID. */
public interface IdentifiedTool {

    UUID id();

    /** Builds FUNCTION executors with per-invocation metrics and cancellation checks. */
    default List<AiServiceTool> buildTools(MetricsRecorder metricsRecorder) {
        return ToolService.findTools(this).stream()
                .map(tool -> tool.toBuilder().toolExecutor(recordingExecutor(tool.toolExecutor(), tool.name(), metricsRecorder)).build())
                .toList();
    }

    private static ToolExecutor recordingExecutor(ToolExecutor delegate, String name, MetricsRecorder metricsRecorder) {
        return new ToolExecutor() {
            @Override
            public String execute(ToolExecutionRequest request, Object memoryId) {
                return delegate.execute(request, memoryId);
            }

            @Override
            public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
                var callId = UUID.randomUUID();
                var startedAt = Instant.now();
                metricsRecorder.record(MetricsRecords.capability(context, callId, startedAt, request.id(), name,
                        "FUNCTION", null, null, null));
                try {
                    LlmClient.checkCancelled(context);
                    var result = delegate.executeWithContext(request, context);
                    LlmClient.checkCancelled(context);
                    metricsRecorder.record(MetricsRecords.capability(context, callId, startedAt, request.id(), name,
                            "FUNCTION", null, null, result.isError()));
                    return result;
                } catch (RuntimeException | Error failure) {
                    metricsRecorder.record(MetricsRecords.capability(context, callId, startedAt, request.id(), name,
                            "FUNCTION", null, null, true));
                    throw failure;
                }
            }
        };
    }
}
