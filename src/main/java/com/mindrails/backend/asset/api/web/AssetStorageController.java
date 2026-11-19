package com.mindrails.backend.asset.api.web;

import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.AssetBlock;
import com.mindrails.backend.asset.service.AssetConversationRetriever;
import com.mindrails.backend.asset.service.AssetKnowledgeService;
import com.mindrails.backend.asset.service.AssetQueryService;
import com.mindrails.backend.asset.service.AssetStorageService;
import com.mindrails.backend.knowledge.model.DocumentSubmission;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/asset-storage")
public class AssetStorageController {
    private final AssetStorageProperties properties;
    private final @Nullable AssetStorageService assets;
    private final @Nullable AssetQueryService queries;
    private final @Nullable AssetKnowledgeService knowledge;
    private final @Nullable AssetConversationRetriever conversation;

    public AssetStorageController(
            AssetStorageProperties properties,
            @Nullable AssetStorageService assets,
            @Nullable AssetQueryService queries,
            @Nullable AssetKnowledgeService knowledge,
            @Nullable AssetConversationRetriever conversation) {
        this.properties = properties;
        this.assets = assets;
        this.queries = queries;
        this.knowledge = knowledge;
        this.conversation = conversation;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("enabled", true,
                "deliverablesEnabled", true,
                "maxUploadBytes", properties.getMaxUploadBytes(),
                "maxAttachments", properties.getMaxAttachments());
    }

    @GetMapping("/sessions/{session}/attachments")
    public List<AssetVersionResponse> attachments(@PathVariable UUID session) {
        return conversation.files(session).stream().map(AssetVersionResponse::from).toList();
    }

    @DeleteMapping("/sessions/{session}/attachments/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void detach(@PathVariable UUID session, @PathVariable UUID version) {
        conversation.detach(session, version);
    }

    @GetMapping
    public List<AssetResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.list(page, size).stream().map(AssetResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AssetResponse get(@PathVariable UUID id) {
        return AssetResponse.from(queries.get(id));
    }

    @GetMapping("/versions/{version}")
    public AssetResponse getVersion(@PathVariable UUID version) {
        return AssetResponse.from(queries.version(version));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AssetResponse upload(@RequestParam MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(defaultValue = "FILE") String kind) throws IOException {
        try (var input = file.getInputStream()) {
            var version = assets.upload(file.getOriginalFilename(), title, kind, input, true);
            return AssetResponse.from(queries.version(version.getId()));
        }
    }

    @PostMapping("/text")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AssetResponse text(@RequestBody AssetTextRequest input) {
        var version = assets.uploadText(input.content(), input.title());
        return AssetResponse.from(queries.version(version.getId()));
    }

    @PutMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AssetResponse replace(@PathVariable UUID id, @RequestParam MultipartFile file)
            throws IOException {
        try (var input = file.getInputStream()) {
            var version = assets.replace(id, file.getOriginalFilename(), input);
            return AssetResponse.from(queries.version(version.getId()));
        }
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        assets.delete(id);
    }

    @PostMapping("/versions/{version}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AssetResponse retry(@PathVariable UUID version) {
        knowledge.retry(version);
        return AssetResponse.from(queries.version(version));
    }

    @PostMapping("/versions/{version}/knowledge")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentSubmission add(@PathVariable UUID version, @RequestBody AssetKnowledgeRequest input) {
        return knowledge.add(version, input.knowledgeBaseId(), input.key(), input.title(), input.tags());
    }

    @GetMapping("/versions/{version}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID version) {
        var file = assets.version(version);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFilename(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.getBytes())
                .body(new InputStreamResource(assets.open(version)));
    }

    @GetMapping("/versions/{version}/blocks")
    public List<AssetBlock> blocks(@PathVariable UUID version,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return queries.blocks(version, page, size);
    }

    @GetMapping("/versions/{version}/extractions/{extraction}/blocks/{block}")
    public AssetSourceResponse block(
            @PathVariable UUID version, @PathVariable UUID extraction, @PathVariable UUID block) {
        return AssetSourceResponse.from(queries.source(version, extraction, block));
    }
}
