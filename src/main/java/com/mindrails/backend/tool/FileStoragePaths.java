package com.mindrails.backend.tool;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.identity.ResourceAccess;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves a tool path against its working directory within the configured file storage. */
public final class FileStoragePaths {
    private FileStoragePaths() {}

    public static Path resolveFile(Path root, Path workingDirectory, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("path must not be blank");
        Path path = workingDirectory.resolve(value).toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            throw new ToolFailure("Path must be inside MINDRAILS_FILE_STORAGE_DIRECTORY");
        }
        if (path.equals(root) || path.equals(workingDirectory)) {
            throw new ToolFailure("Expected a file path inside MINDRAILS_FILE_STORAGE_DIRECTORY");
        }
        var userId = ResourceAccess.userId();
        Path skills = root.resolve(FileStorageProperties.SKILL_DIRECTORY);
        if (userId != null && path.startsWith(skills) && !path.startsWith(skills.resolve(userId.toString()))) {
            throw new ToolFailure("Use your own user skill directory");
        }
        for (Path component = path; component != null && component.startsWith(root); component = component.getParent()) {
            if (Files.isSymbolicLink(component)) {
                throw new ToolFailure("Symbolic links are not supported in file tool paths");
            }
        }
        return path;
    }
}
