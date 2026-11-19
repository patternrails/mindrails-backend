package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService.MessageToLLM;
import com.mindrails.backend.agent.service.internal.llm.ChatFailureMessages;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.tool.ToolResultText;
import com.mindrails.backend.tool.display.ToolDisplay;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import java.util.List;
import java.util.UUID;

/** Converts model callbacks into session events. */
final class DefaultLoopSessionStreamServiceHelper {
    private DefaultLoopSessionStreamServiceHelper() {}

    static ChatStreamEvent.Failed toChatEventFailed(MessageToLLM message, Throwable error) {
        return new ChatStreamEvent.Failed(message.requestId(), ChatFailureMessages.from(error));
    }

    static ChatStreamEvent.Completed toChatEventCompleted(
            MessageToLLM message, UUID messageId, ChatResponse response) {
        var usage = response.tokenUsage();
        return new ChatStreamEvent.Completed(message.requestId(), messageId, response.aiMessage().text(),
                response.finishReason() == null ? null : response.finishReason().name(),
                usage == null ? null : new ChatStreamEvent.Usage(tokenCount(usage.inputTokenCount()),
                        tokenCount(usage.outputTokenCount()), tokenCount(usage.totalTokenCount())));
    }

    static ChatStreamEvent toChatEventToolExecuted(
            MessageToLLM message, int round, ToolExecution execution) {
        var request = execution.request();
        if (execution.hasFailed()) {
            return new ChatStreamEvent.ToolFailed(message.requestId(), round, request.id(), request.name(),
                    new ChatStreamEvent.Error(ErrorCode.INTERNAL_ERROR, "Tool execution failed"));
        }
        var duration = execution.duration();
        return new ChatStreamEvent.ToolCompleted(message.requestId(), round, request.id(), request.name(),
                ToolResultText.from(execution.resultContents()), duration == null ? null : duration.toMillis());
    }

    static ChatStreamEvent.ToolStarted toChatEventToolStarted(
            MessageToLLM message, int round, BeforeToolExecution execution) {
        var request = execution.request();
        return new ChatStreamEvent.ToolStarted(message.requestId(), round, request.id(),
                request.name(), ToolDisplay.object(request.arguments()), toolSource(message, request.name()));
    }

    static ChatStreamEvent.ModelResponseCompleted toChatEventModelResponseCompleted(
            MessageToLLM message, int round, UUID messageId, ChatResponse response) {
        var calls = response.aiMessage().toolExecutionRequests().stream()
                .map(tool -> new ChatStreamEvent.ToolCall(tool.id(), tool.name(), ToolDisplay.object(tool.arguments()),
                        toolSource(message, tool.name())))
                .toList();
        return new ChatStreamEvent.ModelResponseCompleted(message.requestId(), round, messageId,
                response.finishReason() == null ? null : response.finishReason().name(), calls);
    }

    static ChatStreamEvent.ToolArgumentsDelta toChatEventToolArgumentsDelta(
            MessageToLLM message, int round, PartialToolCall tool) {
        return new ChatStreamEvent.ToolArgumentsDelta(
                message.requestId(), round, tool.id(), tool.name(), tool.partialArguments());
    }

    static ChatStreamEvent.ThinkingDelta toChatEventThinkingDelta(
            MessageToLLM message, int round, UUID messageId, PartialThinking thinking) {
        return new ChatStreamEvent.ThinkingDelta(message.requestId(), round, messageId, thinking.text());
    }

    static ChatStreamEvent.AnswerDelta toChatEventAnswerDelta(
            MessageToLLM message, int round, UUID messageId, PartialResponse response) {
        return new ChatStreamEvent.AnswerDelta(message.requestId(), round, messageId, response.text());
    }

    static ChatStreamEvent.Retrieved toChatStreamEventRetrieved(MessageToLLM message, List<Content> contents) {
        var sources = contents.stream()
                .map(content -> {
                    var segment = content.textSegment();
                    return new ChatStreamEvent
                            .Source(segment.metadata().getString("id"),
                            segment.metadata().getString("key"),
                            segment.text(),
                            segment.metadata().getString("url"));
                }).toList();
        return new ChatStreamEvent.Retrieved(message.requestId(), sources);
    }

    private static java.util.Map<String, Object> toolSource(MessageToLLM message, String toolName) {
        java.util.Map<String, java.util.Map<String, Object>> sources = message.parameters().get(LlmClient.TOOL_SOURCES);
        return sources == null ? null : sources.get(toolName);
    }

    private static Long tokenCount(Integer count) {
        return count == null ? null : count.longValue();
    }
}
