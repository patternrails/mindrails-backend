package com.mindrails.backend.knowledge.api.web;

import static com.mindrails.backend.common.ServiceResponses.response;

import com.mindrails.backend.knowledge.model.KnowledgeSearchHit;
import com.mindrails.backend.knowledge.service.KnowledgeException;
import com.mindrails.backend.knowledge.service.KnowledgeSearchService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/search")
public class KnowledgeSearchController {
    private final ObjectProvider<KnowledgeSearchService> services;

    public KnowledgeSearchController(ObjectProvider<KnowledgeSearchService> services) {
        this.services = services;
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeSearchHit>> search(
            @PathVariable UUID knowledgeBaseId, @RequestParam String query) {
        var service = services.getIfAvailable();
        if (service == null) {
            throw KnowledgeException.disabled();
        }
        return response(service.search(knowledgeBaseId, query), HttpStatus.OK);
    }
}
