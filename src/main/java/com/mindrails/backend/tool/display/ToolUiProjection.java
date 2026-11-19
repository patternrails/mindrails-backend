package com.mindrails.backend.tool.display;

import com.mindrails.backend.agent.model.TaskExecution;
import com.mindrails.backend.history.model.HistoryRecord;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Projects persisted records at the HTTP/WebSocket boundary; archive and model input stay intact. */
public final class ToolUiProjection {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ToolUiProjection() {}

    public static HistoryRecord history(HistoryRecord record) {
        return record.toBuilder().messages(ToolDisplay.messages(record.messages()))
                .metadata(metadata(record.metadata())).build();
    }

    public static String metadata(String value) {
        var metadata = ToolDisplay.object(value);
        if (metadata.get("partial") instanceof Map<?,?> partial) {
            var copy = ToolDisplay.object(partial);
            copy.remove("toolCalls");
            metadata.put("partial", copy);
        }
        return JSON.writeValueAsString(metadata);
    }

    public static TaskExecution task(TaskExecution execution) {
        return execution.toBuilder().records(execution.records().stream()
                .map(entry -> new TaskExecution.Entry(entry.sequence(), entry.timestamp(), entry.type(), taskEvent(entry.type(), entry.data())))
                .toList()).toolExecutions(execution.toolExecutions().stream().map(ToolUiProjection::call).toList()).build();
    }

    private static TaskExecution.ToolCall call(TaskExecution.ToolCall call) {
        return new TaskExecution.ToolCall(call.id(), call.name(), JSON.writeValueAsString(ToolDisplay.arguments(call.name(), call.arguments())),
                ToolDisplay.result(call.name(), call.result(), call.failed()), call.failed(), call.durationMs());
    }

    public static Object taskEvent(String type, Object data) {
        if (data instanceof TaskExecution.ToolCall call) {
            var fields = new LinkedHashMap<String,Object>();
            fields.put("id", call.id()); fields.put("name", call.name()); fields.put("result", call.result());
            fields.put("failed", call.failed()); fields.put("durationMs", call.durationMs());
            return taskEvent(type, fields);
        }
        if (!(data instanceof Map<?,?>)) {
            // Persisted JSON entries deserialize records as maps.
            return data;
        }
        var copy = ToolDisplay.object(data);
        if ("model.requested".equals(type)) copy.remove("messages");
        if ("model.response.completed".equals(type) && copy.get("message") instanceof String message) copy.put("message", ToolDisplay.messages(message));
        if (type.startsWith("tool.")) {
            String name = Objects.toString(copy.getOrDefault("toolName", copy.get("name")), "");
            if ("tool.started".equals(type)) copy.put("arguments", null);
            else if (copy.containsKey("arguments")) copy.remove("arguments");
            if (copy.containsKey("result")) copy.put("result", ToolDisplay.result(name, (String) copy.get("result"), "tool.failed".equals(type)));
            if ("tool.arguments.delta".equals(type)) copy.put("text", "");
        }
        return copy;
    }
}
