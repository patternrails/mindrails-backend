package com.mindrails.backend.knowledge.registry;

import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;

import java.util.List;
import java.util.UUID;

public interface KnowledgeJobRegistry {
    KnowledgeIngestionJob getJob(UUID id);

    List<KnowledgeIngestionJob> listJobs(UUID knowledgeBaseId);

    void insert(KnowledgeIngestionJob e);

    void save(KnowledgeIngestionJob e);

    KnowledgeIngestionJob claimNextJob(String owner, int leaseMillis);

    KnowledgeIngestionJob claimNextJob(String owner, int leaseMillis, UUID knowledgeBaseId);
}
