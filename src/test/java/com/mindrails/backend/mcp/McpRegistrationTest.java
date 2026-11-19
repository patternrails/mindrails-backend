package com.mindrails.backend.mcp;

import static com.mindrails.backend.mcp.McpRuntimeTest.success;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mindrails.backend.common.ServiceResponseAdvice;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.mcp.api.web.McpController;
import com.mindrails.backend.mcp.api.web.McpRequestAdvice;
import com.mindrails.backend.mcp.model.McpConnectionConfig;
import com.mindrails.backend.mcp.service.internal.McpConfigParser;
import com.mindrails.backend.mcp.service.internal.McpSecrets;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class McpRegistrationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void acceptsClientDialectsAndPreservesExtensionsButValidatesExecutionFields() {
        var parser = new McpConfigParser();
        var parsed =
                parser.parse(
                                Map.of(
                                        "servers",
                                        Map.of(
                                                "remote",
                                                Map.of(
                                                        "url",
                                                        "http://localhost:8765/mcp",
                                                        "type",
                                                        "http",
                                                        "vendorOption",
                                                        "kept"))))
                        .getFirst();
        assertEquals("streamable-http", parsed.config().type());
        assertEquals("kept", parsed.config().extensions().get("vendorOption"));
        assertFalse(parsed.warnings().isEmpty());
        for (var config :
                List.of(
                        Map.of("command", "python3", "url", "http://localhost/mcp"),
                        Map.of("url", "file:///tmp/a"),
                        Map.of("url", "http://localhost/mcp", "timeoutMs", 1),
                        Map.of("command", "python3", "args", "bad"),
                        Map.of(
                                "url",
                                "http://localhost/mcp",
                                "headers",
                                Map.of("X-Test", "bad\nheader")))) {
            assertThrows(IllegalArgumentException.class, () -> parser.normalize("server", config));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("{\"servers\":{},\"servers\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"servers\":{}} {}"));
    }

    @Test
    void importIsAtomicAndUpsertRequiresVersionAndKeepsOmittedServers() {
        try (var fixture = new McpRuntimeTest.Fixture()) {
            var registry = fixture.registry;
            var service = fixture.service;
            var first =
                    success(
                                    service.importConfig(
                                            Map.of(
                                                    "mcpServers",
                                                    Map.of(
                                                            "one",
                                                            Map.of("url", "http://localhost/mcp"))),
                                            "CREATE_ONLY",
                                            null))
                            .items()
                            .getFirst();
            var batch = new LinkedHashMap<String, Object>();
            batch.put("new", Map.of("command", "python3"));
            batch.put("one", Map.of("url", "http://localhost/new"));
            assertFailure(
                    ErrorCode.CONFLICT,
                    service.importConfig(Map.of("mcpServers", batch), "CREATE_ONLY", null));
            assertEquals(1, registry.list().size());
            assertFailure(
                    ErrorCode.CONFLICT,
                    service.importConfig(
                            Map.of("servers", Map.of("one", Map.of("url", "http://localhost/new"))),
                            "UPSERT",
                            null));
            var updated =
                    success(
                                    service.importConfig(
                                            Map.of(
                                                    "servers",
                                                    Map.of(
                                                            "one",
                                                            Map.of("url", "http://localhost/new"))),
                                            "UPSERT",
                                            Map.of("one", 0L)))
                            .items()
                            .getFirst();
            assertEquals(first.id(), updated.id());
            assertEquals(1, updated.version());
            success(
                    service.importConfig(
                            Map.of("servers", Map.of("two", Map.of("command", "python3"))),
                            "CREATE_ONLY",
                            null));
            assertFailure(ErrorCode.CONFLICT, service.update(first.id(), 0, null, false));
            assertEquals(2, registry.list().size());
            fixture.props.setMaxServers(2);
            assertFailure(
                    ErrorCode.INVALID_ARGUMENT,
                    service.importConfig(
                            Map.of("servers", Map.of("three", Map.of("command", "python3"))),
                            "CREATE_ONLY",
                            null));
            assertEquals(2, registry.list().size());
        }
    }

    @Test
    void encryptsLiteralSecretsMasksAndRetainsThemAndResolvesBackendEnvironment() {
        var secrets =
                new McpSecrets(
                        Base64.getEncoder().encodeToString(new byte[32]),
                        key -> key.equals("TOKEN") ? "environment-secret" : null);
        var raw =
                config(
                        Map.of(
                                "Authorization",
                                "Bearer literal-secret",
                                "X-Ref",
                                "Bearer ${TOKEN}"));
        var sealed = secrets.seal(raw, null);
        assertFalse(json.writeValueAsString(sealed).contains("literal-secret"));
        assertEquals(
                "Bearer literal-secret", secrets.resolve(sealed).headers().get("Authorization"));
        assertEquals("Bearer environment-secret", secrets.resolve(sealed).headers().get("X-Ref"));
        assertEquals(sealed, secrets.seal(secrets.masked(sealed), sealed));
        assertThrows(IllegalArgumentException.class, () -> new McpSecrets("").seal(raw, null));
        var broken = config(Map.of("Other", sealed.headers().get("Authorization")));
        assertThrows(IllegalStateException.class, () -> secrets.resolve(broken));
        assertThrows(
                IllegalStateException.class,
                () -> secrets.resolve(config(Map.of("X-Key", "${MISSING}"))));
        var mixed = secrets.seal(config(Map.of("X-Key", "literal-part-${TOKEN}")), null);
        assertFalse(json.writeValueAsString(mixed).contains("literal-part"));
        assertEquals(
                "literal-part-environment-secret", secrets.resolve(mixed).headers().get("X-Key"));
        // Persisted JSON remains flat and round-trips typed fields and vendor extensions.
        assertEquals(
                sealed, json.readValue(json.writeValueAsString(sealed), McpConnectionConfig.class));
    }

    @Test
    void controllerSeparatesSavingFromTestingAndReturnsConflictsAndValidationErrors()
            throws Exception {
        try (var fixture = new McpRuntimeTest.Fixture()) {
            var mvc =
                    MockMvcBuilders.standaloneSetup(new McpController(fixture.service))
                            .setControllerAdvice(
                                    new ServiceResponseAdvice(), new McpRequestAdvice())
                            .build();
            String body =
                    json.writeValueAsString(
                            Map.of(
                                    "config",
                                    Map.of(
                                            "servers",
                                            Map.of(
                                                    "remote",
                                                    Map.of("url", "http://127.0.0.1:1/mcp")))));
            mvc.perform(
                            post("/api/mcp/servers/import")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].status.state").value("UNKNOWN"))
                    .andExpect(jsonPath("$.items[0].config.url").value("http://127.0.0.1:1/mcp"));
            mvc.perform(
                            post("/api/mcp/servers/import")
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("CONFLICT"));
            mvc.perform(get("/api/mcp/servers?size=0")).andExpect(status().isBadRequest());
            var server = fixture.registry.list().getFirst();
            mvc.perform(
                            put("/api/mcp/servers/" + server.id())
                                    .contentType("application/json")
                                    .content("{\"enabled\":false}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
            mvc.perform(
                            put("/api/mcp/servers/" + server.id())
                                    .contentType("application/json")
                                    .content("{\"version\":0,\"enabled\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));
            mvc.perform(post("/api/mcp/servers/" + server.id() + "/test"))
                    .andExpect(status().isConflict());
            mvc.perform(delete("/api/mcp/servers/" + server.id() + "?version=0"))
                    .andExpect(status().isConflict());
            mvc.perform(delete("/api/mcp/servers/" + server.id() + "?version=1"))
                    .andExpect(status().isNoContent());
            mvc.perform(get("/api/mcp/servers/" + server.id())).andExpect(status().isNotFound());
        }
    }

    private McpConnectionConfig config(Map<String, String> headers) {
        return new McpConfigParser()
                .normalize(
                        "remote",
                        Map.of(
                                "url",
                                "http://localhost/mcp",
                                "headers",
                                headers,
                                "vendorOption",
                                "kept"))
                .config();
    }

    private void assertFailure(ErrorCode code, ServiceResult<?> result) {
        assertEquals(code, assertInstanceOf(ServiceResult.Failure.class, result).code());
    }
}
