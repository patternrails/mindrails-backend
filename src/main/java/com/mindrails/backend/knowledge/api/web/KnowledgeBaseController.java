package com.mindrails.backend.knowledge.api.web;

import static com.mindrails.backend.common.ServiceResponses.response;
import static com.mindrails.backend.knowledge.api.web.KnowledgeRequests.version;

import com.mindrails.backend.embedding.EmbeddingProviderRegistry;
import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeBaseDetails;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.model.KnowledgePage;
import com.mindrails.backend.knowledge.service.KnowledgeBaseService;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {
    private final ObjectProvider<KnowledgeBaseService> services;
    private final ObjectProvider<EmbeddingProviderRegistry> embeddings;

    public KnowledgeBaseController(
            ObjectProvider<KnowledgeBaseService> services,
            ObjectProvider<EmbeddingProviderRegistry> embeddings) {
        this.services = services;
        this.embeddings = embeddings;
    }

    private KnowledgeBaseService service() {
        var value = services.getIfAvailable();
        if (value == null) {
            throw KnowledgeException.disabled();
        }
        return value;
    }

    @GetMapping("/config")
    public ResponseEntity<KnowledgeConfigurationResponse> configuration() {
        var registry = embeddings.getIfAvailable();
        return ResponseEntity.ok(
                new KnowledgeConfigurationResponse(
                        services.getIfAvailable() != null,
                        registry == null ? null : registry.defaultProviderName().orElse(null),
                        registry == null ? List.of() : registry.list()));
    }

    @GetMapping
    public ResponseEntity<KnowledgePage<KnowledgeBase>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(service().page(page, size), HttpStatus.OK);
    }

    @PostMapping
    public ResponseEntity<KnowledgeBase> create(@RequestBody KnowledgeBaseRequest request) {
        return response(
                service()
                        .create(request.name(), request.description(), request.embeddingProvider()),
                HttpStatus.CREATED);
    }

    @GetMapping("/{knowledgeBaseId}")
    public ResponseEntity<KnowledgeBaseDetails> detail(@PathVariable UUID knowledgeBaseId) {
        return response(service().detail(knowledgeBaseId), HttpStatus.OK);
    }

    @PutMapping("/{knowledgeBaseId}")
    public ResponseEntity<KnowledgeBase> update(
            @PathVariable UUID knowledgeBaseId, @RequestBody KnowledgeBaseRequest request) {
        return response(
                service()
                        .update(
                                knowledgeBaseId,
                                request.name(),
                                request.description(),
                                version(request.version())),
                HttpStatus.OK);
    }

    @DeleteMapping("/{knowledgeBaseId}")
    public ResponseEntity<KnowledgeIngestionJob> delete(
            @PathVariable UUID knowledgeBaseId, @RequestParam long version) {
        return response(service().delete(knowledgeBaseId, version), HttpStatus.ACCEPTED);
    }

    @PostMapping("/{knowledgeBaseId}/reindex")
    public ResponseEntity<KnowledgeBase> rebuild(
            @PathVariable UUID knowledgeBaseId, @RequestBody KnowledgeRebuildRequest request) {
        return response(
                service()
                        .rebuild(
                                knowledgeBaseId,
                                request.embeddingProvider(),
                                request.chunkChars(),
                                request.overlap(),
                                version(request.version())),
                HttpStatus.ACCEPTED);
    }

    @DeleteMapping("/{knowledgeBaseId}/reindex")
    public ResponseEntity<KnowledgeBase> cancelRebuild(
            @PathVariable UUID knowledgeBaseId, @RequestParam long version) {
        return response(service().cancelRebuild(knowledgeBaseId, version), HttpStatus.OK);
    }

    @GetMapping("/{knowledgeBaseId}/jobs")
    public ResponseEntity<List<KnowledgeIngestionJob>> listJobs(
            @PathVariable UUID knowledgeBaseId) {
        return response(service().listJobs(knowledgeBaseId), HttpStatus.OK);
    }
}
