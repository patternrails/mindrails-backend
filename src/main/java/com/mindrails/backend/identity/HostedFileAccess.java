package com.mindrails.backend.identity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Short-lived browser navigation credentials, restricted to one file and one login session. */
@Service
public final class HostedFileAccess {
    public static final String COOKIE = "mindrails_file_view";
    public static final int SECONDS = 900;
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final String VIEW_PATH = "/api/asset-storage/(?:versions/" + UUID_PATTERN
            + "|(?:sessions|task-executions)/" + UUID_PATTERN + "/deliverables/" + UUID_PATTERN + ")/view";
    private static final SecureRandom RANDOM = new SecureRandom();
    private record Grant(UUID sessionId, String path, Instant expiresAt) {}
    private final Map<String, Grant> grants = new HashMap<>();
    private final IdentityService identity;
    private final IdentityProperties properties;
    private final Clock clock;

    @Autowired
    public HostedFileAccess(IdentityService identity, IdentityProperties properties) {
        this(identity, properties, Clock.systemUTC());
    }
    HostedFileAccess(IdentityService identity, IdentityProperties properties, Clock clock) {
        this.identity = identity; this.properties = properties; this.clock = clock;
    }

    public static boolean isViewPath(String path) { return path != null && path.matches(VIEW_PATH); }

    public synchronized String issue(Identity.Current actor, String path) {
        if (!isViewPath(path)) throw new IdentityException(400, "Invalid hosted file path");
        identity.sessionIdentity(actor.sessionId());
        grants.values().removeIf(grant -> !grant.expiresAt().isAfter(clock.instant()));
        if (grants.size() >= 10_000) throw new IdentityException(429, "Too many hosted file sessions");
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        grants.put(hash(token), new Grant(actor.sessionId(), path, clock.instant().plusSeconds(SECONDS)));
        return token;
    }

    public synchronized Identity.Current authenticate(String token, String path) {
        if (!isViewPath(path) || token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw IdentityException.unauthorized();
        Grant grant = grants.get(hash(token));
        if (grant == null || !grant.path().equals(path) || !grant.expiresAt().isAfter(clock.instant()))
            throw IdentityException.unauthorized();
        return identity.sessionIdentity(grant.sessionId()); // Rechecks logout, user revocation and absolute session expiry.
    }

    public boolean secureCookie() { return properties.isSecureCookie(); }
    public String cookieSameSite() { return properties.getCookieSameSite(); }

    public void requireOrigin(String origin) {
        if (origin == null || !Arrays.asList(properties.origins()).contains(origin))
            throw new IdentityException(400, "Invalid application origin");
        try {
            URI uri = URI.create(origin);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) { throw new IdentityException(400, "Invalid application origin"); }
    }

    /** Safe re-entry through the frontend when a navigation credential expires or the server restarts. */
    public String applicationUrl(String origin, String path) {
        requireOrigin(origin);
        if (!isViewPath(path)) throw new IdentityException(400, "Invalid hosted file path");
        String resource = path.substring("/api/asset-storage/".length(), path.length() - "/view".length());
        if (resource.startsWith("versions/")) resource = "assets/" + resource.substring("versions/".length());
        return origin + "/files/" + resource;
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
