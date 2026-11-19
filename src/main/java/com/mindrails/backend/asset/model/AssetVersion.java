package com.mindrails.backend.asset.model;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AssetVersion {
    private UUID id;
    private UUID assetId;
    private UUID extractionId;
    private String parserVersion;
    private int number;
    private String filename;
    private String mediaType;
    private String blobKey;
    // Persisted storage identity also protects local files from legacy object-storage records.
    private String storageBackend;
    private String storageLocation;
    private String hash;
    private String md5;
    private long bytes;
    private String state;
    private String error;
    private int textChars;
    private boolean autoIndex;
    private Instant createdAt;
}
