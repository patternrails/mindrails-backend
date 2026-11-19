package com.mindrails.backend.agent.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An agent's identity, long-term direction, and available resources.
 * Each Session represents a session owned by this agent.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Agent implements Cloneable, com.mindrails.backend.identity.UserOwnedResource {

    private UUID id;
    private UUID ownerId;

    /** Server-managed special role: builder or runner:<appId>. */
    private String name;
    private String description;

    /** Broad, long-term purpose of this agent. */
    private String goal;

    /** General system message shared across this agent's sessions. */
    private String systemMessage;

    @Builder.Default
    private List<Session> sessions = new ArrayList<>();

    @Builder.Default
    private List<Task> tasks = new ArrayList<>();

    @Builder.Default
    private List<UUID> skillIds = new ArrayList<>();
    @Builder.Default
    private List<UUID> connectionIds = new ArrayList<>();
    private UUID memoryId;
    @Builder.Default
    private List<UUID> knowledgeBaseIds = List.of();

    private Instant createdAt;
    private Instant updatedAt;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private long version;

    /** Shallow copy: nested lists and Session objects remain shared references. */
    @Override
    public Agent clone() {
        return toBuilder().build();
    }
}
