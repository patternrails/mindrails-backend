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
public class KnowledgeIngestionJob implements VersionedKnowledgeEntity {
    private UUID id;
    private UUID knowledgeBaseId;
    private UUID documentId;
    private UUID revisionId;
    private IngestionJobType kind;
    private IngestionJobState state;
    private int attempts;
    private String leaseOwner;
    private Instant leaseUntil;
    private String error;
    private Instant createdAt;
    private Instant updatedAt;
    private long version;
}
