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
public class KnowledgeDocumentVersion implements VersionedKnowledgeEntity {
    private UUID id;
    private UUID knowledgeBaseId;
    private UUID documentId;
    private KnowledgeIndexProfile profile;
    private String filename;
    private String mediaType;
    private String storageKey;
    private String hash;
    private long bytes;
    private String textKey;
    private DocumentVersionState state;
    private String error;
    private int chunkCount;
    private int processedChunks;
    private Instant createdAt;
    private Instant updatedAt;
    private long version;
}
