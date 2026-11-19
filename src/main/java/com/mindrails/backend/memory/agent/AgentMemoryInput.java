package com.mindrails.backend.memory.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Shared validated write input for management APIs and scoped model tools. */
public record AgentMemoryInput(
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,127}") String key,
        @NotNull AgentMemoryEntry.Type type,
        @NotBlank String content,
        @Size(max = 20) List<@NotBlank @Size(max = 64) String> tags,
        boolean pinned, @Min(0) @Max(100) int priority,
        UUID sourceSessionId, @Min(0) Long version) {}
