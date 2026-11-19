package com.mindrails.backend.knowledge.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mindrails.backend.knowledge.model.DocumentVersionState;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class KnowledgeToolsTest {
    @Test
    void stagedIndexMustNotBeReportedAsSearchableBeforePublication() {
        var revision =
                KnowledgeDocumentVersion.builder()
                        .id(UUID.randomUUID())
                        .state(DocumentVersionState.READY)
                        .build();
        var doc =
                KnowledgeDocument.builder()
                        .activeVersionId(UUID.randomUUID())
                        .pendingVersionId(revision.getId())
                        .build();
        assertEquals("INDEXED_PENDING_PUBLICATION", KnowledgeTools.documentState(doc, revision));
        doc.setActiveVersionId(revision.getId());
        assertEquals("READY", KnowledgeTools.documentState(doc, revision));
        revision.setState(DocumentVersionState.FAILED);
        assertEquals("FAILED", KnowledgeTools.documentState(doc, revision));
    }
}
