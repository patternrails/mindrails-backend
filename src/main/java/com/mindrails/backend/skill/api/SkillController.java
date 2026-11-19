package com.mindrails.backend.skill.api;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.skill.SkillArchives;
import com.mindrails.backend.skill.SkillCatalog;
import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.SkillResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.mindrails.backend.common.ServiceResponses.response;
import static com.mindrails.backend.common.ServiceResult.ErrorCode.INVALID_ARGUMENT;
import static com.mindrails.backend.common.ServiceResult.ErrorCode.NOT_FOUND;
import static com.mindrails.backend.common.ServiceResult.ErrorCode.UNAVAILABLE;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private final SkillCatalog catalog;
    private final SkillArchives archives;

    public SkillController(SkillCatalog catalog, SkillArchives archives) {
        this.catalog = catalog;
        this.archives = archives;
    }

    public record Summary(UUID id, UUID ownerId, String name, String description, String root) {
        static Summary from(UUID userId, FileSystemSkill skill) {
            return from(userId, skill.name(), skill.description(), skill.basePath());
        }

        static Summary from(UUID userId, String name, String description, Path directory) {
            return new Summary(SkillCatalog.id(userId, name), userId, name, description, directory.toString());
        }
    }

    public record Page(List<Summary> items, long total, int page, int size, boolean hasNext) {}
    public record Detail(Summary skill, String content, List<String> resources) {}
    public record Config(List<String> paths, String classpathPath) {}

    @GetMapping
    public ResponseEntity<Page> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String q) {
        if (page < 0 || size < 1 || size > 100 || q.length() > 1024) {
            return response(ServiceResult.failure(INVALID_ARGUMENT,
                    "page must be non-negative, size must be between 1 and 100, and q at most 1024 characters"),
                    HttpStatus.OK);
        }
        UUID userId = ResourceAccess.creationOwner();
        return response(ServiceResult.tryOf(() -> page(userId, page, size, q), UNAVAILABLE), HttpStatus.OK);
    }

    private Page page(UUID userId, int page, int size, String q) {
        String query = q.strip().toLowerCase(Locale.ROOT);
        var matching = catalog.definitions(userId).stream()
                .filter(skill -> skill.name().toLowerCase(Locale.ROOT).contains(query)
                        || skill.description().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(FileSystemSkill::name)).toList();
        long offset = (long) page * size;
        var items = matching.stream().skip(offset).limit(size).map(skill -> Summary.from(userId, skill)).toList();
        return new Page(items, matching.size(), page, size, offset + size < matching.size());
    }

    @GetMapping("/config")
    public Config config() {
        return new Config(List.of(catalog.directory(ResourceAccess.creationOwner()).toString()), "");
    }

    @GetMapping("/{name}/detail")
    public ResponseEntity<Detail> detail(@PathVariable String name) {
        UUID userId = ResourceAccess.creationOwner();
        var result = ServiceResult.tryOf(() -> catalog.definitions(userId), UNAVAILABLE)
                .flatMap(skills -> skills.stream().filter(skill -> skill.name().equals(name)).findFirst()
                        .map(skill -> ServiceResult.success(detail(userId, skill)))
                        .orElseGet(() -> ServiceResult.failure(NOT_FOUND, "Skill not found")));
        return response(result, HttpStatus.OK);
    }

    private static Detail detail(UUID userId, FileSystemSkill skill) {
        var resources = skill.resources().stream().map(SkillResource::relativePath).sorted().toList();
        return new Detail(Summary.from(userId, skill), skill.content(), resources);
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Summary> upload(@RequestPart("file") MultipartFile file) {
        UUID userId = ResourceAccess.creationOwner();
        try (var input = file.getInputStream()) {
            var result = archives.upload(userId, file.getOriginalFilename(), file.getSize(), input)
                    .map(skill -> Summary.from(userId, skill.name(), skill.description(), skill.directory()));
            return response(result, HttpStatus.CREATED);
        } catch (IOException error) {
            return response(ServiceResult.failure(UNAVAILABLE, "Could not read skill upload"), HttpStatus.CREATED);
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        return response(archives.delete(ResourceAccess.creationOwner(), name), HttpStatus.NO_CONTENT);
    }
}
