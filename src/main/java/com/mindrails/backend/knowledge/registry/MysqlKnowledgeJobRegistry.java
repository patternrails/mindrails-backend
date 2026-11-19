package com.mindrails.backend.knowledge.registry;

import com.mindrails.backend.knowledge.model.IngestionJobState;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MysqlKnowledgeJobRegistry extends JdbcKnowledgeSnapshots
        implements KnowledgeJobRegistry {
    private final TransactionTemplate transactions;

    public MysqlKnowledgeJobRegistry(
            JdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions) {
        super(jdbc, mapper);
        this.transactions = transactions;
    }

    public KnowledgeIngestionJob getJob(UUID id) {
        return one("knowledge_ingestion_jobs", id, KnowledgeIngestionJob.class);
    }

    public List<KnowledgeIngestionJob> listJobs(UUID knowledgeBaseId) {
        return query(
                "knowledge_ingestion_jobs",
                " WHERE knowledge_base_id=? ORDER BY created_at DESC,id LIMIT 1000",
                KnowledgeIngestionJob.class,
                knowledgeBaseId.toString());
    }

    public void insert(KnowledgeIngestionJob e) {
        insert(
                "knowledge_ingestion_jobs",
                e,
                e.getState().name(),
                e.getCreatedAt(),
                Map.of("knowledge_base_id", e.getKnowledgeBaseId().toString()));
    }

    public void save(KnowledgeIngestionJob e) {
        save(
                "knowledge_ingestion_jobs",
                e,
                e.getState().name(),
                Collections.singletonMap(
                        "lease_until",
                        e.getLeaseUntil() == null ? null : Timestamp.from(e.getLeaseUntil())));
    }

    public KnowledgeIngestionJob claimNextJob(String owner, int leaseMillis) {
        return claimNextJob(owner, leaseMillis, null);
    }

    public KnowledgeIngestionJob claimNextJob(String owner, int leaseMillis, UUID knowledgeBaseId) {
        return transactions.execute(
                status -> {
                    String eligible =
                            "((state='QUEUED' AND (lease_until IS NULL OR"
                                    + " lease_until<UTC_TIMESTAMP(6))) OR (state='RUNNING' AND"
                                    + " lease_until<UTC_TIMESTAMP(6)))";
                    var candidates =
                            knowledgeBaseId == null
                                    ? query(
                                            "knowledge_ingestion_jobs",
                                            " WHERE "
                                                    + eligible
                                                    + " ORDER BY created_at,id LIMIT 1 FOR UPDATE"
                                                    + " SKIP LOCKED",
                                            KnowledgeIngestionJob.class)
                                    : query(
                                            "knowledge_ingestion_jobs",
                                            " WHERE knowledge_base_id=? AND "
                                                    + eligible
                                                    + " ORDER BY created_at,id LIMIT 1 FOR UPDATE"
                                                    + " SKIP LOCKED",
                                            KnowledgeIngestionJob.class,
                                            knowledgeBaseId.toString());
                    if (candidates.isEmpty()) {
                        return null;
                    }
                    var job = candidates.getFirst();
                    job.setState(IngestionJobState.RUNNING);
                    job.setAttempts(job.getAttempts() + 1);
                    job.setLeaseOwner(owner);
                    job.setLeaseUntil(Instant.now().plusMillis(leaseMillis));
                    job.setUpdatedAt(Instant.now());
                    save(job);
                    return job;
                });
    }
}
