package com.mindrails.backend.agent.api.web;

import static com.mindrails.backend.common.ServiceResponses.response;

import com.mindrails.backend.identity.IdentityFilter;
import com.mindrails.backend.identity.IdentityService;
import com.mindrails.backend.provider.*;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/provider")
public class ProviderController {
    private final ProviderService service;

    public ProviderController(ProviderService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<Provider>> listProviders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return response(service.list(page, size), HttpStatus.OK);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Provider> get(@PathVariable UUID id) {
        return response(service.get(id), HttpStatus.OK);
    }

    public record Write(
            String name,
            String chatModel,
            Boolean streaming,
            String baseUrl,
            String apiKey,
            String modelName,
            Boolean reasoning,
            String thinkingLevel,
            java.math.BigDecimal modelCost,
            Integer contextWindow,
            Integer maxTokens,
            Boolean defaultProvider) {
        ProviderDefinition definition() {
            return new ProviderDefinition(
                    null,
                    name,
                    chatModel,
                    streaming,
                    baseUrl,
                    apiKey,
                    modelName,
                    reasoning,
                    thinkingLevel,
                    modelCost,
                    contextWindow,
                    maxTokens);
        }
    }

    @PostMapping
    public ResponseEntity<Provider> create(@RequestBody Write input, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(
                service.save(
                        UUID.randomUUID(),
                        input.definition(),
                        Boolean.TRUE.equals(input.defaultProvider()),
                        true),
                HttpStatus.CREATED);
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<Provider> clone(@PathVariable UUID id, @RequestBody Write input, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(service.clone(id, input.definition()), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Provider> update(@PathVariable UUID id, @RequestBody Write input, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(
                service.save(
                        id,
                        input.definition(),
                        Boolean.TRUE.equals(input.defaultProvider()),
                        false),
                HttpStatus.OK);
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<ProviderConnectionTest.Result> test(@PathVariable UUID id, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(service.test(id), HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
        IdentityService.requireAdmin(IdentityFilter.current(request));
        return response(service.delete(id), HttpStatus.NO_CONTENT);
    }
}
