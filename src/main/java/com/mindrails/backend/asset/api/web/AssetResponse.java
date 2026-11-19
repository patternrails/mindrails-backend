package com.mindrails.backend.asset.api.web;

import com.mindrails.backend.asset.model.Asset;
import com.mindrails.backend.asset.model.AssetDetails;

import java.util.List;
import java.util.UUID;

public record AssetResponse(
        Asset asset, AssetVersionResponse version, boolean inKnowledgeBase, UUID knowledgeBaseId,
        String knowledgeState, String knowledgeError, List<AssetVersionResponse> versions) {
    public static AssetResponse from(AssetDetails details) {
        return new AssetResponse(
                details.asset(), AssetVersionResponse.from(details.version()),
                details.inKnowledgeBase(), details.knowledgeBaseId(), details.knowledgeState(),
                details.knowledgeError(),
                details.versions().stream().map(AssetVersionResponse::from).toList());
    }
}
