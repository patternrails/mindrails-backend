package com.mindrails.backend.asset.service;

import com.mindrails.backend.asset.model.AssetBlock;
import com.mindrails.backend.asset.model.AssetDetails;
import com.mindrails.backend.asset.model.AssetSource;

import java.util.List;
import java.util.UUID;

public interface AssetQueryService {
    AssetDetails get(UUID assetId);

    AssetDetails version(UUID versionId);

    List<AssetDetails> list(int page, int size);

    List<AssetBlock> blocks(UUID versionId, int page, int size);

    AssetSource source(UUID versionId, UUID extractionId, UUID blockId);
}
