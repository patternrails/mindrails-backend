package com.mindrails.backend.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindrails.backend.history.api.web.*;
import com.mindrails.backend.history.model.*;
import com.mindrails.backend.history.service.DefaultHistoryService;
import com.mindrails.backend.history.store.InMemoryHistoryStore;
import com.mindrails.backend.metrics.*;
import com.mindrails.backend.observability.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ObservabilityAuthorizationTest {
    static final String PASSWORD = "verification-password-2026";
    final UUID session = UUID.randomUUID(), record = UUID.randomUUID();
    final InMemoryHistoryStore history = new InMemoryHistoryStore();
    final class TestClock extends Clock {
        Instant now = Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    final TestClock clock = new TestClock();
    MockMvc mvc;
    IdentityService identity;
    String admin, normal;

    @BeforeEach void setup() {
        var p = new IdentityProperties(); p.setAdminPassword(PASSWORD); p.setAccessSeconds(60);
        identity = new IdentityService(new InMemoryIdentityStore(), p, clock);
        admin = identity.login("admin", PASSWORD, "local").accessToken();
        identity.create(identity.authenticate(admin), new UserController.UserWrite("normal", "Normal", "", "", PASSWORD, null, Identity.Role.Normal));
        normal = identity.login("normal", PASSWORD, "local").accessToken();
        history.insert(new HistoryRecord(record, identity.authenticate(normal).id(), null, session, null, null, null, null,
                HistoryRecord.Transport.SYNC, HistoryRecord.Status.COMPLETED, "[]", "{}", clock.instant(), clock.instant(), 1));
        var health = new MetricsHealth();
        var buffer = new MetricsSnapshotBuffer(10, 1_000_000, Duration.ofMinutes(1), health);
        mvc = MockMvcBuilders.standaloneSetup(
                new HistoryController(new DefaultHistoryService(history)),
                new ObservabilityController(filter -> MetricsAggregates.from(List.of(), filter), new MetricsProperties(), health, buffer))
                .setControllerAdvice(new HistoryErrorAdvice(), new IdentityErrorAdvice())
                .addFilters(new IdentityFilter(identity)).build();
    }

    List<String> routes() {
        return List.of("/api/history", "/api/history/" + record, "/api/metrics/overview", "/api/metrics/sessions/" + session);
    }

    @Test void allHistoryAndMetricsReadsRequireValidTokens() throws Exception {
        for (String path : routes()) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        }
        mvc.perform(delete("/api/history/" + record)).andExpect(status().isUnauthorized());
        clock.now = clock.now.plusSeconds(61);
        for (String path : routes())
            mvc.perform(get(path).header("Authorization", "Bearer " + admin)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/history/" + record).header("Authorization", "Bearer " + admin)).andExpect(status().isUnauthorized());
    }

    @Test void normalUsersCanRestoreHistoryReadMetricsAndManageArchives() throws Exception {
        for (String path : routes())
            mvc.perform(get(path).header("Authorization", "Bearer " + normal)).andExpect(status().isOk());
        mvc.perform(delete("/api/history/" + record).header("Authorization", "Bearer " + normal)).andExpect(status().isNoContent());
        mvc.perform(get("/api/history/" + record).header("Authorization", "Bearer " + normal)).andExpect(status().isNotFound());
    }

    @Test void systemAdminCannotReadOrDeleteAnotherUsersHistory() throws Exception {
        mvc.perform(get("/api/history").header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        mvc.perform(get("/api/history/" + record).header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/history/" + record).header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
    }
}
