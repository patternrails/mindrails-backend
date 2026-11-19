package com.mindrails.backend.identity;

import java.util.UUID;

/** Persisted ownership metadata; application writes cannot transfer it after creation. */
public interface UserOwnedResource {
    UUID getOwnerId();
    void setOwnerId(UUID ownerId);
}
