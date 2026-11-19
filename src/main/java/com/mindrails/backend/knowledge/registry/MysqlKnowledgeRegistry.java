package com.mindrails.backend.knowledge.registry;

import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.common.ServiceResult;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Composes typed SQL registries under the same DataSource transaction. */
public final class MysqlKnowledgeRegistry implements KnowledgeRegistry {
    private final TransactionTemplate transactions;
    private final KnowledgeBaseRegistry bases;
    private final KnowledgeDocumentRegistry documents;
    private final KnowledgeJobRegistry jobs;

    public MysqlKnowledgeRegistry(JdbcTemplate jdbc, ObjectMapper mapper) {
        transactions =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getDataSource())));
        bases = new MysqlKnowledgeBaseRegistry(jdbc, mapper);
        documents = new MysqlKnowledgeDocumentRegistry(jdbc, mapper);
        jobs = new MysqlKnowledgeJobRegistry(jdbc, mapper, transactions);
    }

    public <T> T transaction(Supplier<T> work) {
        return transactions.execute(status -> {
            var result = work.get();
            if (result instanceof ServiceResult.Failure<?>) status.setRollbackOnly();
            return result;
        });
    }

    public KnowledgeBase getKnowledgeBase(UUID id) {
        return bases.getKnowledgeBase(id);
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        return bases.listKnowledgeBases();
    }

    public void lockKnowledgeBase(UUID id) {
        bases.lockKnowledgeBase(id);
    }

    public void insert(KnowledgeBase e) {
        bases.insert(e);
    }

    public void save(KnowledgeBase e) {
        bases.save(e);
    }

    public void deleteKnowledgeBase(UUID id) {
        bases.deleteKnowledgeBase(id);
    }

    public KnowledgeDocument getDocument(UUID id) {
        return documents.getDocument(id);
    }

    public KnowledgeDocumentVersion getDocumentVersion(UUID id) {
        return documents.getDocumentVersion(id);
    }

    public List<KnowledgeDocument> listDocuments(UUID knowledgeBaseId) {
        return documents.listDocuments(knowledgeBaseId);
    }

    public List<KnowledgeDocumentVersion> listDocumentVersions(UUID doc) {
        return documents.listDocumentVersions(doc);
    }

    public void insert(KnowledgeDocument e) {
        documents.insert(e);
    }

    public void insert(KnowledgeDocumentVersion e) {
        documents.insert(e);
    }

    public void save(KnowledgeDocument e) {
        documents.save(e);
    }

    public void save(KnowledgeDocumentVersion e) {
        documents.save(e);
    }

    public void deleteDocument(UUID id) {
        documents.deleteDocument(id);
    }

    public KnowledgeIngestionJob getJob(UUID id) {
        return jobs.getJob(id);
    }

    public List<KnowledgeIngestionJob> listJobs(UUID knowledgeBaseId) {
        return jobs.listJobs(knowledgeBaseId);
    }

    public void insert(KnowledgeIngestionJob e) {
        jobs.insert(e);
    }

    public void save(KnowledgeIngestionJob e) {
        jobs.save(e);
    }

    public KnowledgeIngestionJob claimNextJob(String owner, int leaseMillis) {
        return jobs.claimNextJob(owner, leaseMillis);
    }

    public KnowledgeIngestionJob claimNextJob(String owner, int leaseMillis, UUID knowledgeBaseId) {
        return jobs.claimNextJob(owner, leaseMillis, knowledgeBaseId);
    }
}
