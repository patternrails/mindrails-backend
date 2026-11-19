package com.mindrails.backend.history.api.web;

import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.tool.display.ToolDisplay;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Display fields only. The stored messages and metadata remain available through explicit reads. */
public record HistoryPreview(UUID id, UUID agentId, UUID sessionId, UUID providerId,
        String status, Instant startedAt, Instant finishedAt, List<Message> messages, Partial partial) {
    public static final int TEXT_LIMIT = 6000;
    public static final int TOOL_LIMIT = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Tool(String name, String arguments) {}
    public record Message(int index, String type, String text, String thinking, List<Tool> toolCalls, boolean truncated) {}
    public record Partial(String answer, String thinking, boolean truncated) {}

    public static HistoryPreview from(HistoryRecord record) {
        var messages = new ArrayList<Message>();
        var nativeMessages = JSON.readTree(record.messages());
        for (int index = 0; index < nativeMessages.size(); index++) {
            var message = nativeMessages.get(index);
            var budget = new Budget();
            String text = body(message, budget);
            if ("TOOL_EXECUTION_RESULT".equals(message.path("type").asString())) {
                String preview = ToolDisplay.result(message.path("toolName").asString(""), text,
                        message.path("isError").asBoolean(false));
                budget.truncated |= preview == null ? !text.isEmpty() : !preview.equals(text);
                text = preview == null ? "" : preview;
            }
            String thinking = budget.take(message.path("thinking").asString(""));
            var calls = new ArrayList<Tool>();
            var requests = message.path("toolExecutionRequests");
            if (requests.isArray()) {
                for (var call : requests) {
                    if (calls.size() == TOOL_LIMIT) { budget.truncated = true; break; }
                    String name = budget.take(call.path("name").asString(""));
                    var arguments = call.path("arguments");
                    String value = arguments.isString() ? arguments.asString() : arguments.isMissingNode() ? "null" : arguments.toString();
                    String shown = JSON.writeValueAsString(ToolDisplay.arguments(call.path("name").asString(""), value));
                    budget.truncated |= value.codePointCount(0, value.length()) > ToolDisplay.LIMIT;
                    calls.add(new Tool(name, budget.take(shown)));
                }
            }
            messages.add(new Message(index, message.path("type").asString("UNKNOWN"), text, thinking, calls, budget.truncated));
        }
        var partial = JSON.readTree(record.metadata()).path("partial");
        var budget = new Budget();
        var partialView = new Partial(budget.take(partial.path("answer").asString("")),
                budget.take(partial.path("thinking").asString("")), budget.truncated);
        return new HistoryPreview(record.id(), record.agentId(), record.sessionId(), record.providerId(),
                record.status().name(), record.startedAt(), record.finishedAt(), messages, partialView);
    }

    private static String body(JsonNode message, Budget budget) {
        if (message.path("text").isString() && !message.path("text").asString().isEmpty()) {
            return budget.take(message.path("text").asString());
        }
        var body = new StringBuilder();
        var contents = message.path("contents");
        if (contents.isArray()) for (var content : contents) {
            if (!"TEXT".equals(content.path("type").asString()) || !content.path("text").isString()) continue;
            if (!body.isEmpty()) body.append(budget.take("\n"));
            body.append(budget.take(content.path("text").asString()));
            if (budget.truncated) break;
        }
        return body.toString();
    }

    private static final class Budget {
        int remaining = TEXT_LIMIT;
        boolean truncated;
        String take(String value) {
            int length = value.codePointCount(0, value.length());
            if (length <= remaining) { remaining -= length; return value; }
            truncated = true;
            String clipped = value.substring(0, value.offsetByCodePoints(0, remaining));
            remaining = 0;
            return clipped + "…";
        }
    }
}
