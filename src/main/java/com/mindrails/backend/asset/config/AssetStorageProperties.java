package com.mindrails.backend.asset.config;

import com.mindrails.backend.common.config.FileStorageProperties;
import lombok.Getter;
import lombok.Setter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@Getter
@Setter
@ConfigurationProperties("mindrails.asset-storage")
public class AssetStorageProperties {
    @Getter(lombok.AccessLevel.NONE)
    private final FileStorageProperties files;
    private long maxUploadBytes = 25 * 1024 * 1024;
    private int maxPreviewBytes = 2 * 1024 * 1024;
    private int directTextChars = 8000;
    private int contextChars = 24000;
    private int maxAttachments = 10;

    public AssetStorageProperties() {
        this(new FileStorageProperties(Path.of("./data")));
    }

    @Autowired
    public AssetStorageProperties(FileStorageProperties files) {
        this.files = files;
    }

    public Path directory() {
        return files.assets();
    }

    public Path storageRoot() {
        return files.directory();
    }

    public Path cacheDirectory() {
        return files.assetCache();
    }

    public String location() {
        return directory().toString();
    }
}
