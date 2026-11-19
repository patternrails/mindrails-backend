package com.mindrails.backend.knowledge.api.web;

import static com.mindrails.backend.common.ServiceResponses.response;
import static com.mindrails.backend.knowledge.api.web.KnowledgeRequests.version;

import com.mindrails.backend.knowledge.model.DocumentChunk;
import com.mindrails.backend.knowledge.model.DocumentSubmission;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentUpload;
import com.mindrails.backend.knowledge.model.KnowledgePage;
import com.mindrails.backend.knowledge.service.KnowledgeDocumentService;
import com.mindrails.backend.knowledge.service.KnowledgeException;
import com.mindrails.backend.knowledge.service.KnowledgeResults;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/documents")
public class KnowledgeDocumentController {
    private final ObjectProvider<KnowledgeDocumentService> services;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.mindrails.backend.knowledge.store.DocumentStorage> storage;

    @GetMapping("/{documentId}/versions/{revisionId}/download")
    public ResponseEntity<FileSystemResource> downloadFixed(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId) {
        var revision =
                KnowledgeResults.require(service().versions(knowledgeBaseId, documentId)).stream()
                        .filter(v -> v.getId().equals(revisionId))
                        .findFirst()
                        .orElseThrow(KnowledgeException::missing);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(revision.getFilename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(storage.getObject().path(revision.getStorageKey())));
    }

    @GetMapping("/{documentId}/versions/{revisionId}/chunks")
    public List<DocumentChunk> fixedChunks(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @PathVariable UUID revisionId) {
        var revision =
                KnowledgeResults.require(service().versions(knowledgeBaseId, documentId)).stream()
                        .filter(v -> v.getId().equals(revisionId))
                        .findFirst()
                        .orElseThrow(KnowledgeException::missing);
        if (revision.getTextKey() == null) return List.of();
        return tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readValue(
                        storage.getObject().text(revision.getTextKey()),
                        new tools.jackson.core.type.TypeReference<List<DocumentChunk>>() {});
    }

    public KnowledgeDocumentController(ObjectProvider<KnowledgeDocumentService> services) {
        this.services = services;
    }

    private KnowledgeDocumentService service() {
        var value = services.getIfAvailable();
        if (value == null) {
            throw KnowledgeException.disabled();
        }
        return value;
    }

    @GetMapping
    public ResponseEntity<KnowledgePage<KnowledgeDocumentSummaryResponse>> list(
            @PathVariable UUID knowledgeBaseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(
                service()
                        .page(knowledgeBaseId, page, size)
                        .map(data -> data.map(KnowledgeDocumentSummaryResponse::from)),
                HttpStatus.OK);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentSubmission> upload(
            @PathVariable UUID knowledgeBaseId,
            @RequestParam String key,
            @RequestParam String title,
            @RequestParam(required = false) List<String> tags,
            @RequestParam MultipartFile file)
            throws IOException {
        try (var input = file.getInputStream()) {
            return response(
                    service()
                            .upload(
                                    new KnowledgeDocumentUpload(
                                            knowledgeBaseId,
                                            key,
                                            title,
                                            tags == null ? List.of() : tags,
                                            file.getOriginalFilename(),
                                            input)),
                    HttpStatus.ACCEPTED);
        }
    }

    @PostMapping("/text")
    public ResponseEntity<DocumentSubmission> createText(
            @PathVariable UUID knowledgeBaseId, @RequestBody KnowledgeTextRequest request) {
        return response(
                service()
                        .createText(
                                knowledgeBaseId,
                                request.key(),
                                request.title(),
                                request.content(),
                                request.tags(),
                                null,
                                null,
                                null),
                HttpStatus.ACCEPTED);
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<KnowledgeDocumentDetailResponse> get(
            @PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
        return response(
                service()
                        .detail(knowledgeBaseId, documentId)
                        .map(KnowledgeDocumentDetailResponse::from),
                HttpStatus.OK);
    }

    @PatchMapping("/{documentId}")
    public ResponseEntity<KnowledgeDocument> updateMetadata(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @RequestBody KnowledgeDocumentMetadataRequest request) {
        return response(
                service()
                        .updateMetadata(
                                knowledgeBaseId,
                                documentId,
                                request.title(),
                                request.tags(),
                                version(request.version())),
                HttpStatus.OK);
    }

    @PutMapping(value = "/{documentId}/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentSubmission> replace(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @RequestParam long version,
            @RequestParam MultipartFile file)
            throws IOException {
        try (var input = file.getInputStream()) {
            return response(
                    service()
                            .replace(
                                    knowledgeBaseId,
                                    documentId,
                                    file.getOriginalFilename(),
                                    input,
                                    version),
                    HttpStatus.ACCEPTED);
        }
    }

    @PostMapping("/{documentId}/reindex")
    public ResponseEntity<DocumentSubmission> retry(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @RequestParam long version) {
        return response(service().retry(knowledgeBaseId, documentId, version), HttpStatus.ACCEPTED);
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<DocumentSubmission> delete(
            @PathVariable UUID knowledgeBaseId,
            @PathVariable UUID documentId,
            @RequestParam long version) {
        return response(
                service().delete(knowledgeBaseId, documentId, version), HttpStatus.ACCEPTED);
    }

    @GetMapping(value = "/{documentId}/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> parsedText(
            @PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
        return response(service().parsedText(knowledgeBaseId, documentId), HttpStatus.OK);
    }

    @GetMapping("/{documentId}/chunks")
    public ResponseEntity<List<DocumentChunk>> chunks(
            @PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
        return response(service().chunks(knowledgeBaseId, documentId), HttpStatus.OK);
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<FileSystemResource> download(
            @PathVariable UUID knowledgeBaseId, @PathVariable UUID documentId) {
        var service = service();
        var path = KnowledgeResults.require(service.download(knowledgeBaseId, documentId));
        var revision =
                KnowledgeResults.require(service.downloadVersion(knowledgeBaseId, documentId));
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(revision.getFilename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(path));
    }
}
