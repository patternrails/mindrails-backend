package com.mindrails.backend.knowledge.model;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/** Input stream lifetime belongs to the calling transport. */
public record KnowledgeDocumentUpload(
        UUID knowledgeBaseId,
        String key,
        String title,
        List<String> tags,
        String filename,
        InputStream content,
        UUID assetVersionId) {
    public KnowledgeDocumentUpload(
            UUID kb,
            String key,
            String title,
            List<String> tags,
            String filename,
            InputStream content) {
        this(kb, key, title, tags, filename, content, null);
    }
}
