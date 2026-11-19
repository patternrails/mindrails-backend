package com.mindrails.backend.asset.service;

import com.mindrails.backend.agent.registry.TaskExecutionStore;
import com.mindrails.backend.asset.config.AssetStorageProperties;

import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Manages local Task execution deliverables, their metadata, downloads and cleanup. */
@Service
public final class TaskExecutionDeliverables {
    public record File(
            UUID id, String name, String path, long bytes, String updatedAt, String downloadUrl) {}

    private final AssetStorageProperties properties;
    private final TaskExecutionStore executions;
    private final JsonMapper json = new JsonMapper();

    public TaskExecutionDeliverables(AssetStorageProperties properties, TaskExecutionStore executions) {
        this.properties = properties;
        this.executions = executions;
    }

    private void check(UUID execution) {
        if (executions.find(execution).isEmpty()) throw AssetException.missing();
    }

    private Path directory(UUID execution) {
        return properties.directory()
                .resolve("task-executions").resolve(execution.toString());
    }

    private void noSymlinks(Path path) {
        Path root = properties.directory();
        for (Path p = path; p != null && p.startsWith(root); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) {
                throw AssetException.invalid("Symlinks are not execution output paths");
            }
        }
    }

    public Path resolve(UUID execution, String name) {
        check(execution);
        if (name == null || name.isBlank() || name.contains("\\")) {
            throw AssetException.invalid("Use a execution-relative output filename");
        }
        return validateFile(execution, directory(execution).resolve("files").resolve(name));
    }

    private Path validateFile(UUID execution, Path path) {
        Path root = directory(execution).resolve("files");
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw AssetException.invalid("Output must stay inside this execution folder");
        }
        noSymlinks(path);
        return path;
    }

    /** Returns the validated output directory without creating it. */
    public Path outputDirectory(UUID execution) {
        check(execution);
        Path root = directory(execution).resolve("files");
        noSymlinks(root);
        return root;
    }

    /** Registers existing execution output files after a successful operation. */
    public List<File> publishAll(UUID execution) throws IOException {
        Path root = outputDirectory(execution);
        if (!Files.isDirectory(root)) return List.of();
        var published = new ArrayList<File>();
        try (var outputs = Files.walk(root)) {
            for (Path path : outputs.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                            && !p.getFileName().toString().startsWith(".pending-"))
                    .toList()) {
                published.add(publish(execution, path));
            }
        }
        return List.copyOf(published);
    }

    private File describe(UUID execution, Path path) throws IOException {
        String relative = directory(execution).resolve("files")
                .relativize(path).toString().replace('\\', '/');
        UUID id = UUID.nameUUIDFromBytes(relative.getBytes(StandardCharsets.UTF_8));
        return new File(id, path.getFileName().toString(), relative, Files.size(path),
                Files.getLastModifiedTime(path).toInstant().toString(),
                "/api/asset-storage/task-executions/" + execution + "/deliverables/" + id + "/download");
    }

    /** Registers an existing file after validating its Task execution ownership. */
    public File publish(UUID execution, Path path) throws IOException {
        check(execution);
        path = validateFile(execution, path);
        Path metadata = directory(execution).resolve("metadata");
        noSymlinks(metadata);
        Files.createDirectories(metadata);
        File file = describe(execution, path);
        Path temporary = Files.createTempFile(metadata, "publish-", ".part");
        try {
            Files.writeString(temporary, json.writeValueAsString(file));
            Files.move(temporary, metadata.resolve(file.id() + ".json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return file;
    }

    public List<File> list(UUID execution) {
        check(execution);
        Path metadata = directory(execution).resolve("metadata");
        noSymlinks(metadata);
        if (!Files.isDirectory(metadata)) return List.of();
        try (var paths = Files.list(metadata)) {
            var result = new ArrayList<File>();
            for (Path record : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                noSymlinks(record);
                File file = json.readValue(Files.readString(record), File.class);
                Path path = resolve(execution, file.path());
                if (Files.isRegularFile(path)) result.add(file);
            }
            return result.stream()
                    .sorted(Comparator.comparing(File::updatedAt).reversed().thenComparing(File::path))
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public Path download(UUID execution, UUID id) {
        var file = list(execution).stream().filter(f -> f.id().equals(id))
                .findFirst().orElseThrow(AssetException::missing);
        return resolve(execution, file.path());
    }

    /** Deletes only the named deliverable and its metadata inside the owning Task execution. */
    public void delete(UUID execution, UUID id) {
        Path path = download(execution, id);
        Path metadata = directory(execution).resolve("metadata").resolve(id + ".json");
        noSymlinks(metadata);
        try {
            Files.delete(path);
            Files.deleteIfExists(metadata);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

}
