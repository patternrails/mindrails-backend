package com.mindrails.backend.agent.tool;

import com.mindrails.backend.agent.service.SessionCommunicationService;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.tool.IdentifiedTool;
import com.mindrails.backend.tool.ToolFailure;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public final class SessionCommunicationTool implements IdentifiedTool {
    public static final String NAME = "session_communicate";
    private static final String CALL_ID = "mindrails.communication.toolCallId";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final SessionCommunicationService communication;

    public SessionCommunicationTool(SessionCommunicationService communication) { this.communication = communication; }
    @Override public UUID id() { return UUID.fromString("34027cd3-a32f-4be2-9c13-f43664376181"); }

    @Tool(name = NAME, value = "Send a text message to another ordinary Session belonging to the same user. "
            + "Use session_list to discover a Session by the user's description; ask for clarification when several candidates match. "
            + "Use only an exact Session UUID returned by session_list, supplied by the user or trusted message context; never guess one. "
            + "Returns an enqueue receipt immediately, not an answer or completion guarantee. The target works without a browser. "
            + "Say in the message whether a reply is needed. To reply, send to the incoming source Session and set inReplyTo to its message ID. "
            + "Your normal final answer stays in your Session and is not sent back automatically. "
            + "Do not acknowledge acknowledgements or automatically retry accepted work. Communication has an 8-hop limit.")
    public SessionCommunicationService.Receipt communicate(
            @P(name = "targetSessionId", value = "Exact UUID of the destination Session.") UUID targetSessionId,
            @P(name = "message", value = "Task or information, including selected context and whether a reply is required; 1..16000 characters.") String message,
            @P(name = "inReplyTo", value = "Optional ID of this invocation's incoming message when replying to its source.", required = false) UUID inReplyTo,
            InvocationContext context) {
        var result = communication.communicate(targetSessionId, message, inReplyTo,
                context == null || context.invocationParameters() == null ? null : context.invocationParameters().get(CALL_ID), context);
        return switch (result) {
            case ServiceResult.Success<SessionCommunicationService.Receipt> success -> success.value();
            case ServiceResult.Failure<SessionCommunicationService.Receipt> failure -> throw new ToolFailure(
                    JSON.writeValueAsString(Map.of("status", "error", "code", failure.code(), "message", failure.message())));
        };
    }

    @Override public List<AiServiceTool> buildTools(MetricsRecorder recorder) {
        return IdentifiedTool.super.buildTools(recorder).stream().map(tool -> {
            var delegate = tool.toolExecutor();
            return tool.toBuilder().toolExecutor(new ToolExecutor() {
                @Override public String execute(ToolExecutionRequest request, Object memoryId) {
                    throw new IllegalArgumentException("Server Session invocation context is required");
                }
                @Override public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext context) {
                    if (context == null || context.invocationParameters() == null || request.id() == null) {
                        throw new IllegalArgumentException("Server Session invocation and tool call ID are required");
                    }
                    // Give this call its own parameters while sharing the Run-owned deduplication cache.
                    var parameters = InvocationParameters.from(context.invocationParameters().asMap());
                    parameters.put(CALL_ID, request.id());
                    return delegate.executeWithContext(request, context.toBuilder().invocationParameters(parameters).build());
                }
            }).build();
        }).toList();
    }
}
