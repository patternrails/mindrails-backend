package com.mindrails.backend.knowledge.service;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.model.KnowledgeSearchHit;

import java.util.List;
import java.util.UUID;

public interface KnowledgeSearchService {
    ServiceResult<List<KnowledgeSearchHit>> search(UUID knowledgeBaseId, String query);

    default ServiceResult<List<KnowledgeSearchHit>> searchVersions(
            UUID knowledgeBaseId, String query, java.util.Set<UUID> versions) {
        return search(knowledgeBaseId, query)
                .map(
                        hits ->
                                hits.stream()
                                        .filter(hit -> versions.contains(hit.revisionId()))
                                        .toList());
    }
}
