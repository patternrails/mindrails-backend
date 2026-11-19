package com.mindrails.backend.mcp;

import static com.mindrails.backend.mcp.McpRuntimeTest.success;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mindrails.backend.common.ServiceResponseAdvice;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.mcp.api.toolspec.McpTools;
import com.mindrails.backend.mcp.api.web.McpController;
import com.mindrails.backend.mcp.model.McpStatus.State;
import com.mindrails.backend.mcp.model.McpToolCatalog;
import com.mindrails.backend.mcp.registry.McpRegistry;
import com.mindrails.backend.mcp.service.DefaultMcpService;
import com.mindrails.backend.mcp.service.McpService;
import com.mindrails.backend.mcp.service.internal.McpClientManager;
import com.mindrails.backend.mcp.service.internal.McpSecrets;
import com.mindrails.backend.metrics.TestMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.mindrails.backend.mcp.service.McpToolAuthorization;

class McpServiceTest {
    @Test
    void serviceMasksEveryQueryAndRetainsSecretsWhenOnlyEnabledChanges() {
        try (var fixture = new McpRuntimeTest.Fixture()) {
            var secrets = new McpSecrets(Base64.getEncoder().encodeToString(new byte[32]));
            var service =
                    new DefaultMcpService(
                            fixture.registry,
                            fixture.manager,
                            secrets,
                            fixture.props,
                            TestHistory.disabled(),
                            org.mockito.Mockito.mock(
                                    McpToolAuthorization.class, invocation -> null));
            var imported =
                    success(
                                    service.importConfig(
                                            Map.of(
                                                    "servers",
                                                    Map.of(
                                                            "remote",
                                                            Map.of(
                                                                    "url",
                                                                    "http://localhost/mcp",
                                                                    "headers",
                                                                    Map.of(
                                                                            "Authorization",
                                                                            "Bearer"
                                                                                + " private-token")))),
                                            "CREATE_ONLY",
                                            null))
                            .items()
                            .getFirst();
            assertTrue(imported.hasSecrets());
            assertEquals(McpSecrets.KEEP, imported.config().headers().get("Authorization"));
            assertEquals(imported, success(service.get(imported.id())));
            assertEquals(imported, success(service.list(0, 20)).items().getFirst());
            var updated = success(service.update(imported.id(), 0, null, false));
            assertEquals(State.DISABLED, updated.status().state());
            assertEquals(McpSecrets.KEEP, updated.config().headers().get("Authorization"));
            var stored = fixture.registry.find(imported.id()).orElseThrow();
            assertEquals(
                    "Bearer private-token",
                    secrets.resolve(stored.config()).headers().get("Authorization"));
        }
    }

    @Test
    void oldDiscoveryCannotOverwriteTheCurrentCatalogOrRecreateADeletedServer() {
        try (var fixture = new McpRuntimeTest.Fixture()) {
            var server = fixture.stdio("catalog", 30000);
            var first = new McpToolCatalog(0, Instant.now(), List.of(Map.of("name", "old")));
            fixture.registry.saveCatalog(server.id(), first);
            success(fixture.service.update(server.id(), 0, null, false));
            assertTrue(success(fixture.service.tools(server.id())).stale());
            var second = new McpToolCatalog(1, Instant.now(), List.of(Map.of("name", "new")));
            fixture.registry.saveCatalog(server.id(), second);
            fixture.registry.saveCatalog(server.id(), first);
            assertEquals(second, fixture.registry.findCatalog(server.id()).orElseThrow());
            success(fixture.service.delete(server.id(), 1));
            fixture.registry.saveCatalog(server.id(), second);
            assertTrue(fixture.registry.findCatalog(server.id()).isEmpty());
        }
    }

    @Test
    void storageFailuresUseServiceResultsAndKeepTheHttpErrorContract() throws Exception {
        var registry = mock(McpRegistry.class);
        when(registry.list())
                .thenThrow(new DataAccessResourceFailureException("private-storage-detail"));
        try (var fixture = new McpRuntimeTest.Fixture()) {
            var service =
                    new DefaultMcpService(
                            registry,
                            fixture.manager,
                            new McpSecrets(""),
                            fixture.props,
                            TestHistory.disabled(),
                            org.mockito.Mockito.mock(
                                    McpToolAuthorization.class, invocation -> null));
            assertEquals(
                    ServiceResult.ErrorCode.UNAVAILABLE,
                    assertInstanceOf(ServiceResult.Failure.class, service.list(0, 20)).code());
            var mvc =
                    MockMvcBuilders.standaloneSetup(new McpController(service))
                            .setControllerAdvice(new ServiceResponseAdvice())
                            .build();
            mvc.perform(get("/api/mcp/servers"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("MCP storage is unavailable"));
        }
    }

    @Test
    void springWiresBothEntrypointsThroughTheSameService() {
        new ApplicationContextRunner()
                .withBean(
                        McpToolAuthorization.class,
                        () -> mock(McpToolAuthorization.class, invocation -> null))
                .withUserConfiguration(
                        McpSecrets.class,
                        McpClientManager.class,
                        DefaultMcpService.class,
                        McpTools.class,
                        McpController.class)
                .withBean(com.mindrails.backend.mcp.registry.McpRegistry.class,
                        com.mindrails.backend.mcp.registry.InMemoryMcpRegistry::new)
                .withBean(com.mindrails.backend.mcp.service.internal.McpProperties.class,
                        com.mindrails.backend.mcp.service.internal.McpProperties::new)
                .withBean(
                        com.mindrails.backend.metrics.MetricsRecorder.class, TestMetrics::disabled)
                .withBean(com.mindrails.backend.history.ChatHistory.class, TestHistory::disabled)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var service = context.getBean(McpService.class);
                            var imported =
                                    success(
                                            service.importConfig(
                                                    Map.of(
                                                            "servers",
                                                            Map.of(
                                                                    "remote",
                                                                    Map.of(
                                                                            "url",
                                                                            "http://localhost/mcp"))),
                                                    "CREATE_ONLY",
                                                    null));
                            assertEquals(
                                    imported.items(),
                                    context.getBean(McpController.class)
                                            .list(0, 20)
                                            .getBody()
                                            .items());
                            assertInstanceOf(McpTools.class, context.getBean("mcpTools"));
                        });
    }
}
