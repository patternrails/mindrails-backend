package com.mindrails.backend.tool.display;

import dev.langchain4j.service.Result;
import java.util.*;

/** Compact completed activity shared by streaming and synchronous chat presentation. */
public record ToolActivity(String id, int round, String name, Map<String,Object> arguments,
        String result, String error, Long durationMs, String status) {
    public static List<ToolActivity> from(Result<?> result) {
        var rounds = new HashMap<String,Integer>();
        int round = 0;
        if (result.intermediateResponses() != null) {
            for (var response : result.intermediateResponses()) {
                round++;
                for (var request : response.aiMessage().toolExecutionRequests()) rounds.put(request.id(), round);
            }
        }
        if (result.toolExecutions() == null) return List.of();
        var activity = new ArrayList<ToolActivity>();
        for (var execution : result.toolExecutions()) {
            var request = execution.request();
            String preview = ToolDisplay.result(request.name(), com.mindrails.backend.tool.ToolResultText.from(execution.resultContents()), execution.hasFailed());
            activity.add(new ToolActivity(request.id() == null ? "tool-" + activity.size() : request.id(),
                    rounds.getOrDefault(request.id(), Math.max(1, round)), request.name(),
                    ToolDisplay.arguments(request.name(), request.arguments()), execution.hasFailed() ? null : preview,
                    execution.hasFailed() ? Objects.requireNonNullElse(preview, "Tool execution failed") : null,
                    execution.duration() == null ? null : execution.duration().toMillis(), execution.hasFailed() ? "failed" : "completed"));
        }
        return List.copyOf(activity);
    }
}
