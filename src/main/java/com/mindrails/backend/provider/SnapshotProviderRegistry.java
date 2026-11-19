package com.mindrails.backend.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable catalog order; the default provider is first. Also useful for programmatic/test
 * catalogs.
 */
public class SnapshotProviderRegistry implements ProviderRegistry {
    protected volatile List<Provider> providers;

    public SnapshotProviderRegistry(List<Provider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public Optional<Provider> find(UUID id) {
        return providers.stream().filter(provider -> provider.getId().equals(id)).findFirst();
    }

    @Override
    public List<Provider> list(int page, int size) {
        if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid provider pagination");
        return providers.stream().skip((long) page * size).limit(size).toList();
    }

    @Override
    public Optional<Provider> defaultProvider() {
        return providers.stream().findFirst();
    }
}
