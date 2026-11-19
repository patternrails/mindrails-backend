package com.mindrails.backend.agent.model;

import java.util.List;
public record ChatReply(List<String> lines, List<Source> sources, Usage usage, List<String> executedTools, List<com.mindrails.backend.tool.display.ToolActivity> toolActivity) {
    public ChatReply {
        lines = List.copyOf(lines);
        sources = List.copyOf(sources);
        executedTools = List.copyOf(executedTools);
        toolActivity = List.copyOf(toolActivity);
    }
    public ChatReply(List<String> lines, List<Source> sources, Usage usage, List<String> executedTools) {
        this(lines, sources, usage, executedTools, List.of());
    }
    public ChatReply(List<String> lines, List<Source> sources) {
        this(lines, sources, null, List.of());
    }
    public record Usage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {}
    public record Source(String sourceId,String title,String excerpt,String url) {}
}
