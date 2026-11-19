package com.mindrails.backend.agent.api.websocket.protocol;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent.Error;
import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent.Usage;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.ActiveRun;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire payloads. Run and tool state are explicit; connection state is represented by ready/close. */
public final class StreamPayloads {
    private StreamPayloads() {}
    public record Ready(ActiveRun activeRun) {}
    public record State(RunState state) {}
    public record Started(RunState state, ChatStreamEvent.Enqueued input) {}
    public record CommandError(ErrorCode code, String message) {}
    public record Completed(RunState state, UUID finalMessageId, String answer, String finishReason, Usage usage) {}
    public record Cancelled(RunState state, CancelReason reason) {}
    public record Failed(RunState state, Error error) {}
    public record TextDelta(int round, UUID messageId, String text) {}
    public record Source(String sourceId, String title, String excerpt, String url, boolean truncated) {}
    public record Retrieved(List<Source> sources) {}
    public record ToolArgumentsDelta(int round, String toolCallId, String toolName, String text, ToolState state) {}
    public record ToolCall(String toolCallId, String toolName, Map<String, Object> arguments,
            boolean argumentsOmitted, ToolState state, Map<String,Object> source) {}
    public record ModelResponseCompleted(int round, UUID messageId, String finishReason, List<ToolCall> toolCalls) {}
    public record ToolStarted(int round, String toolCallId, String toolName, Map<String, Object> arguments,
            boolean argumentsOmitted, ToolState state, Map<String,Object> source) {}
    public record ToolCompleted(int round, String toolCallId, String toolName, String result,
            Long durationMs, boolean truncated, ToolState state) {}
    public record ToolFailed(int round, String toolCallId, String toolName, Error error, ToolState state) {}
}
