package com.mindrails.backend.embedding.api;

import static com.mindrails.backend.common.ServiceResponses.response;

import com.mindrails.backend.embedding.*;

import com.mindrails.backend.identity.IdentityFilter;
import com.mindrails.backend.identity.IdentityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/embedding-provider")
public class EmbeddingProviderController {
    private final EmbeddingProviderService service;

    public EmbeddingProviderController(EmbeddingProviderService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<EmbeddingProviderRegistry.Info>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(service.list(page, size), HttpStatus.OK);
    }

    @GetMapping("/{name}")
    public ResponseEntity<EmbeddingProviderRegistry.Info> get(@PathVariable String name) {
        return response(service.get(name), HttpStatus.OK);
    }

    @PostMapping
    public ResponseEntity<EmbeddingProviderRegistry.Info> create(
            @RequestBody EmbeddingProviderDefinition input, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(service.save(input.name(), input, true), HttpStatus.CREATED);
    }

    @PostMapping("/{name}/clone")
    public ResponseEntity<EmbeddingProviderRegistry.Info> clone(@PathVariable String name,
            @RequestBody EmbeddingProviderDefinition input, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(service.clone(name, input), HttpStatus.CREATED);
    }

    @PutMapping("/{name}")
    public ResponseEntity<EmbeddingProviderRegistry.Info> update(
            @PathVariable String name, @RequestBody EmbeddingProviderDefinition input, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(service.save(name, input, false), HttpStatus.OK);
    }

    @PostMapping("/{name}/test")
    public ResponseEntity<com.mindrails.backend.provider.ProviderConnectionTest.Result> test(@PathVariable String name, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(service.test(name), HttpStatus.OK);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(service.delete(name), HttpStatus.NO_CONTENT);
    }
}
