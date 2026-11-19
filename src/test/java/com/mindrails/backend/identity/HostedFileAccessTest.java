package com.mindrails.backend.identity;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HostedFileAccessTest {
    static class Time extends Clock {
        Instant now = Instant.parse("2026-10-31T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    @Test void grantIsReusableOnlyForItsFileUntilExpiryAndDoesNotReplaceApiCredentials() {
        var time = new Time();
        var properties = new IdentityProperties(); properties.setAdminPassword("hosted-file-test-password");
        var identity = new IdentityService(new InMemoryIdentityStore(), properties, time);
        var actor = identity.authenticate(identity.login("admin", "hosted-file-test-password", "test").accessToken());
        var access = new HostedFileAccess(identity, properties, time);
        String path = "/api/asset-storage/versions/" + UUID.randomUUID() + "/view";
        String grant = access.issue(actor, path);
        assertEquals(actor, access.authenticate(grant, path));
        assertEquals(actor, access.authenticate(grant, path));
        assertThrows(IdentityException.class, () -> identity.authenticate(grant));
        assertThrows(IdentityException.class, () -> access.authenticate(grant, path.replace("/view", "/download")));
        assertThrows(IdentityException.class, () -> access.authenticate(grant, "/api/asset-storage/versions/" + UUID.randomUUID() + "/view"));
        time.now = time.now.plusSeconds(HostedFileAccess.SECONDS);
        assertThrows(IdentityException.class, () -> access.authenticate(grant, path));
    }

    @Test void redirectTargetsUseOnlyAllowedApplicationOriginsAndValidFileRoutes() {
        var properties = new IdentityProperties(); properties.setAdminPassword("hosted-file-test-password");
        var identity = new IdentityService(new InMemoryIdentityStore(), properties);
        var access = new HostedFileAccess(identity, properties);
        String id = UUID.randomUUID().toString();
        String path = "/api/asset-storage/versions/" + id + "/view";
        assertEquals("http://localhost:3000/files/assets/" + id, access.applicationUrl("http://localhost:3000", path));
        for (String origin : new String[]{"https://evil.example", "http://localhost:3000/", "http://localhost:3000@evil.example", "null"})
            assertThrows(IdentityException.class, () -> access.applicationUrl(origin, path));
        for (String invalid : new String[]{"/api/auth/me", path + "/extra", path + "?token=test", path.replace(id, "../secret")})
            assertFalse(HostedFileAccess.isViewPath(invalid));
    }
}
