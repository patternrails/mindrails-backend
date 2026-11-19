package com.mindrails.backend.asset.service;

import com.mindrails.backend.asset.model.*;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.knowledge.model.*;
import com.mindrails.backend.knowledge.service.*;

import org.jspecify.annotations.Nullable;

import java.util.*;

/** Explicit KB links and automatic indexing share the same immutable asset original. */
public final class AssetKnowledgeService {
    private final AssetStorageService assets;
    private final AssetRegistry registry;
    private final @Nullable KnowledgeDocumentService documents;
    private final @Nullable KnowledgeBaseService bases;

    public AssetKnowledgeService(
            AssetStorageService assets,
            AssetRegistry registry,
            @Nullable KnowledgeDocumentService documents,
            @Nullable KnowledgeBaseService bases) {
        this.assets = assets;
        this.registry = registry;
        this.documents = documents;
        this.bases = bases;
    }

    public synchronized UUID automaticBase() {
        UUID id = registry.scope(com.mindrails.backend.identity.ResourceAccess.creationOwner());
        if (id != null) return id;
        if (bases == null) throw AssetException.conflict("Knowledge indexing is disabled");
        var kb =
                KnowledgeResults.require(
                        bases.create(
                                "Asset Storage",
                                "Managed index for uploaded assets; chat retrieval is restricted to"
                                        + " explicitly attached versions.",
                                null));
        registry.scope(com.mindrails.backend.identity.ResourceAccess.creationOwner(), kb.getId());
        return kb.getId();
    }

    public DocumentSubmission add(
            UUID version, UUID kb, String key, String title, List<String> tags) {
        if (kb == null) throw AssetException.invalid("Knowledge base required");
        var v = assets.version(version);
        var asset = assets.asset(v.getAssetId());
        if (documents == null || bases == null) throw AssetException.conflict("Knowledge indexing is disabled");
        com.mindrails.backend.identity.ResourceAccess.requireBinding(asset.ownerId(),KnowledgeResults.require(bases.get(kb)).getOwnerId());
        return KnowledgeResults.require(
                documents.upload(
                        new KnowledgeDocumentUpload(
                                kb,
                                key == null ? "asset-" + version : key,
                                title == null ? asset.title() : title,
                                tags == null ? List.of() : tags,
                                v.getFilename(),
                                null,
                                version)));
    }

    public void autoIndex(AssetVersion v) {
        try(var scope=com.mindrails.backend.identity.ResourceAccess.as(registry.asset(v.getAssetId()).ownerId())) {
        if (registry.links(v.getId()).isEmpty())
            add(v.getId(), automaticBase(), null, null, List.of());
        }
    }

    public record Status(String state, String error) {}

    public String state(UUID version) {
        return status(version).state();
    }

    public Status status(UUID version) {
        var links = registry.links(version);
        if (links.isEmpty()) return new Status(null, null);
        if (documents == null) return new Status("UNAVAILABLE", "Knowledge indexing is disabled");
        var assetVersion = assets.version(version);
        boolean ready = false, failed = false;
        String error = null;
        for (var link : links) {
            var detail =
                    KnowledgeResults.require(
                            documents.detail(link.knowledgeBaseId(), link.documentId()));
            var latest =
                    detail.versions().stream()
                            .filter(
                                    v ->
                                            v.getStorageKey()
                                                    .equals(assetVersion.getBlobKey()))
                            .toList();
            for (var v : latest) {
                if (v.getState() == DocumentVersionState.READY
                        || v.getState() == DocumentVersionState.ARCHIVED
                        || v.getState() == DocumentVersionState.SUPERSEDED) ready = true;
                else if (v.getState() == DocumentVersionState.FAILED) {
                    failed = true;
                    error = v.getError() == null ? "Knowledge indexing failed without an error message"
                            : com.mindrails.backend.common.ExceptionDiagnostics.safe(v.getError());
                }
            }
        }
        return ready ? new Status("READY", null) : failed ? new Status("FAILED", error)
                : new Status("INDEXING", null);
    }

    public void retry(UUID version) {
        var assetVersion = assets.version(version);
        if ("FAILED".equals(assetVersion.getState())) {
            assets.retry(version);
            return;
        }
        if (documents == null) throw AssetException.conflict("Knowledge indexing is disabled");
        for (var link : registry.links(version)) {
            var doc =
                    KnowledgeResults.require(
                            documents.get(link.knowledgeBaseId(), link.documentId()));
            if (doc.getState() == KnowledgeDocumentState.FAILED)
                KnowledgeResults.require(
                        documents.retry(link.knowledgeBaseId(), link.documentId(), doc.getVersion()));
        }
    }
}
