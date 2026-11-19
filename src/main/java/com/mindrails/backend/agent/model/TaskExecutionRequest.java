package com.mindrails.backend.agent.model;

import java.util.Map;
import java.util.UUID;

/** Parameters are data, not substitutions in instructions. Trigger provenance is server supplied. */
public record TaskExecutionRequest(UUID requestId, Map<String, Object> parameters) {
    public TaskExecutionRequest { parameters = parameters == null ? Map.of() : parameters; }
}
