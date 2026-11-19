package com.mindrails.backend.agent.api.websocket;

import static com.mindrails.backend.agent.api.websocket.protocol.ServerMessageType.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.api.websocket.protocol.RunState;
import com.mindrails.backend.agent.api.websocket.protocol.ServerMessageType;
import com.mindrails.backend.agent.api.websocket.protocol.StreamPayloads;
import com.mindrails.backend.agent.api.websocket.protocol.ToolState;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import com.mindrails.backend.tool.display.ToolDisplay;
import java.util.Map;
import java.util.UUID;

/**
 * Strict client decoding and streaming event-to-payload mapping; no LC4J objects cross this
 * boundary.
 */
final class StreamProtocol {
    private static final int DISPLAY_BYTES = 16 * 1024;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private StreamProtocol() {}

    static ClientCommand decode(String json) {
        JsonNode root =
                MAPPER.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .readTree(json);
        if (root == null || !root.isObject())
            throw new IllegalArgumentException("Expected a JSON object");
        fields(root, "version", "type", "requestId", "data");
        if (!root.path("version").isIntegralNumber()
                || !root.path("version").canConvertToInt()
                || root.path("version").intValue() != 1)
            throw new IllegalArgumentException("Unsupported protocol version");
        UUID requestId = uuid(root.path("requestId"));
        JsonNode data = root.path("data");
        if (!data.isObject()) throw new IllegalArgumentException("data must be an object");
        return switch (root.path("type").asString("")) {
            case "chat" -> {
                fields(data, "message", "assetVersionIds");
                if(data.has("message")&&!data.path("message").isString()&&!data.path("message").isNull())throw new IllegalArgumentException("message must be text");
                var attachments = new java.util.ArrayList<UUID>();
                if (data.has("assetVersionIds")) {
                    if (!data.path("assetVersionIds").isArray()
                            || data.path("assetVersionIds").size() > 10)
                        throw new IllegalArgumentException("Invalid attachments");
                    for (var id : data.path("assetVersionIds")) attachments.add(uuid(id));
                }
                if ((!data.path("message").isString() || data.path("message").asString().isBlank())
                        && attachments.isEmpty())
                    throw new IllegalArgumentException("Non-blank message is required");
                yield new ClientCommand.Chat(
                        requestId,
                        data.path("message").asString("").isBlank()
                                ? "请分析这些附件，并引用对应来源。"
                                : data.path("message").asString(),
                        attachments);
            }
            case "cancel" -> {
                fields(data);
                yield new ClientCommand.Cancel(requestId);
            }
            default -> throw new IllegalArgumentException("Unsupported command type");
        };
    }

    static UUID requestId(String json) {
        try {
            return uuid(MAPPER.readTree(json).path("requestId"));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void fields(JsonNode node, String... allowed) {
        var names = java.util.Set.of(allowed);
        for (String name : node.propertyNames()) {
            if (!names.contains(name)) throw new IllegalArgumentException("Unknown field: " + name);
        }
    }

    static UUID uuid(JsonNode node) {
        if (!node.isString()) throw new IllegalArgumentException("UUID is required");
        return uuid(node.asString());
    }

    static UUID uuid(String text) {
        UUID id = UUID.fromString(text);
        if (!id.toString().equalsIgnoreCase(text))
            throw new IllegalArgumentException("Invalid UUID");
        return id;
    }

    record Event(ServerMessageType type, UUID requestId, Object data, RunState terminalState) {}

    // TODO, refactor the event map in future
    static Event map(ChatStreamEvent event) {
        return switch (event) {
            case ChatStreamEvent.Enqueued e -> new Event(MESSAGE_ENQUEUED, e.requestId(), e, null);
            case ChatStreamEvent.Started e ->
                    new Event(
                            RUN_STARTED,
                            event.requestId(),
                            new StreamPayloads.Started(RunState.RUNNING, e.input()),
                            null);
            case ChatStreamEvent.ThinkingDelta e ->
                    new Event(
                            THINKING_DELTA,
                            event.requestId(),
                            new StreamPayloads.TextDelta(e.round(), e.messageId(), e.text()),
                            null);
            case ChatStreamEvent.AnswerDelta e ->
                    new Event(
                            ANSWER_DELTA,
                            event.requestId(),
                            new StreamPayloads.TextDelta(e.round(), e.messageId(), e.text()),
                            null);
            case ChatStreamEvent.Retrieved e ->
                    new Event(
                            RETRIEVAL_COMPLETED,
                            event.requestId(),
                            new StreamPayloads.Retrieved(
                                    e.sources().stream()
                                            .map(
                                                    s ->
                                                            new StreamPayloads.Source(
                                                                    s.sourceId(),
                                                                    s.title(),
                                                                    truncate(s.excerpt()),
                                                                    s.url(),
                                                                    oversized(s.excerpt())))
                                            .toList()),
                            null);
            case ChatStreamEvent.ToolArgumentsDelta e ->
                    new Event(
                            TOOL_ARGUMENTS_DELTA,
                            event.requestId(),
                            new StreamPayloads.ToolArgumentsDelta(
                                    e.round(),
                                    e.toolCallId(),
                                    e.toolName(),
                                    "",
                                    ToolState.PREPARING),
                            null);
            case ChatStreamEvent.ModelResponseCompleted e ->
                    new Event(
                            MODEL_RESPONSE_COMPLETED,
                            event.requestId(),
                            new StreamPayloads.ModelResponseCompleted(
                                    e.round(),
                                    e.messageId(),
                                    e.finishReason(),
                                    e.toolCalls().stream()
                                            .map(
                                                    t ->
                                                            new StreamPayloads.ToolCall(
                                                                    t.toolCallId(),
                                                                    t.toolName(),
                                                                    ToolDisplay.arguments(t.toolName(), t.arguments()),
                                                                    false,
                                                                    ToolState.PENDING,
                                                                    t.source()))
                                            .toList()),
                            null);
            case ChatStreamEvent.ToolStarted e ->
                    new Event(
                            TOOL_STARTED,
                            event.requestId(),
                            new StreamPayloads.ToolStarted(
                                    e.round(),
                                    e.toolCallId(),
                                    e.toolName(),
                                    null,
                                    false,
                                    ToolState.RUNNING,
                                    e.source()),
                            null);
            case ChatStreamEvent.ToolCompleted e ->
                    new Event(
                            TOOL_COMPLETED,
                            event.requestId(),
                            new StreamPayloads.ToolCompleted(
                                    e.round(),
                                    e.toolCallId(),
                                    e.toolName(),
                                    ToolDisplay.result(e.toolName(), e.result(), false),
                                    e.durationMs(),
                                    false,
                                    ToolState.COMPLETED),
                            null);
            case ChatStreamEvent.ToolFailed e ->
                    new Event(
                            TOOL_FAILED,
                            event.requestId(),
                            new StreamPayloads.ToolFailed(
                                    e.round(),
                                    e.toolCallId(),
                                    e.toolName(),
                                    e.error(),
                                    ToolState.FAILED),
                            null);
            case ChatStreamEvent.Completed e ->
                    new Event(
                            RUN_COMPLETED,
                            event.requestId(),
                            new StreamPayloads.Completed(
                                    RunState.COMPLETED,
                                    e.finalMessageId(),
                                    e.answer(),
                                    e.finishReason(),
                                    e.usage()),
                            RunState.COMPLETED);
            case ChatStreamEvent.Cancelled e ->
                    new Event(
                            RUN_CANCELLED,
                            event.requestId(),
                            new StreamPayloads.Cancelled(RunState.CANCELLED, e.reason()),
                            RunState.CANCELLED);
            case ChatStreamEvent.Failed e ->
                    new Event(
                            RUN_FAILED,
                            event.requestId(),
                            new StreamPayloads.Failed(RunState.FAILED, e.error()),
                            RunState.FAILED);
        };
    }

    private static boolean oversized(String text) {
        return text != null && text.getBytes(StandardCharsets.UTF_8).length > DISPLAY_BYTES;
    }

    private static String truncate(String text) {
        if (!oversized(text)) return text;
        int end = 0, bytes = 0;
        while (end < text.length()) {
            int cp = text.codePointAt(end);
            int size = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes + size > DISPLAY_BYTES) break;
            bytes += size;
            end += Character.charCount(cp);
        }
        return text.substring(0, end);
    }
}
