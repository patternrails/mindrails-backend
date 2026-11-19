package com.mindrails.backend.embedding;

import static com.mindrails.backend.common.ServiceResult.ErrorCode.*;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.registry.KnowledgeBaseRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class EmbeddingProviderService {
    private final EmbeddingProviderRegistry registry;
    private final ObjectProvider<KnowledgeBaseRegistry> knowledge;

    public EmbeddingProviderService(
            EmbeddingProviderRegistry registry,
            ObjectProvider<KnowledgeBaseRegistry> knowledge) {
        this.registry = registry;
        this.knowledge = knowledge;
    }

    public ServiceResult<List<EmbeddingProviderRegistry.Info>> list(int page, int size) {
        return page < 0 || size < 1 || size > 100
                ? ServiceResult.failure(INVALID_ARGUMENT, "Invalid pagination")
                : ServiceResult.success(
                        registry.list().stream().skip((long) page * size).limit(size).toList());
    }

    public ServiceResult<EmbeddingProviderRegistry.Info> get(String name) {
        return registry.list().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .map(ServiceResult::success)
                .orElseGet(() -> ServiceResult.failure(NOT_FOUND, "Embedding provider not found"));
    }

    public ServiceResult<com.mindrails.backend.provider.ProviderConnectionTest.Result> test(
            String name) {
        return get(name)
                .map(
                        ignored ->
                                com.mindrails.backend.provider.ProviderConnectionTest.run(
                                        () -> registry.require(name).embed("Connection test")));
    }

    public com.mindrails.backend.provider.ProviderConnectionTest.Result probe(EmbeddingProviderDefinition definition) {
        registry.check(definition);
        return com.mindrails.backend.provider.ProviderConnectionTest.run(() -> registry.probe(definition));
    }

    public synchronized ServiceResult<EmbeddingProviderRegistry.Info> save(
            String name, EmbeddingProviderDefinition input, boolean create) {
        if (name == null || input == null)
            return ServiceResult.failure(INVALID_ARGUMENT, "Name is required");
        var old = registry.definition(name);
        if (create && old.isPresent())
            return ServiceResult.failure(CONFLICT, "Embedding provider already exists");
        if (!create && old.isEmpty())
            return ServiceResult.failure(NOT_FOUND, "Embedding provider not found");
        if (!name.equals(input.name()))
            return ServiceResult.failure(INVALID_ARGUMENT, "Embedding provider name is immutable");
        boolean isDefault = old.map(d -> Boolean.TRUE.equals(d.defaultProvider()))
                .orElseGet(() -> !Boolean.FALSE.equals(input.defaultProvider())
                        && registry.defaultProviderName().isEmpty());
        if (input.defaultProvider() != null && input.defaultProvider() != isDefault)
            return ServiceResult.failure(CONFLICT,
                    "Changing the system default embedding provider requires index migration and is not supported yet");
        var d =
                new EmbeddingProviderDefinition(
                        name,
                        input.type(),
                        input.modelName(),
                        input.baseUrl(),
                        (input.apiKey() == null || input.apiKey().isBlank())
                                ? old.map(EmbeddingProviderDefinition::apiKey).orElse(null)
                                : input.apiKey(),
                        input.dimensions(),
                        input.timeoutMillis(),
                        input.maxRetries(),
                        isDefault);
        try {
            com.mindrails.backend.provider.ProviderSecrets.validate(d.apiKey());
            registry.check(d);
        } catch (IllegalArgumentException error) {
            return ServiceResult.failure(INVALID_ARGUMENT, error.getMessage());
        }
        if (old.isPresent()
                && referenced(name)
                && (!Objects.equals(old.get().modelName(), d.modelName())
                        || !Objects.equals(old.get().type(), d.type())
                        || old.get().dimensions() != d.dimensions()
                        || !Objects.equals(registry.endpoint(old.get()), registry.endpoint(d))))
            return ServiceResult.failure(
                    CONFLICT,
                    "Provider is indexed by knowledge or Agent memory; create a new provider and"
                            + " rebuild the index");
        try {
            EmbeddingProviderRegistry.validate(d);
            return ServiceResult.success(registry.save(d));
        } catch (IllegalArgumentException error) {
            return ServiceResult.failure(INVALID_ARGUMENT, error.getMessage());
        } catch (RuntimeException error) {
            return ServiceResult.failure(INTERNAL_ERROR, "Unable to save embedding provider");
        }
    }

    public synchronized ServiceResult<EmbeddingProviderRegistry.Info> clone(String sourceName, EmbeddingProviderDefinition input) {
        var source = registry.definition(sourceName);
        if (source.isEmpty()) return ServiceResult.failure(NOT_FOUND, "Embedding provider not found");
        if (input == null) return ServiceResult.failure(INVALID_ARGUMENT, "Configuration is required");
        String key = input.apiKey() == null || input.apiKey().isBlank() ? source.get().apiKey() : input.apiKey();
        return save(input.name(), new EmbeddingProviderDefinition(input.name(), input.type(),
                input.modelName(), input.baseUrl(), key, input.dimensions(), input.timeoutMillis(),
                input.maxRetries(), Boolean.TRUE.equals(input.defaultProvider())), true);
    }

    public synchronized ServiceResult<Void> delete(String name) {
        if (registry.definition(name).isEmpty())
            return ServiceResult.failure(NOT_FOUND, "Embedding provider not found");
        if (referenced(name))
            return ServiceResult.failure(CONFLICT, "Embedding provider is in use");
        try {
            registry.delete(name);
            return ServiceResult.success();
        } catch (RuntimeException error) {
            return ServiceResult.failure(INTERNAL_ERROR, "Unable to delete embedding provider");
        }
    }

    private boolean referenced(String name) {
        if (registry.defaultProviderName().filter(name::equals).isPresent()) return true;
        var kb = knowledge.getIfAvailable();
        return kb != null
                && kb.listKnowledgeBases().stream()
                        .anyMatch(
                                b ->
                                        b.getProfile() != null
                                                        && name.equals(b.getProfile().provider())
                                                || b.getPendingProfile() != null
                                                        && name.equals(
                                                                b.getPendingProfile().provider()));
    }
}
