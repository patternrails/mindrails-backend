package com.mindrails.backend.knowledge.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocument implements VersionedKnowledgeEntity {
    private UUID id;
    private UUID knowledgeBaseId;
    private String key;
    private String title;
    private List<String> tags;
    private KnowledgeSourceType sourceType;
    private UUID sourceAgentId;
    private UUID sourceSessionId;
    private String sourceTurnId;
    private UUID activeVersionId;
    private UUID pendingVersionId;
    private KnowledgeDocumentState state;
    private Instant createdAt;
    private Instant updatedAt;
    private long version;
}
