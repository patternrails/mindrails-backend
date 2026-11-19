package com.mindrails.backend.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Runtime model catalog with persistable configuration definitions. */
public interface ProviderRegistry {
    default Optional<ProviderDefinition> definition(UUID id) {
        return Optional.empty();
    }

    default Provider put(ProviderDefinition definition, boolean makeDefault) {
        throw new UnsupportedOperationException();
    }

    default void delete(UUID id) {
        throw new UnsupportedOperationException();
    }

    Optional<Provider> find(UUID id);

    List<Provider> list(int page, int size);

    Optional<Provider> defaultProvider();
}
