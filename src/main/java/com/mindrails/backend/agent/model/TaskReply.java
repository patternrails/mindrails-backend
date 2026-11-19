package com.mindrails.backend.agent.model;

import java.util.List;
import java.util.UUID;

public record TaskReply(UUID executionId, List<String> lines, List<ChatReply.Source> sources,
        ChatReply.Usage usage, List<String> executedTools) {
    public TaskReply {
        lines = lines == null ? List.of() : List.copyOf(lines);
        sources = sources == null ? List.of() : List.copyOf(sources);
        executedTools = executedTools == null ? List.of() : List.copyOf(executedTools);
    }
}
