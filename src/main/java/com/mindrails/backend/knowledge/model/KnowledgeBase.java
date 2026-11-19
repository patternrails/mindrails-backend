package com.mindrails.backend.knowledge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase implements VersionedKnowledgeEntity, com.mindrails.backend.identity.UserOwnedResource {
    private UUID id;
    private UUID ownerId;
    private String name;
    private String description;
    private KnowledgeBaseState state;
    private KnowledgeIndexProfile profile;
    private KnowledgeIndexProfile pendingProfile;
    private Instant createdAt;
    private Instant updatedAt;
    private long version;
}
