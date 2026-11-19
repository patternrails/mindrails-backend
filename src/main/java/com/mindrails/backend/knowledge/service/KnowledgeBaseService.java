package com.mindrails.backend.knowledge.service;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeBaseDetails;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.model.KnowledgePage;

import java.util.List;
import java.util.UUID;

public interface KnowledgeBaseService {
    ServiceResult<KnowledgePage<KnowledgeBase>> page(int page, int size);

    ServiceResult<List<KnowledgeBase>> list();

    ServiceResult<KnowledgeBase> get(UUID id);

    ServiceResult<KnowledgeBaseDetails> detail(UUID id);

    ServiceResult<KnowledgeBase> create(String name, String description, String provider);

    ServiceResult<KnowledgeBase> update(UUID id, String name, String description, long version);

    ServiceResult<KnowledgeBase> rebuild(
            UUID id, String provider, Integer size, Integer overlap, long version);

    ServiceResult<KnowledgeBase> cancelRebuild(UUID id, long version);

    ServiceResult<KnowledgeIngestionJob> delete(UUID id, long version);

    ServiceResult<List<KnowledgeIngestionJob>> listJobs(UUID id);
}
