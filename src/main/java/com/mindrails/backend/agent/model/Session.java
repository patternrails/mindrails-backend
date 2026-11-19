package com.mindrails.backend.agent.model;

import com.mindrails.backend.provider.Provider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Configuration and identity of a session belonging to an Agent. */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Session implements Cloneable, com.mindrails.backend.identity.UserOwnedResource {

    private UUID id;
    private UUID ownerId;
    private UUID parentAgentId;
    private String name;
    private Provider provider;
    private String systemMessage;


    /** Stored for compatibility with older Session responses. */
    @Builder.Default private List<UUID> skillIds = new ArrayList<>();

    /** Stable, server-managed identity of this session's chat memory. */
    private UUID memoryId;

    private Instant createdAt;
    private Instant updatedAt;

    @com.fasterxml.jackson.annotation.JsonIgnore private long version;

    /** Shallow copy: nested lists and Provider remain shared references. */
    @Override
    public Session clone() {
        return toBuilder().build();
    }
}
