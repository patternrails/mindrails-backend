package com.mindrails.backend.agent.api.web;

import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/** Editable fields of an existing session. */
@Getter
@Setter
public class UpdateSessionRequest {

    @NotNull private UUID providerId;

    @jakarta.validation.constraints.Size(max = 160)
    private String name;

    private String systemMessage;
    // Accepted from older clients and ignored.
    private List<UUID> skillIds;
    private UUID memoryId;

    @com.fasterxml.jackson.annotation.JsonSetter("knowledgeBaseIds")
    public void setKnowledgeBaseIds(List<UUID> ignored) {
        throw new IllegalArgumentException("Configure knowledge on the Agent, not the Session");
    }

    @com.fasterxml.jackson.annotation.JsonSetter("knowledgeBaseId")
    public void setKnowledgeBaseId(UUID ignored) {
        throw new IllegalArgumentException(
                "Session cannot configure knowledgeBaseId; configure the Agent instead");
    }
}
