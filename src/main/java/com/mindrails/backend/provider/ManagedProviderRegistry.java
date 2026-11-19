package com.mindrails.backend.provider;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** Validates a replacement before committing storage, then atomically publishes the new catalog. */
public abstract class ManagedProviderRegistry extends SnapshotProviderRegistry {
    public record Catalog(String defaultProvider, List<ProviderDefinition> providers) {}

    private Catalog catalog;
    private final ProvidersConfiguration loader;

    protected ManagedProviderRegistry(
            Catalog catalog, ProvidersConfiguration loader) {
        super(
                catalog.providers().isEmpty()
                        ? List.of()
                        : loader.loadDefinitions(
                                catalog.providers(), catalog.defaultProvider()));
        this.loader = loader;
        this.catalog =
                new Catalog(
                        catalog.defaultProvider(),
                        catalog.providers().stream()
                                .map(
                                        d ->
                                                withId(
                                                        d,
                                                        d.id() == null
                                                                ? UUID.nameUUIDFromBytes(
                                                                        ("mindrails:provider:"
                                                                                        + d.name())
                                                                                .getBytes(
                                                                                        java.nio
                                                                                                .charset
                                                                                                .StandardCharsets
                                                                                                .UTF_8))
                                                                : d.id()))
                                .toList());
    }

    private static ProviderDefinition withId(ProviderDefinition d, UUID id) {
        return new ProviderDefinition(
                id,
                d.name(),
                d.chatModel(),
                d.streaming(),
                d.baseUrl(),
                d.apiKey(),
                d.modelName(),
                d.reasoning(),
                d.thinkingLevel(),
                d.modelCost(),
                d.contextWindow(),
                d.maxTokens());
    }

    protected abstract void persist(Catalog next);

    protected Catalog currentCatalog() {
        return catalog;
    }

    @Override
    public synchronized Optional<ProviderDefinition> definition(UUID id) {
        return catalog.providers().stream().filter(d -> d.id().equals(id)).findFirst();
    }

    @Override
    public synchronized Provider put(ProviderDefinition definition, boolean makeDefault) {
        var entries = new ArrayList<>(catalog.providers());
        String previousName =
                definition(definition.id()).map(ProviderDefinition::name).orElse(null);
        int index = -1;
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).id().equals(definition.id())) index = i;
        if (index < 0) entries.add(definition);
        else entries.set(index, definition);
        String defaultName =
                makeDefault
                                || entries.size() == 1
                                || Objects.equals(previousName, catalog.defaultProvider())
                        ? definition.name()
                        : catalog.defaultProvider();
        return commit(new Catalog(defaultName, List.copyOf(entries))).stream()
                .filter(provider -> provider.getId().equals(definition.id())).findFirst().orElseThrow();
    }

    @Override
    public synchronized void delete(UUID id) {
        var entries = catalog.providers().stream().filter(d -> !d.id().equals(id)).toList();
        String defaultName =
                entries.stream().anyMatch(d -> d.name().equals(catalog.defaultProvider()))
                        ? catalog.defaultProvider()
                        : entries.isEmpty() ? null : entries.getFirst().name();
        commit(new Catalog(defaultName, entries));
    }

    private List<Provider> commit(Catalog next) {
        var built =
                next.providers().isEmpty()
                        ? List.<Provider>of()
                        : loader.loadDefinitions(
                                next.providers(), next.defaultProvider());
        persist(next);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish(next, built); }
            });
            return built;
        }
        publish(next, built);
        return providers;
    }

    private synchronized void publish(Catalog next, List<Provider> built) {
        // Preserve provider identities held by existing clients. In-flight requests retain their model.
        var old = providers;
        providers =
                built.stream()
                        .map(
                                p -> {
                                    var previous =
                                            old.stream()
                                                    .filter(v -> v.getId().equals(p.getId()))
                                                    .findFirst();
                                    if (previous.isPresent()) {
                                        org.springframework.beans.BeanUtils.copyProperties(
                                                p, previous.get());
                                        return previous.get();
                                    }
                                    return p;
                                })
                        .toList();
        catalog = next;
    }
}
