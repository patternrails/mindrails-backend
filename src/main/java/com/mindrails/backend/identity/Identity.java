package com.mindrails.backend.identity;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

public final class Identity {
    private Identity() {}
    public enum Role { SystemAdmin, Normal }
    public record User(UUID id, String loginId, String name, String email, String bio,
                       String avatarUrl, String language, Role role, @com.fasterxml.jackson.annotation.JsonIgnore String passwordHash, long version, long authVersion, Instant createdAt) {
        public Profile profile() { return new Profile(id, loginId, name, email, bio, avatarUrl, language, role,
                id.equals(IdentityService.ADMIN_ID), createdAt); }
    }
    public record Profile(UUID id, String loginId, String name, String email, String bio,
                          String avatarUrl, String language, Role role, boolean configuredAdmin, Instant createdAt) {}
    public record Session(UUID id, UUID userId, long userVersion, String accessHash, String refreshHash,
                          Instant accessExpiresAt, Instant refreshExpiresAt) {}
    public record Current(UUID id, String name, Role role, UUID sessionId) implements Principal {
        @Override public String getName() { return name; }
        public boolean admin() { return role == Role.SystemAdmin; }
    }
    public record Tokens(String accessToken, String refreshToken, Instant accessExpiresAt,
                         Instant refreshExpiresAt, Profile user) {}
}
