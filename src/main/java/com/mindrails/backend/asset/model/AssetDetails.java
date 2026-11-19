package com.mindrails.backend.asset.model;

import java.util.List;
import java.util.UUID;

/** Version-specific read model; independent of HTTP response DTOs. */
public record AssetDetails(
        Asset asset,
        AssetVersion version,
        boolean inKnowledgeBase,
        UUID knowledgeBaseId,
        String knowledgeState,
        String knowledgeError,
        List<AssetVersion> versions) {}
