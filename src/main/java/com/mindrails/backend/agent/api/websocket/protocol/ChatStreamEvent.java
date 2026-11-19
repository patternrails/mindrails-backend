package com.mindrails.backend.agent.api.websocket.protocol;

import com.mindrails.backend.common.ServiceResult.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** WebSocket streaming events. Text, errors and tool data must already be safe for user display. */
public sealed interface ChatStreamEvent {
    UUID requestId();

    record Enqueued(UUID requestId, String message, java.time.Instant submittedAt,
            com.mindrails.backend.agent.model.SessionMessageSource source) implements ChatStreamEvent {}
    record Started(UUID requestId, Enqueued input) implements ChatStreamEvent {
        public Started(UUID requestId) { this(requestId, null); }
    }
    record ThinkingDelta(UUID requestId, int round, UUID messageId, String text) implements ChatStreamEvent {}
    record AnswerDelta(UUID requestId, int round, UUID messageId, String text) implements ChatStreamEvent {}
    record Retrieved(UUID requestId, List<Source> sources) implements ChatStreamEvent {
        public Retrieved { sources = List.copyOf(sources); }
    }
    record Source(String sourceId, String title, String excerpt, String url) {}
    record ToolArgumentsDelta(UUID requestId, int round, String toolCallId, String toolName, String text)
            implements ChatStreamEvent {}
    record ToolCall(String toolCallId, String toolName, Map<String, Object> arguments, Map<String,Object> source) {
        public ToolCall(String toolCallId,String toolName,Map<String,Object> arguments) {this(toolCallId,toolName,arguments,null);}
    }
    record ModelResponseCompleted(UUID requestId, int round, UUID messageId, String finishReason, List<ToolCall> toolCalls)
            implements ChatStreamEvent {
        public ModelResponseCompleted { toolCalls = List.copyOf(toolCalls); }
    }
    record ToolStarted(UUID requestId, int round, String toolCallId, String toolName, Map<String, Object> arguments,Map<String,Object> source)
            implements ChatStreamEvent {
        public ToolStarted(UUID requestId,int round,String toolCallId,String toolName,Map<String,Object> arguments) {this(requestId,round,toolCallId,toolName,arguments,null);}
    }
    record ToolCompleted(UUID requestId, int round, String toolCallId, String toolName, String result, Long durationMs)
            implements ChatStreamEvent {}
    record ToolFailed(UUID requestId, int round, String toolCallId, String toolName, Error error) implements ChatStreamEvent {}
    record Usage(Long inputTokens, Long outputTokens, Long totalTokens) {}
    record Completed(UUID requestId, UUID finalMessageId, String answer, String finishReason, Usage usage)
            implements ChatStreamEvent {}
    record Cancelled(UUID requestId, CancelReason reason) implements ChatStreamEvent {}
    record Failed(UUID requestId, Error error) implements ChatStreamEvent {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record Error(ErrorCode code, String message, String reason, String detail) {
        public Error(ErrorCode code, String message) {
            this(code, message, null, null);
        }
    }
}
