package com.mindrails.backend.provider;

import static com.mindrails.backend.common.ServiceResult.ErrorCode.*;

import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.common.ServiceResult;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;

@Service
public class ProviderService {
    private final ProviderRegistry registry;
    private final ObjectProvider<AgentRegistry> agents;
    private final ObjectProvider<SessionRegistry> sessions;
    @Autowired(required=false) private TaskRegistry tasks;

    @Autowired
    public ProviderService(
            ProviderRegistry registry,
            ObjectProvider<AgentRegistry> agents,
            ObjectProvider<SessionRegistry> sessions) {
        this.registry = registry;
        this.agents = agents;
        this.sessions = sessions;
    }

    public ProviderService(ProviderRegistry registry) {
        this.registry = registry;
        agents = null;
        sessions = null;
    }

    public ServiceResult<List<Provider>> list(int page, int size) {
        return page < 0 || size < 1 || size > 100
                ? ServiceResult.failure(INVALID_ARGUMENT, "Invalid pagination")
                : ServiceResult.success(registry.list(page, size));
    }

    public ServiceResult<Provider> get(UUID id) {
        return registry.find(id)
                .map(ServiceResult::success)
                .orElseGet(() -> ServiceResult.failure(NOT_FOUND, "Provider not found"));
    }

    public ServiceResult<ProviderConnectionTest.Result> test(UUID id) {
        return get(id).map(
                        provider ->
                                ProviderConnectionTest.run(
                                        () -> ProviderConnectionTest.language(provider)));
    }

    public synchronized ServiceResult<Provider> save(
            UUID id, ProviderDefinition input, boolean makeDefault, boolean create) {
        if (!create && registry.find(id).isEmpty())
            return ServiceResult.failure(NOT_FOUND, "Provider not found");
        try {
            validate(input);
            if (registry.list(0, Integer.MAX_VALUE).stream()
                    .anyMatch(
                            p ->
                                    p.getName().equalsIgnoreCase(input.name().trim())
                                            && !p.getId().equals(id)))
                return ServiceResult.failure(CONFLICT, "Provider name already exists");
            String reference =
                    (input.apiKey() == null || input.apiKey().isBlank())
                            ? registry.definition(id).map(ProviderDefinition::apiKey).orElse(null)
                            : input.apiKey();
            ProviderSecrets.validate(reference);
            var definition =
                    new ProviderDefinition(
                            id,
                            input.name().trim(),
                            input.chatModel(),
                            input.streaming(),
                            input.baseUrl(),
                            reference,
                            input.modelName().trim(),
                            input.reasoning(),
                            input.thinkingLevel(),
                            input.modelCost(),
                            input.contextWindow(),
                            input.maxTokens());
            return ServiceResult.success(registry.put(definition, makeDefault));
        } catch (IllegalArgumentException error) {
            return ServiceResult.failure(INVALID_ARGUMENT, error.getMessage());
        } catch (RuntimeException error) {
            return ServiceResult.failure(INTERNAL_ERROR, "Unable to save provider");
        }
    }

    public synchronized ServiceResult<Provider> clone(UUID sourceId, ProviderDefinition input) {
        var source = registry.definition(sourceId);
        if (source.isEmpty()) return ServiceResult.failure(NOT_FOUND, "Provider not found");
        if (input == null) return ServiceResult.failure(INVALID_ARGUMENT, "Configuration is required");
        String key = input.apiKey() == null || input.apiKey().isBlank() ? source.get().apiKey() : input.apiKey();
        return save(UUID.randomUUID(), input.withApiKey(key), false, true);
    }

    public synchronized ServiceResult<Void> delete(UUID id) {
        if (registry.find(id).isEmpty())
            return ServiceResult.failure(NOT_FOUND, "Provider not found");
        if (!referencing(id).isEmpty())
            return ServiceResult.failure(CONFLICT, "Provider is used by a Session");
        if(tasks != null) {
            synchronized(tasks) {
                if(tasks.referencesProvider(id)) return ServiceResult.failure(CONFLICT,"Provider is used by a Task");
                return removeProvider(id);
            }
        }
        return removeProvider(id);
    }

    private ServiceResult<Void> removeProvider(UUID id) {
        try {
            registry.delete(id);
            return ServiceResult.success();
        } catch (RuntimeException error) {
            return ServiceResult.failure(INTERNAL_ERROR, "Unable to delete provider");
        }
    }

    private List<com.mindrails.backend.agent.model.Session> referencing(UUID id) {
        if (agents == null || agents.getIfAvailable() == null || sessions.getIfAvailable() == null)
            return List.of();
        var result = new ArrayList<com.mindrails.backend.agent.model.Session>();
        for (int page = 0; ; page++) {
            var batch = agents.getObject().listManaged(page, 100);
            for (var agent : batch)
                for (var s : sessions.getObject().listAllManaged(agent.getId()))
                    if (s.getProvider().getId().equals(id)) result.add(s);
            if (batch.size() < 100) return result;
        }
    }

    static void validate(ProviderDefinition d) {
        if (d == null
                || d.name() == null
                || d.name().isBlank()
                || d.name().length() > 255
                || d.modelName() == null
                || d.modelName().isBlank()
                || d.modelName().length() > 255)
            throw new IllegalArgumentException(
                    "Name and model name are required (maximum 255 characters)");
        if (d.modelCost() != null
                && (d.modelCost().signum() < 0
                        || d.modelCost().precision() > 20
                        || d.modelCost().scale() > 8))
            throw new IllegalArgumentException("Cost must be non-negative");
        if (d.baseUrl() != null) validateUrl(d.baseUrl());
    }

    public static void validateUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid endpoint URL");
        }
        if (value.length() > 2048
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException(
                    "Endpoint must be HTTP(S), without credentials, query or fragment");
    }
}
