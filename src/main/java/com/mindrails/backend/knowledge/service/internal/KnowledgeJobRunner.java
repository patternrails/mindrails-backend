package com.mindrails.backend.knowledge.service.internal;

import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;

/** Dispatches a claimed task; task failure policy is independent of the processing pipeline. */
public final class KnowledgeJobRunner {
    private final KnowledgeIngestionService ingestion;
    private final KnowledgeCleanupService cleanup;
    private final KnowledgeTaskService tasks;

    public KnowledgeJobRunner(
            KnowledgeIngestionService ingestion,
            KnowledgeCleanupService cleanup,
            KnowledgeTaskService tasks) {
        this.ingestion = ingestion;
        this.cleanup = cleanup;
        this.tasks = tasks;
    }

    public void process(KnowledgeIngestionJob job) {
        try {
            switch (job.getKind()) {
                case INGEST -> ingestion.process(job);
                case DELETE_DOCUMENT, DELETE_BASE -> cleanup.cleanup(job);
            }
        } catch (Exception error) {
            tasks.fail(job, error);
        }
    }
}
