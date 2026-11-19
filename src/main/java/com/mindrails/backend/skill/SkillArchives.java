package com.mindrails.backend.skill;

import com.mindrails.backend.common.ServiceResult;
import dev.langchain4j.skills.FileSystemSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.mindrails.backend.common.ServiceResult.ErrorCode.INVALID_ARGUMENT;
import static com.mindrails.backend.common.ServiceResult.ErrorCode.NOT_FOUND;
import static com.mindrails.backend.common.ServiceResult.ErrorCode.UNAVAILABLE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

@Service
public class SkillArchives {
    private static final Logger log = LoggerFactory.getLogger(SkillArchives.class);
    private static final long MAX_ZIP_BYTES = 25L * 1024 * 1024;

    private final SkillFactory factory;
    private final JdbcTemplate jdbc;

    public SkillArchives(SkillFactory factory, @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        this.factory = factory;
        this.jdbc = jdbc;
    }

    public record Installed(String name, String description, Path directory) {}

    public ServiceResult<Installed> upload(UUID userId, String filename, long bytes, InputStream input) {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")
                || bytes <= 0 || bytes > MAX_ZIP_BYTES) {
            return ServiceResult.failure(INVALID_ARGUMENT, "Select a skill ZIP of at most 25 MB");
        }
        Path root = factory.directory(userId);
        Path staging = null;
        try {
            createDirectory(root);
            staging = Files.createTempDirectory(root, ".upload-");
            Path archive = staging.resolve("skill.zip");
            Files.copy(input, archive);
            Path directory = SkillZip.extract(archive, staging);
            FileSystemSkill skill = SkillZip.verify(directory);
            return ServiceResult.success(install(userId, root, directory, skill));
        } catch (CharacterCodingException error) {
            return ServiceResult.failure(INVALID_ARGUMENT, "SKILL.md must be UTF-8 text");
        } catch (IllegalArgumentException error) {
            return ServiceResult.failure(INVALID_ARGUMENT, error.getMessage());
        } catch (IOException | RuntimeException error) {
            log.error("Could not install skill ZIP for user {}", userId, error);
            return ServiceResult.failure(UNAVAILABLE, "Skill upload failed");
        } finally {
            discard(staging);
        }
    }

    private synchronized Installed install(UUID userId, Path root, Path directory, FileSystemSkill skill)
            throws IOException {
        Set<String> names = factory.definitions(root).stream()
                .map(FileSystemSkill::name).collect(Collectors.toSet());
        String name = availableName(root, skill.name(), names);
        if (!name.equals(skill.name())) SkillZip.rename(directory, name);

        Path target = root.resolve(name);
        Files.move(directory, target);
        try {
            jdbc.update("""
                    INSERT INTO skill_definitions (id, owner_id, name) VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE id = id
                    """, SkillCatalog.id(userId, name).toString(), userId.toString(), name);
        } catch (RuntimeException error) {
            try {
                Files.move(target, directory);
            } catch (IOException rollback) {
                error.addSuppressed(rollback);
            }
            throw error;
        }
        return new Installed(name, skill.description(), target);
    }

    private static String availableName(Path root, String original, Set<String> names) {
        String name = original;
        for (int suffix = 1; names.contains(name) || Files.exists(root.resolve(name), NOFOLLOW_LINKS); suffix++) {
            name = original + "-" + suffix;
            SkillZip.validateName(name);
        }
        return name;
    }

    public synchronized ServiceResult<Void> delete(UUID userId, String name) {
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            return ServiceResult.failure(INVALID_ARGUMENT, "Invalid skill name");
        }
        var moved = new LinkedHashMap<Path, Path>();
        try {
            Path root = factory.directory(userId);
            for (FileSystemSkill skill : factory.readDirectory(root)) {
                if (!skill.name().equals(name)) continue;
                Path archive = root.resolve(".deleted");
                createDirectory(archive);
                Path source = skill.basePath();
                Path target = archive.resolve(UUID.randomUUID().toString());
                Files.move(source, target);
                moved.put(source, target);
            }
            return moved.isEmpty() ? ServiceResult.failure(NOT_FOUND, "Skill not found") : ServiceResult.success();
        } catch (IOException | RuntimeException error) {
            moved.forEach((source, target) -> {
                try {
                    Files.move(target, source);
                } catch (IOException rollback) {
                    error.addSuppressed(rollback);
                }
            });
            log.error("Could not archive skill {} for user {}", name, userId, error);
            return ServiceResult.failure(UNAVAILABLE, "Skill deletion failed; refresh before retrying");
        }
    }

    private void createDirectory(Path directory) throws IOException {
        if (!factory.isSafeDirectory(directory)) throw new IOException("Skill directory must not contain symlinks");
        Files.createDirectories(directory);
        if (!factory.isSafeDirectory(directory)) throw new IOException("Skill directory must not contain symlinks");
    }

    private static void discard(Path directory) {
        if (directory == null) return;
        try {
            FileSystemUtils.deleteRecursively(directory);
        } catch (IOException error) {
            log.warn("Could not remove skill upload staging directory {}", directory, error);
        }
    }
}
