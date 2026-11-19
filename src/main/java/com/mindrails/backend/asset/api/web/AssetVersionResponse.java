package com.mindrails.backend.asset.api.web;

import com.mindrails.backend.asset.model.AssetVersion;

import java.time.Instant;
import java.util.UUID;

public record AssetVersionResponse(
        UUID id, UUID assetId, UUID extractionId, int number, String filename, String mediaType,
        String hash, String md5, long bytes, String state, String error, int textChars,
        Instant createdAt) {
    public static AssetVersionResponse from(AssetVersion version) {
        return new AssetVersionResponse(
                version.getId(), version.getAssetId(), version.getExtractionId(),
                version.getNumber(), version.getFilename(), version.getMediaType(),
                version.getHash(), version.getMd5(), version.getBytes(), version.getState(),
                version.getError(), version.getTextChars(), version.getCreatedAt());
    }
}
