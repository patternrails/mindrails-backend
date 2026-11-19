package com.mindrails.backend.asset.service;

import com.mindrails.backend.asset.model.*;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

public interface AssetStorageService {
    UUID LOCAL_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    AssetVersion upload(
            String filename, String title, String kind, InputStream input, boolean autoIndex);

    AssetVersion uploadText(String content, String title);

    AssetVersion replace(UUID assetId, String filename, InputStream input);

    Asset asset(UUID id);

    AssetVersion version(UUID id);

    List<Asset> list(int page, int size);

    List<AssetVersion> versions(UUID id);

    List<AssetBlock> blocks(UUID version);

    Path materialize(String key);

    InputStream open(UUID version);

    void delete(UUID asset);

    void retry(UUID version);
}
