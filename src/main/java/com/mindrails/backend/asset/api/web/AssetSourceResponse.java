package com.mindrails.backend.asset.api.web;

import com.mindrails.backend.asset.model.AssetBlock;
import com.mindrails.backend.asset.model.AssetSource;

public record AssetSourceResponse(AssetVersionResponse version, AssetBlock block, String downloadUrl) {
    public static AssetSourceResponse from(AssetSource source) {
        return new AssetSourceResponse(
                AssetVersionResponse.from(source.version()), source.block(),
                "/api/asset-storage/versions/" + source.version().getId() + "/download");
    }
}
