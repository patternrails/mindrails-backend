package com.mindrails.backend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

@ConfigurationProperties("mindrails.file-storage")
public record FileStorageProperties(@DefaultValue("./data") Path directory) {
    public static final String SKILL_DIRECTORY = "skill";

    public FileStorageProperties {
        directory = directory.toAbsolutePath().normalize();
    }

    public Path knowledge() {
        return directory.resolve("knowledge");
    }

    public Path assets() {
        return directory.resolve("assets");
    }

    public Path assetCache() {
        return directory.resolve("asset-cache");
    }

    public Path skills() {
        return directory.resolve(SKILL_DIRECTORY);
    }

    public Path avatars() {
        return directory.resolve("avatars");
    }
}
